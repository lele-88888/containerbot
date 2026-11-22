package com.containerbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务状态持久化：服务器重启后把循环任务和各项设置接回来。
 *
 * <h3>为什么默认关闭</h3>
 * 恢复任务等于"服务器一启动假人就自己动起来"——这在某些场景下会吓人
 * （比如存档刚载入、区块还没加载完，假人就冲出去搬货）。
 * 所以默认不恢复，需要时用 {@code /cbot persist on} 明确打开。
 *
 * <h3>存哪儿</h3>
 * 存档目录下的 {@code containerbot/state.json}，跟着存档走，
 * 换存档不会串。
 *
 * <h3>存什么</h3>
 * <ul>
 *   <li>每轮任务的完整定义（物品、数量、区域、目的地、节流…）</li>
 *   <li>循环开关与间隔</li>
 *   <li>白/黑名单、精确数量、饱食度、自动进食、可用性标记</li>
 * </ul>
 *
 * <p><b>不存</b>进度类状态（走了到哪、开过哪些箱子）——那些恢复后重新算，
 * 否则会出现"以为搬过其实没搬"的错乱。</p>
 */
public final class Persistence {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "state.json";

	/** 总开关，默认关闭 */
	private static boolean enabled = false;

	/**
	 * 待生成的假人：名字 → 剩余 tick 数。
	 *
	 * <p>为什么要延迟：GCA 的 {@code fakePlayerResident} 会在开服时把假人带回来，
	 * 但它和我们的 {@code SERVER_STARTED} 谁先执行并不确定。若我们抢先生成，
	 * GCA 随后再拉一次就会撞车。等 2 秒（40 tick）让 GCA 先动手，
	 * 到时还没在线才说明确实需要我们生成。</p>
	 */
	private static final java.util.Map<String, Integer> PENDING_SPAWN = new java.util.LinkedHashMap<>();

	private static final int RESPAWN_DELAY_TICKS = 40;

	private Persistence() {}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean v) {
		enabled = v;
	}

	// ------------------------------------------------------------------ 存

	// ------------------------------------------------------------------ 数据结构

	/** 一个假人的持久化状态 */
	public static final class BotState {
		public String name;
		public boolean busy = true;
		public boolean exact, feed, eat;
		public boolean repeatEnabled;
		public int repeatIntervalTicks;
		public List<String> white = new ArrayList<>();
		public List<String> black = new ArrayList<>();
		/** 目的地选区（目的箱轮转）；都为空表示单点目的地 */
		public int[] dposA, dposB;
		public String dposDim;
		/** 累计统计（跨重启保留） */
		public long lifeMoved, lifeActiveMs, lifeTasks;
		/** 最近一次任务的完整定义（循环恢复要用它） */
		public TaskState lastTask;
	}

	/** 任务定义的可序列化形式 */
	public static final class TaskState {
		public String item;
		public int count;
		public int radius;
		public int[] boxA, boxB;
		public String boxDim;
		public int shulkerThreshold;
		public int[] deposit;
		public String depositDim;
		public boolean moveAll, dropMode;
		public int intervalTicks, itemsPerHour;
		public List<String> whiteList = new ArrayList<>();
	}

	private static final class Root {
		/**
		 * 持久化开关本身也要存。否则重启后内存里的 enabled 回到默认的 false，
		 * 就等于"永远不恢复"——用户必须先手动 /cbot persist on 再重启一次才有可能生效。
		 */
		public boolean enabled = false;
		public List<BotState> bots = new ArrayList<>();
	}

	// ------------------------------------------------------------------ 转换

	private static TaskState toState(LootTask t) {
		if (t == null) return null;
		TaskState s = new TaskState();
		s.item = t.item() == null ? null : BuiltInRegistries.ITEM.getKey(t.item()).toString();
		s.count = t.count();
		s.radius = t.radius();
		s.boxA = t.boxA() == null ? null : new int[]{t.boxA().getX(), t.boxA().getY(), t.boxA().getZ()};
		s.boxB = t.boxB() == null ? null : new int[]{t.boxB().getX(), t.boxB().getY(), t.boxB().getZ()};
		s.boxDim = t.boxDim() == null ? null : t.boxDim().location().toString();
		s.shulkerThreshold = t.shulkerThreshold();
		s.deposit = t.deposit() == null ? null
				: new int[]{t.deposit().getX(), t.deposit().getY(), t.deposit().getZ()};
		s.depositDim = t.depositDim() == null ? null : t.depositDim().location().toString();
		s.moveAll = t.moveAll();
		s.dropMode = t.dropMode();
		s.intervalTicks = t.intervalTicks();
		s.itemsPerHour = t.itemsPerHour();
		if (t.whiteList() != null) {
			for (Item i : t.whiteList()) {
				ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
				if (id != null) s.whiteList.add(id.toString());
			}
		}
		return s;
	}

	private static LootTask fromState(TaskState s) {
		if (s == null) return null;
		Item item = s.item == null ? null : BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(s.item));
		if (s.item != null && (item == null || item == net.minecraft.world.item.Items.AIR)) {
			return null;   // 物品已被移除（换了模组/版本），这条任务作废
		}
		BlockPos boxA = s.boxA == null ? null : new BlockPos(s.boxA[0], s.boxA[1], s.boxA[2]);
		BlockPos boxB = s.boxB == null ? null : new BlockPos(s.boxB[0], s.boxB[1], s.boxB[2]);
		BlockPos dep = s.deposit == null ? null : new BlockPos(s.deposit[0], s.deposit[1], s.deposit[2]);
		ResourceKey<Level> boxDim = s.boxDim == null ? null
				: ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
						ResourceLocation.parse(s.boxDim));
		ResourceKey<Level> depDim = s.depositDim == null ? null
				: ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
						ResourceLocation.parse(s.depositDim));

		List<Item> white = new ArrayList<>();
		for (String id : s.whiteList) {
			Item i = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(id));
			if (i != null && i != net.minecraft.world.item.Items.AIR) white.add(i);
		}

		return new LootTask(item, s.count, s.radius, boxA, boxB, boxDim, s.shulkerThreshold,
				dep, depDim, s.moveAll, s.dropMode, s.intervalTicks, s.itemsPerHour, List.copyOf(white));
	}

	// ------------------------------------------------------------------ 延迟生成

	/**
	 * 每个服务器 tick 调用：处理排队中的假人生成。
	 *
	 * <p>延迟到期后再查一次在线状态——若这期间已被 GCA 的 {@code fakePlayerResident}
	 * 带回来，就跳过生成。</p>
	 */
	public static void tick(MinecraftServer server) {
		if (PENDING_SPAWN.isEmpty()) return;

		var it = PENDING_SPAWN.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			int left = e.getValue() - 1;
			if (left > 0) {
				e.setValue(left);
				continue;
			}
			it.remove();

			String name = e.getKey();
			if (server.getPlayerList().getPlayerByName(name) != null) {
				ContainerBotMod.LOGGER.info(
						"[ContainerBot] 假人 {} 已由 GCA fakePlayerResident 带回来，跳过生成", name);
				continue;
			}
			try {
				server.getCommands().performPrefixedCommand(
						server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
						"player " + name + " spawn");
				ContainerBotMod.LOGGER.info("[ContainerBot] 已重新生成假人 {}", name);
			} catch (Throwable t) {
				ContainerBotMod.LOGGER.warn("[ContainerBot] 重新生成假人 {} 失败: {}", name, t.toString());
			}
		}
	}

	// ------------------------------------------------------------------ 落盘

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("containerbot").resolve(FILE_NAME);
	}

	/** 保存全部假人状态；失败只记日志，绝不影响关服 */
	public static void saveAll(MinecraftServer server) {
		// 注意：即使 enabled 为 false 也要写文件——要把"关闭"这个状态记下来，
		// 否则下次启动会读到旧的 enabled=true 而错误地恢复任务。
		try {
			Root root = new Root();
			root.enabled = enabled;
			for (LootBot bot : BotManager.all()) {
				BotState bs = new BotState();
				bs.name = bot.getName();
				bs.busy = bot.hasTaskFlag();
				bs.exact = bot.isExactEnabled();
				bs.feed = bot.isFeedEnabled();
				bs.eat = bot.isEatEnabled();
				bs.repeatEnabled = bot.isRepeating();
				bs.repeatIntervalTicks = bot.getRepeatIntervalTicks();
				bs.lastTask = toState(bot.getLastTask());
				bs.lifeMoved = bot.getLifeMoved();
				bs.lifeActiveMs = bot.getLifeActiveMs();
				bs.lifeTasks = bot.getLifeTasks();
				if (bot.hasDepositArea()) {
					BlockPos a = bot.getDepositAreaA();
					BlockPos b = bot.getDepositAreaB();
					bs.dposA = new int[]{a.getX(), a.getY(), a.getZ()};
					bs.dposB = new int[]{b.getX(), b.getY(), b.getZ()};
					bs.dposDim = bot.getDepositAreaDim() == null
							? null : bot.getDepositAreaDim().location().toString();
				}
				for (Item i : bot.getFilterWhite()) {
					ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
					if (id != null) bs.white.add(id.toString());
				}
				for (Item i : bot.getFilterBlack()) {
					ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
					if (id != null) bs.black.add(id.toString());
				}
				root.bots.add(bs);
			}

			Path p = file(server);
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
			ContainerBotMod.LOGGER.info("[ContainerBot] 已保存 {} 个假人的状态到 {}",
					root.bots.size(), p);
		} catch (Throwable t) {
			ContainerBotMod.LOGGER.warn("[ContainerBot] 保存状态失败（不影响关服）: {}", t.toString());
		}
	}

	/** 读回状态并恢复；失败只记日志 */
	public static void loadAll(MinecraftServer server) {
		try {
			Path p = file(server);
			if (!Files.exists(p)) {
				ContainerBotMod.LOGGER.info("[ContainerBot] 没有可恢复的状态文件");
				return;
			}
			Root root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Root.class);
			if (root == null || root.bots == null) return;

			// 以文件里记录的开关为准，并用它同步内存状态
			enabled = root.enabled;
			if (!enabled) {
				ContainerBotMod.LOGGER.info("[ContainerBot] 状态文件里持久化是关闭的，不恢复任务");
				return;
			}

			int restored = 0;
			int tasks = 0;
			for (BotState bs : root.bots) {
				if (bs == null || bs.name == null) continue;
				LootBot bot = BotManager.getOrCreate(bs.name);
				bot.setHasTaskFlag(bs.busy);
				bot.setExactEnabled(bs.exact);
				bot.setFeedEnabled(bs.feed);
				bot.setEatEnabled(bs.eat);

				List<Item> white = new ArrayList<>();
				for (String id : bs.white) {
					Item i = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(id));
					if (i != null) white.add(i);
				}
				List<Item> black = new ArrayList<>();
				for (String id : bs.black) {
					Item i = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(id));
					if (i != null) black.add(i);
				}
				bot.setFilters(white, black);
				bot.restoreLifeStats(bs.lifeMoved, bs.lifeActiveMs, bs.lifeTasks);

				// 目的地选区（目的箱轮转）
				if (bs.dposA != null && bs.dposB != null) {
					BlockPos da = new BlockPos(bs.dposA[0], bs.dposA[1], bs.dposA[2]);
					BlockPos db = new BlockPos(bs.dposB[0], bs.dposB[1], bs.dposB[2]);
					ResourceKey<Level> dd = bs.dposDim == null ? null
							: ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
									ResourceLocation.parse(bs.dposDim));
					bot.setDepositArea(da, db, dd);
				}

				LootTask last = fromState(bs.lastTask);
				bot.restorePersisted(last, bs.repeatEnabled, bs.repeatIntervalTicks);
				restored++;
				if (bs.repeatEnabled && last != null) {
					tasks++;
					// 先看假人是不是已经在线。
					// Carpet 的「假人驻留」(fakePlayerAutoRejoin) 会在开服时把人带回来，
					// 这种情况下绝不能再生一次——会和已有的实体撞车。
					if (server.getPlayerList().getPlayerByName(bs.name) != null) {
						ContainerBotMod.LOGGER.info(
								"[ContainerBot] 假人 {} 已在线（可能由 GCA fakePlayerResident 恢复），跳过生成",
								bs.name);
					} else {
						// 先排队，延迟几秒再判断——给 GCA 的驻留机制留出时间
						PENDING_SPAWN.put(bs.name, RESPAWN_DELAY_TICKS);
						bot.markWaitingSpawn();
					}
				}
			}
			ContainerBotMod.LOGGER.info("[ContainerBot] 已恢复 {} 个假人的状态，其中 {} 个循环任务",
					restored, tasks);
		} catch (Throwable t) {
			ContainerBotMod.LOGGER.warn("[ContainerBot] 恢复状态失败: {}", t.toString());
		}
	}
}
