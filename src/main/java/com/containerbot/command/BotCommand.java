package com.containerbot.command;

import com.containerbot.BotManager;
import com.containerbot.ContainerScanner;
import com.containerbot.Diag;
import com.containerbot.GotoJob;
import com.containerbot.ItemNames;
import com.containerbot.LootBot;
import com.containerbot.LootTask;
import com.containerbot.PathPlanner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /cbot 指令。
 *
 * <pre>
 *  /cbot spawn  &lt;名字&gt;                              生成 Carpet 假人
 *  /cbot start  &lt;名字&gt; &lt;物品id&gt; &lt;数量&gt; [半径]        开始自动取货
 *  /cbot stop   &lt;名字&gt;                              停止任务
 *  /cbot status                                     查看所有任务
 *  /cbot despawn&lt;名字&gt;                              移除假人
 *
 *  ---- 指定区域（只在该长方体范围内找箱子）----
 *  /cbot pos1                                       站在一角执行，设角点 1
 *  /cbot pos2                                       站在另一角执行，设角点 2
 *  /cbot region                                     查看当前选区与其中的容器数
 *  /cbot region clear                               清除选区
 * </pre>
 *
 * <p>设置过选区后，{@code /cbot start 名字 物品 数量}（不带半径）就会只取该区域内的箱子；
 * 显式给出半径则仍按假人周围的半径模式搜寻。</p>
 */
public final class BotCommand {

	/** 每个玩家自己的选区：[0] = 角点1，[1] = 角点2 */
	private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

	/**
	 * 选区所在维度。
	 *
	 * <p>两个角点必须同一维度，否则选区没有意义（长方体跨维度不存在）。
	 * 指定坐标形式的目的就是能在任意维度框区域——包括控制台执行
	 * （控制台没有实体位置，用不了"站着的角点"形式）。</p>
	 */
	private static final Map<UUID, ResourceKey<Level>> SELECTION_DIMS = new HashMap<>();

	/** 每个玩家指定的卸货箱（背包满时货物倒进这里） */
	private static final Map<UUID, BlockPos> DEPOSITS = new HashMap<>();

	/** 卸货点所在维度。为空表示与假人当前维度相同 */
	private static final Map<UUID, ResourceKey<Level>> DEPOSIT_DIMS = new HashMap<>();

	/**
	 * 目的地放置方式：false = 倒进容器（默认），true = 直接扔在地上。
	 *
	 * <p>「扔地上」模式下目的地可以是任意方块位置，不要求是容器——
	 * 适合把货倾倒在漏斗口、收集装置旁或临时堆放点。</p>
	 */
	private static final Map<UUID, Boolean> DEPOSIT_DROP = new HashMap<>();

	/**
	 * 节流间隔（tick）：每处理完一个容器后暂停多久。
	 *
	 * <p>0 = 全速（默认）。调大可显著降低假人的动作频率，减轻服务端压力。</p>
	 */
	private static final Map<UUID, Integer> PACES = new HashMap<>();

	/**
	 * 吞吐限速（物品/小时）。0 = 不限。
	 *
	 * <p>与 PACES 的区别：PACES 限制「动作频率」（容器之间固定停顿），
	 * 本项限制「实际搬运量」——不管容器里装多少，长期平均速率都不超过此值。</p>
	 */
	private static final Map<UUID, Integer> RATES = new HashMap<>();

	/**
	 * 控制台 / 命令方块执行的命令没有玩家实体，用这个哨兵 UUID 存设置。
	 *
	 * <p>这样 {@code /cbot deposit <x> <y> <z> [维度]} 与 {@code /cbot move <名字> <半径>}
	 * 也能从控制台、RCON、命令方块触发，便于无人值守与自动化测试。</p>
	 */
	private static final UUID CONSOLE = new UUID(0L, 0L);

	/** 取「当前命令来源」对应的设置归属：玩家用其 UUID，控制台用哨兵 */
	private static UUID key(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		return player != null ? player.getUUID() : CONSOLE;
	}

	/** 单个维度上的最大边长，避免误设超大区域导致卡顿 */
	private static final int MAX_EDGE = 256;

	private BotCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cbot")
				.requires(src -> src.hasPermission(2))

				.then(Commands.literal("spawn")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::spawn)))

				// 生成并直接标记为空闲——省掉一次 /cbot free
				.then(Commands.literal("spawnfree")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::spawnFree)))

				.then(Commands.literal("start")
						.then(Commands.argument("name", StringArgumentType.word())
								// 必须用 Minecraft 原生注册的参数类型！
								//
								// 血的教训：先前自定义了一个 ArgumentType 来读带冒号的
								// minecraft:diamond，服务端能解析，但 Brigadier 在把命令树
								// 同步给客户端时需要序列化参数类型，而原生
								// ArgumentTypes 只认自己注册过的类型，于是抛
								//   IllegalArgumentException: Unrecognized argument type
								// 直接把玩家踢下线（显示「无效的玩家数据」）。
								//
								// ResourceLocationArgument 是原生类型，正是为
								// minecraft:diamond 这类 ID 设计的，无同步问题。
								.then(Commands.argument("item", ResourceLocationArgument.id())
										.suggests(ItemSuggestionProvider.INSTANCE)
										.then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
												// radius 传 0 表示「自动」：设过选区就用选区，否则用默认半径
												.executes(ctx -> start(ctx, 0, LootTask.DEFAULT_SHULKER_THRESHOLD))
												.then(Commands.argument("radius", IntegerArgumentType.integer(0, 128))
														.executes(ctx -> start(ctx,
																IntegerArgumentType.getInteger(ctx, "radius"),
																LootTask.DEFAULT_SHULKER_THRESHOLD))
														// 可选第 5 参：潜影盒策略的启用阈值。
														// 目标数量 >= 该值时才把装目标物品的潜影盒整盒搬走。
														// 传 0 表示禁用潜影盒策略。
														.then(Commands.argument("shulkerThreshold",
																		IntegerArgumentType.integer(0, 1_000_000))
																.executes(ctx -> start(ctx,
																		IntegerArgumentType.getInteger(ctx, "radius"),
																		IntegerArgumentType.getInteger(ctx, "shulkerThreshold")))))))))

				// 清空任务：不带名字 = 全部；带名字 = 指定假人
				.then(Commands.literal("clear")
						.executes(BotCommand::clearAll)
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::clearOne)))

				.then(Commands.literal("stop")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::stop)))

				.then(Commands.literal("status")
						.executes(BotCommand::status))

				.then(Commands.literal("despawn")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::despawn)))

				// ---- 暂停 / 继续 ----
				// 保留全部进度，从暂停处恢复，不会重头开始。
				// 不带名字 = 对所有假人生效。
				.then(Commands.literal("pause")
						.executes(ctx -> setPaused(ctx, true, null))
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> setPaused(ctx, true,
										StringArgumentType.getString(ctx, "name")))))

				.then(Commands.literal("resume")
						.executes(ctx -> setPaused(ctx, false, null))
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> setPaused(ctx, false,
										StringArgumentType.getString(ctx, "name")))))

				// ---- 卸货箱 / 搬家目的地 ----
				// 看着某个箱子执行 /cbot deposit 即可把它指定为卸货点。
				.then(Commands.literal("deposit")
						.executes(BotCommand::setDeposit)
						// 看着任意方块执行：切换为「直接扔在地上」
						.then(Commands.literal("drop")
								.executes(BotCommand::setDepositDrop))
						// 切回「倒进容器」
						.then(Commands.literal("box")
								.executes(BotCommand::setDepositBox))
						// 手输坐标，适合目的地很远或跨维度
						.then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
								.then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
										.then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
												.executes(BotCommand::setDepositAt)
												.then(Commands.argument("dim", StringArgumentType.word())
														.executes(BotCommand::setDepositAt)))))
						.then(Commands.literal("clear")
								.executes(BotCommand::clearDeposit)))

				// ---- 搬家模式 ----
				// 掏空选定区域（或半径内）的所有容器，全部倒进指定目的地。
				// 必须先用 /cbot deposit 指定目的地。
				.then(Commands.literal("move")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> startMove(ctx, 0))
								.then(Commands.argument("radius", IntegerArgumentType.integer(0, 128))
										.executes(ctx -> startMove(ctx,
												IntegerArgumentType.getInteger(ctx, "radius"))))))

				// ---- 节流间隔 ----
				// 每处理完一个容器后暂停指定 tick 数，降低动作频率、减轻服务端压力。
				.then(Commands.literal("pace")
						.executes(BotCommand::paceInfo)
						.then(Commands.argument("ticks", IntegerArgumentType.integer(0, 20 * 60))
								.executes(BotCommand::setPace)))

				// ---- 吞吐限速 ----
				// 限制的是"实际搬运量"（物品/小时），与节流间隔（动作频率）互补。
				.then(Commands.literal("rate")
						.executes(BotCommand::rateInfo)
						.then(Commands.argument("itemsPerHour", IntegerArgumentType.integer(0, 1_000_000))
								.executes(BotCommand::setRate)))

				// ---- 循环任务 ----
				// 跑完一轮 → 等 N 秒 → 自动重跑。失败也继续（"没货可搬"是正常状态）。
				.then(Commands.literal("repeat")
						.executes(BotCommand::repeatInfo)
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::repeatInfoOne)
								.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
										.executes(BotCommand::setRepeat))
								.then(Commands.literal("off")
										.executes(BotCommand::clearRepeat))))

				// 中文友好的取货：物品放最后，可写中文名（greedyString 能吃任意字符）
				.then(Commands.literal("get")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
										.then(Commands.argument("items", StringArgumentType.greedyString())
												.executes(ctx -> start(ctx, 0, LootTask.DEFAULT_SHULKER_THRESHOLD))))))

				// ---- 按物品分流 ----
				// 每条 route 生成一个排队中的搬家任务：只搬指定物品，送到指定地点。
				// 多条 route 会依次执行，就实现了"不同物品送不同目的地"。
				.then(Commands.literal("route")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
										.then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
												.then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
														.then(Commands.argument("items", StringArgumentType.greedyString())
																.executes(BotCommand::addRoute))))))
						.then(Commands.literal("list")
								.executes(BotCommand::routeInfo)))

				// ---- 白名单 / 黑名单 ----
				// 物品列表用 greedyString（最后一个参数），因此可以写中文名。
				.then(Commands.literal("filter")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::filterInfo)
								.then(Commands.literal("clear")
										.executes(BotCommand::filterClear))
								.then(Commands.literal("white")
										.then(Commands.argument("items", StringArgumentType.greedyString())
												.executes(ctx -> setFilter(ctx, true))))
								.then(Commands.literal("black")
										.then(Commands.argument("items", StringArgumentType.greedyString())
												.executes(ctx -> setFilter(ctx, false))))))

				// ---- 统计与帮助 ----
				.then(Commands.literal("stats")
						.executes(ctx -> stats(ctx, null))
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "name")))))

				.then(Commands.literal("help")
						.executes(BotCommand::help))

				// ---- 盘点 / 导航 / 自检 ----
				.then(Commands.literal("scan")
						.executes(ctx -> scan(ctx, 32))
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> scan(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

				.then(Commands.literal("goto")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
										.then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
												.then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
														.executes(BotCommand::gotoPos)
														.then(Commands.argument("dim", StringArgumentType.word())
																.executes(BotCommand::gotoPos)))))))

				.then(Commands.literal("test")
						.executes(BotCommand::selfTest))

				// ---- 可用性标记（安全策略）----
				// 默认所有假人都被标记为「有任务」，必须显式 /cbot free 才能接任务。
				// 假人下线后会自动重新标记为「有任务」。
				.then(Commands.literal("free")
						.executes(BotCommand::listFree)
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> setBusyFlag(ctx, false))))

				.then(Commands.literal("busy")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> setBusyFlag(ctx, true))))

				// ---- 分组 / 标签 ----
				.then(Commands.literal("tag")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("group", StringArgumentType.string())
										.executes(BotCommand::addTag))))

				.then(Commands.literal("untag")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("group", StringArgumentType.string())
										.executes(BotCommand::removeTag))))

				.then(Commands.literal("group")
						.executes(BotCommand::groupList)
						.then(Commands.argument("group", StringArgumentType.string())
								.then(Commands.literal("pause").executes(ctx -> groupAction(ctx, "pause")))
								.then(Commands.literal("resume").executes(ctx -> groupAction(ctx, "resume")))
								.then(Commands.literal("stop").executes(ctx -> groupAction(ctx, "stop")))
								.then(Commands.literal("feed")
										.then(Commands.literal("on").executes(ctx -> groupAction(ctx, "feed on")))
										.then(Commands.literal("off").executes(ctx -> groupAction(ctx, "feed off"))))
								.then(Commands.literal("exact")
										.then(Commands.literal("on").executes(ctx -> groupAction(ctx, "exact on")))
										.then(Commands.literal("off").executes(ctx -> groupAction(ctx, "exact off"))))))

				// ---- 维持饱食度 / 自动进食 ----
				// feed = 可选兜底（默认关闭），直接补满饥饿值（相当于"作弊式保命"）
				// eat  = 默认行为（默认开启），让假人像真人一样吃东西
				.then(Commands.literal("feed")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::feedInfo)
								.then(Commands.literal("on")
										.executes(ctx -> setFeed(ctx, true)))
								.then(Commands.literal("off")
										.executes(ctx -> setFeed(ctx, false)))
								.then(Commands.literal("test")
										.executes(BotCommand::feedTest))))

				.then(Commands.literal("exact")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::exactInfo)
								.then(Commands.literal("on")
										.executes(ctx -> setExact(ctx, true)))
								.then(Commands.literal("off")
										.executes(ctx -> setExact(ctx, false)))))

				.then(Commands.literal("eat")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(BotCommand::eatInfo)
								.then(Commands.literal("on")
										.executes(ctx -> setEat(ctx, true)))
								.then(Commands.literal("off")
										.executes(ctx -> setEat(ctx, false)))
								.then(Commands.literal("test")
										.executes(BotCommand::eatTest))))

				// ---- 诊断 ----
				// 修 bug 必先有观测手段：诊断常驻，不必每次改代码临时加输出。
				.then(Commands.literal("debug")
						.executes(BotCommand::debugStatus)
						.then(Commands.literal("on")
								.executes(ctx -> setDebug(ctx, true)))
						.then(Commands.literal("off")
								.executes(ctx -> setDebug(ctx, false)))
						.then(Commands.literal("dump")
								.executes(BotCommand::debugDump))
						// 测试钩子总开关（feed test / eat test 需要它打开）
						.then(Commands.literal("hooks")
								.executes(BotCommand::testHooksInfo)
								.then(Commands.literal("on")
										.executes(ctx -> setTestHooks(ctx, true)))
								.then(Commands.literal("off")
										.executes(ctx -> setTestHooks(ctx, false)))))

				// ---- 区域选择 ----
				// 区域选择：两种形式
				//   1) 不带参数 → 用"你站的位置"（需要玩家）
				//   2) 给坐标 → <x> <y> <z> [维度]（控制台也能用）
				.then(Commands.literal("pos1")
						.executes(ctx -> setPos(ctx, 0))
						.then(posArgs(0)))

				.then(Commands.literal("pos2")
						.executes(ctx -> setPos(ctx, 1))
						.then(posArgs(1)))

				.then(Commands.literal("region")
						.executes(BotCommand::regionInfo)
						.then(Commands.literal("clear")
								.executes(BotCommand::regionClear)))
		);
	}

	// ------------------------------------------------------------------ 区域选择

	/** 坐标形式的参数树：&lt;x&gt; &lt;y&gt; &lt;z&gt; [维度] */
	private static ArgumentBuilder<CommandSourceStack, ?> posArgs(int index) {
		return Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
				.then(Commands.argument("y", IntegerArgumentType.integer(-2048, 2048))
						.then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
								.executes(ctx -> setPosAt(ctx, index))
								.then(Commands.argument("dim", StringArgumentType.word())
										.executes(ctx -> setPosAt(ctx, index)))));
	}

	/** 用玩家当前位置设角点（自动记录玩家所在维度） */
	private static int setPos(CommandContext<CommandSourceStack> ctx, int index) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("控制台没有位置概念，请用 /cbot pos" + (index + 1)
					+ " <x> <y> <z> [维度]"));
			return 0;
		}
		return applyPos(src, index, player.blockPosition().immutable(),
				player.level().dimension());
	}

	/** 用显式坐标设角点（控制台可用，可指定维度） */
	private static int setPosAt(CommandContext<CommandSourceStack> ctx, int index) {
		CommandSourceStack src = ctx.getSource();
		BlockPos p = new BlockPos(
				IntegerArgumentType.getInteger(ctx, "x"),
				IntegerArgumentType.getInteger(ctx, "y"),
				IntegerArgumentType.getInteger(ctx, "z"));

		ServerPlayer player = src.getPlayer();
		ResourceKey<Level> dim = player != null ? player.level().dimension() : Level.OVERWORLD;

		String dimStr = null;
		try {
			dimStr = StringArgumentType.getString(ctx, "dim");
		} catch (IllegalArgumentException ignored) {
			// 未给维度参数 → 用默认
		}
		if (dimStr != null) {
			ResourceLocation id = ResourceLocation.tryParse(
					dimStr.indexOf(':') >= 0 ? dimStr : "minecraft:" + dimStr);
			if (id == null) {
				src.sendFailure(Component.literal("维度 ID 无效: " + dimStr));
				return 0;
			}
			dim = ResourceKey.create(Registries.DIMENSION, id);
		}
		return applyPos(src, index, p, dim);
	}

	/** 写入角点并校验维度一致性与体积 */
	private static int applyPos(CommandSourceStack src, int index, BlockPos p, ResourceKey<Level> dim) {
		UUID owner = key(src);
		BlockPos[] sel = SELECTIONS.computeIfAbsent(owner, k -> new BlockPos[2]);
		ResourceKey<Level> prevDim = SELECTION_DIMS.get(owner);

		// 两个角点必须同一维度——长方体跨维度没有意义
		if (prevDim != null && sel[1 - index] != null && !prevDim.equals(dim)) {
			src.sendFailure(Component.literal(
					"维度不一致：另一角在 " + prevDim.location() + "，本次是 " + dim.location()
							+ "。选区不能跨维度，请重新设两个角"));
			return 0;
		}

		sel[index] = p;
		SELECTION_DIMS.put(owner, dim);

		String label = index == 0 ? "角点1" : "角点2";
		String dimText = "（维度 " + dim.location() + "）";

		if (sel[0] != null && sel[1] != null) {
			String err = validateBox(sel[0], sel[1]);
			if (err != null) {
				src.sendFailure(Component.literal(
						label + " 已设为 " + fmt(p) + dimText + "，但选区无效: " + err));
				return 0;
			}
			src.sendSuccess(() -> Component.literal(
					label + " 已设为 " + fmt(p) + dimText + "，选区就绪 " + describeBox(sel[0], sel[1])
							+ "（" + size(sel[0], sel[1]) + " 格）"), false);
		} else {
			src.sendSuccess(() -> Component.literal(
					label + " 已设为 " + fmt(p) + dimText
							+ "，再执行 /cbot pos" + (index == 0 ? "2" : "1") + " 设定另一角"), false);
		}
		return 1;
	}

	private static int regionInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		UUID owner = key(src);
		BlockPos[] sel = SELECTIONS.get(owner);
		if (sel == null || sel[0] == null || sel[1] == null) {
			src.sendSuccess(() -> Component.literal(
					"尚未设定选区。两种方式：\n"
							+ "  · 站在两个对角各执行一次 /cbot pos1 与 /cbot pos2\n"
							+ "  · 或直接给坐标：/cbot pos1 <x> <y> <z> [维度]"), false);
			return 0;
		}

		String err = validateBox(sel[0], sel[1]);
		if (err != null) {
			src.sendFailure(Component.literal("选区无效: " + err));
			return 0;
		}

		ResourceKey<Level> dim = SELECTION_DIMS.get(owner);
		String dimText = dim == null ? "" : "（维度 " + dim.location() + "）";

		// 顺便数一下选区里有多少容器，方便确认选对了
		int count = -1;
		MinecraftServer server = src.getServer();
		ServerLevel level = dim != null ? server.getLevel(dim)
				: (src.getPlayer() != null && src.getPlayer().level() instanceof ServerLevel l ? l : null);
		if (level != null) {
			count = ContainerScanner.scanBox(level, sel[0], sel[1]).size();
		}

		final int c = count;
		src.sendSuccess(() -> Component.literal(
				"选区 " + describeBox(sel[0], sel[1]) + dimText + "（" + size(sel[0], sel[1]) + " 格）"
						+ (c >= 0 ? "，其中容器 " + c + " 个" : "")), false);
		src.sendSuccess(() -> Component.literal(
				"直接执行 /cbot move <名字> 或 /cbot start <名字> <物品> <数量> 即可只用该区域"), false);
		return 1;
	}

	private static int regionClear(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		UUID owner = key(src);
		SELECTIONS.remove(owner);
		SELECTION_DIMS.remove(owner);
		src.sendSuccess(() -> Component.literal("选区已清除，将回退为半径模式"), false);
		return 1;
	}

	private static String validateBox(BlockPos a, BlockPos b) {
		int dx = Math.abs(a.getX() - b.getX());
		int dy = Math.abs(a.getY() - b.getY());
		int dz = Math.abs(a.getZ() - b.getZ());
		if (dx > MAX_EDGE || dy > MAX_EDGE || dz > MAX_EDGE) {
			return "单边不得超过 " + MAX_EDGE + " 格（当前 " + dx + "×" + dy + "×" + dz + "）";
		}
		return null;
	}

	private static String describeBox(BlockPos a, BlockPos b) {
		return "(" + Math.min(a.getX(), b.getX()) + "," + Math.min(a.getY(), b.getY()) + ","
				+ Math.min(a.getZ(), b.getZ()) + ")~(" + Math.max(a.getX(), b.getX()) + ","
				+ Math.max(a.getY(), b.getY()) + "," + Math.max(a.getZ(), b.getZ()) + ")";
	}

	private static int size(BlockPos a, BlockPos b) {
		return (Math.abs(a.getX() - b.getX()) + 1)
				* (Math.abs(a.getY() - b.getY()) + 1)
				* (Math.abs(a.getZ() - b.getZ()) + 1);
	}

	// ------------------------------------------------------------------ 限速

	private static int setRate(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int rate = IntegerArgumentType.getInteger(ctx, "itemsPerHour");
		RATES.put(key(src), rate);
		src.sendSuccess(() -> Component.literal(rate == 0
				? "已取消吞吐限速（不限速）"
				: "吞吐限速已设为 " + rate + " 件/小时（约 " + (rate / 60) + " 件/分钟）；之后下的任务生效"),
				true);
		return 1;
	}

	private static int rateInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int rate = RATES.getOrDefault(key(src), 0);
		src.sendSuccess(() -> Component.literal("当前吞吐限速: "
				+ (rate == 0 ? "不限" : rate + " 件/小时（约 " + (rate / 60) + " 件/分钟）")
				+ "；用 /cbot rate <件/小时> 修改"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 暂停

	/**
	 * 暂停 / 继续。
	 *
	 * @param name 为 null 时对所有假人生效
	 */
	private static int setPaused(CommandContext<CommandSourceStack> ctx, boolean pause, String name) {
		CommandSourceStack src = ctx.getSource();

		if (name == null) {
			var bots = BotManager.all();
			if (bots.isEmpty()) {
				src.sendFailure(Component.literal("当前没有任何假人任务"));
				return 0;
			}
			for (LootBot bot : bots) {
				if (pause) bot.pause(); else bot.resume();
			}
			final int n = bots.size();
			src.sendSuccess(() -> Component.literal(
					(pause ? "已暂停 " : "已继续 ") + n + " 个假人"), true);
			return n;
		}

		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		if (pause) bot.pause(); else bot.resume();
		src.sendSuccess(() -> Component.literal(
				(pause ? "已暂停 " : "已继续 ") + name
						+ (pause ? "（进度保留，/cbot resume " + name + " 恢复）" : "")), true);
		return 1;
	}

	// ------------------------------------------------------------------ 节流

	private static int setPace(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		PACES.put(key(src), ticks);
		src.sendSuccess(() -> Component.literal(ticks == 0
				? "已取消节流（全速运行）"
				: "节流间隔已设为 " + ticks + " tick（约 " + String.format("%.1f", ticks / 20.0)
						+ " 秒/容器）；之后下的任务生效"), true);
		return 1;
	}

	private static int paceInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int ticks = PACES.getOrDefault(key(src), 0);
		src.sendSuccess(() -> Component.literal("当前节流间隔: " + ticks + " tick"
				+ (ticks == 0 ? "（全速）" : "（约 " + String.format("%.1f", ticks / 20.0) + " 秒/容器）")
				+ "；用 /cbot pace <tick> 修改"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 循环任务

	private static int setRepeat(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		int sec = IntegerArgumentType.getInteger(ctx, "seconds");

		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		if (!bot.enableRepeat(sec * 20)) {
			src.sendFailure(Component.literal("该假人还没有任何任务。先执行一次 /cbot move 或 /cbot start，再开启循环"));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(
				"已开启循环: " + name + " 每 " + sec + " 秒重跑一次 —— " + bot.describeRepeat()), true);
		return 1;
	}

	private static int clearRepeat(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.disableRepeat();
		src.sendSuccess(() -> Component.literal("已停止 " + name + " 的循环任务"), true);
		return 1;
	}

	private static int repeatInfoOne(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(name + " 循环: " + bot.describeRepeat()), false);
		return 1;
	}

	private static int repeatInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var bots = BotManager.all();
		if (bots.isEmpty()) {
			src.sendSuccess(() -> Component.literal("当前没有任何假人任务"), false);
			return 0;
		}
		boolean any = false;
		for (LootBot bot : bots) {
			if (!bot.isRepeating()) continue;
			any = true;
			String line = bot.getName() + " 循环: " + bot.describeRepeat();
			src.sendSuccess(() -> Component.literal(line), false);
		}
		if (!any) {
			src.sendSuccess(() -> Component.literal(
					"当前没有循环中的任务。用法: /cbot repeat <名字> <间隔秒>"), false);
		}
		return 1;
	}

	// ------------------------------------------------------------------ 分流

	private static int addRoute(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		BlockPos dest = new BlockPos(
				IntegerArgumentType.getInteger(ctx, "x"),
				IntegerArgumentType.getInteger(ctx, "y"),
				IntegerArgumentType.getInteger(ctx, "z"));
		String raw = StringArgumentType.getString(ctx, "items");

		if (src.getServer().getPlayerList().getPlayerByName(name) == null) {
			src.sendFailure(Component.literal("找不到玩家或假人 " + name + "，请先执行 /cbot spawn " + name));
			return 0;
		}
		String err = ItemNames.resolveListError(raw);
		if (err != null) {
			src.sendFailure(Component.literal(err));
			return 0;
		}
		var items = ItemNames.resolveList(raw);
		if (items.isEmpty()) {
			src.sendFailure(Component.literal("没有解析出任何物品"));
			return 0;
		}

		LootBot bot = BotManager.getOrCreate(name);
		// 安全策略：只有标记为空闲的假人才能接新任务
		if (!requireAvailable(src, bot)) return 0;
		UUID owner = key(src);

		// 区域沿用当前选区；没设选区就用半径 32
		BlockPos boxA = null;
		BlockPos boxB = null;
		ResourceKey<Level> boxDim = null;
		BlockPos[] sel = SELECTIONS.get(owner);
		if (sel != null && sel[0] != null && sel[1] != null && validateBox(sel[0], sel[1]) == null) {
			boxA = sel[0];
			boxB = sel[1];
			boxDim = SELECTION_DIMS.get(owner);
		}

		ResourceKey<Level> destDim = src.getPlayer() != null
				? src.getPlayer().level().dimension()
				: (boxDim != null ? boxDim : Level.OVERWORLD);

		String itemText = describeItems(items);
		LootTask t = LootTask.move(32, boxA, boxB, boxDim, dest, destDim, false,
				PACES.getOrDefault(owner, 0), RATES.getOrDefault(owner, 0), List.copyOf(items));
		bot.submit(src.getServer(), t);

		final int queued = bot.getQueueSize();
		src.sendSuccess(() -> Component.literal(
				"已加入分流: " + name + " 把 " + itemText + " 从 " + t.scopeText()
						+ " 送到 " + fmt(dest) + (queued > 0 ? "（排队中，前面还有 " + queued + " 个）" : "")), true);
		return 1;
	}

	private static String describeItems(List<Item> items) {
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Item it : items) {
			if (n++ > 0) sb.append("、");
			if (n > 6) { sb.append("等").append(items.size()).append("种"); break; }
			sb.append(ItemNames.displayName(it));
		}
		return sb.toString();
	}

	private static int routeInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal(
				"分流用法: /cbot route <名字> <x> <y> <z> <物品列表>\n"
						+ "  每条命令生成一个排队任务，只搬指定物品送到该坐标。\n"
						+ "  连写多条即可实现「不同物品送不同目的地」。物品支持中文名。"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 白/黑名单

	private static int setFilter(CommandContext<CommandSourceStack> ctx, boolean white) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		String raw = StringArgumentType.getString(ctx, "items");

		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}

		String err = ItemNames.resolveListError(raw);
		if (err != null) {
			src.sendFailure(Component.literal(err));
			return 0;
		}
		var items = ItemNames.resolveList(raw);
		if (items.isEmpty()) {
			src.sendFailure(Component.literal("没有解析出任何物品"));
			return 0;
		}

		var target = white ? bot.getFilterWhite() : bot.getFilterBlack();
		target.clear();
		target.addAll(items);

		src.sendSuccess(() -> Component.literal(
				name + " 的" + (white ? "白名单" : "黑名单") + "已设为 " + bot.describeFilter()), true);
		return 1;
	}

	private static int filterInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(name + " 过滤: " + bot.describeFilter()
				+ "（只对搬家模式生效；用 /cbot filter " + name + " white|black <物品列表>  设置，支持中文）"), false);
		return 1;
	}

	private static int filterClear(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.clearFilter();
		src.sendSuccess(() -> Component.literal("已清除 " + name + " 的物品过滤"), true);
		return 1;
	}

	// ------------------------------------------------------------------ 精确数量

	private static int setExact(CommandContext<CommandSourceStack> ctx, boolean on) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.setExactEnabled(on);
		src.sendSuccess(() -> Component.literal(
				(on ? "已开启 " : "已关闭 ") + name + " 的精确数量模式"
						+ (on ? "（超过剩余需求的那一组不会被搬，保证不超额；"
								+ "代价是只有一大组时可能一个都拿不到）" : "")), true);
		return 1;
	}

	private static int exactInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(name + " 精确数量模式: "
				+ (bot.isExactEnabled() ? "开启" : "关闭（默认）")
				+ "；用 /cbot exact " + name + " on|off 切换"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 可用性标记

	/** 安全策略：只有被标记为空闲、且当前没在跑任务的假人才能接新任务 */
	private static boolean requireAvailable(CommandSourceStack src, LootBot bot) {
		String reason = bot.unavailableReason();
		if (reason == null) return true;
		src.sendFailure(Component.literal("假人 " + bot.getName() + " " + reason));
		return false;
	}

	private static int setBusyFlag(CommandContext<CommandSourceStack> ctx, boolean busy) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name
					+ "（先用 /cbot spawn 生成，或用任意 /cbot 命令建立记录）"));
			return 0;
		}
		bot.setHasTaskFlag(busy);
		src.sendSuccess(() -> Component.literal(busy
				? "已把 " + name + " 标记为【有任务】（不会再给它派活）"
				: "已把 " + name + " 标记为【空闲】，可以接任务了"), true);
		return 1;
	}

	private static int listFree(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var bots = BotManager.all();
		if (bots.isEmpty()) {
			src.sendSuccess(() -> Component.literal("当前没有任何假人记录"), false);
			return 0;
		}
		int free = 0;
		for (LootBot bot : bots) {
			boolean avail = bot.isAvailable();
			if (avail) free++;
			String line = (avail ? "[空闲] " : "[占用] ") + bot.getName()
					+ (bot.hasTaskFlag() ? "  (标记为有任务)" : "")
					+ (bot.isRunning() ? "  (正在执行: " + bot.getState().label() + ")" : "");
			src.sendSuccess(() -> Component.literal(line), false);
		}
		final int f = free;
		src.sendSuccess(() -> Component.literal("可用假人: " + f + "/" + bots.size()
				+ "（默认全部标记为有任务，用 /cbot free <名字> 解锁）"), false);
		return Math.max(1, free);
	}

	// ------------------------------------------------------------------ 分组 / 标签

	private static int addTag(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		String group = StringArgumentType.getString(ctx, "group");
		BotManager.tag(name, group);
		src.sendSuccess(() -> Component.literal(
				"已把 " + name + " 加入分组「" + group + "」（当前 " + BotManager.membersOf(group).size() + " 个成员）"),
				true);
		return 1;
	}

	private static int removeTag(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		String group = StringArgumentType.getString(ctx, "group");
		if (!BotManager.untag(name, group)) {
			src.sendFailure(Component.literal(name + " 不在分组「" + group + "」里"));
			return 0;
		}
		src.sendSuccess(() -> Component.literal("已把 " + name + " 移出分组「" + group + "」"), true);
		return 1;
	}

	private static int groupList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var groups = BotManager.allGroups();
		if (groups.isEmpty()) {
			src.sendSuccess(() -> Component.literal(
					"还没有任何分组。用 /cbot tag <名字> <组名> 创建"), false);
			return 0;
		}
		for (var e : groups.entrySet()) {
			String line = "「" + e.getKey() + "」" + e.getValue().size() + " 个: "
					+ String.join(", ", e.getValue());
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return groups.size();
	}

	/** 对一个分组执行操作 */
	private static int groupAction(CommandContext<CommandSourceStack> ctx, String action) {
		CommandSourceStack src = ctx.getSource();
		String group = StringArgumentType.getString(ctx, "group");
		var members = BotManager.membersOf(group);
		if (members.isEmpty()) {
			src.sendFailure(Component.literal("分组「" + group + "」不存在或没有成员"));
			return 0;
		}

		int affected = 0;
		for (String member : members) {
			LootBot bot = BotManager.get(member);
			if (bot == null) continue;
			switch (action) {
				case "pause" -> bot.pause();
				case "resume" -> bot.resume();
				case "stop" -> bot.clearAll();
				case "feed on" -> bot.setFeedEnabled(true);
				case "feed off" -> bot.setFeedEnabled(false);
				case "exact on" -> bot.setExactEnabled(true);
				case "exact off" -> bot.setExactEnabled(false);
				default -> { continue; }
			}
			affected++;
		}

		final int n = affected;
		src.sendSuccess(() -> Component.literal(
				"分组「" + group + "」: 已对 " + n + "/" + members.size() + " 个假人执行 " + action), true);
		return Math.max(1, n);
	}

	// ------------------------------------------------------------------ 维持饱食度

	private static int setFeed(CommandContext<CommandSourceStack> ctx, boolean on) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.setFeedEnabled(on);
		src.sendSuccess(() -> Component.literal(
				(on ? "已开启 " : "已关闭 ") + name + " 的饱食度维持"
						+ (on ? "（直接补满饥饿值，同时会自然回血）" : "")), true);
		return 1;
	}

	private static int feedTest(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!requireTestHooks(src)) return 0;
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		ServerPlayer sp = src.getServer().getPlayerList().getPlayerByName(name);
		if (sp == null) {
			src.sendFailure(Component.literal("假人 " + name + " 不在线"));
			return 0;
		}
		boolean on = bot.isFeedEnabled();
		int before = bot.forceHunger(sp, 10, 0.0f);
		src.sendSuccess(() -> Component.literal(
				"已把 " + name + " 的饥饿值从 " + before + " 打到 10（饱和度 0）。"
						+ (on ? "feed 已开启，下一 tick 应自动补回 20。"
								: "⚠ feed 当前是关闭的，饥饿值会保持在 10——想看补满请先 /cbot feed " + name + " on")), true);
		return 1;
	}

	private static int feedInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(name + " 饱食度维持: "
				+ (bot.isFeedEnabled() ? "开启" : "关闭（默认）")
				+ "；用 /cbot feed " + name + " on|off 切换"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 自动进食

	private static int setEat(CommandContext<CommandSourceStack> ctx, boolean on) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.setEatEnabled(on);
		src.sendSuccess(() -> Component.literal(
				(on ? "已开启 " : "已关闭 ") + name + " 的自动进食"
						+ (on ? "（饥饿值低于 18 时吃背包里的食物；需要背包里有食物）" : "")), true);
		return 1;
	}

	private static int eatTest(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!requireTestHooks(src)) return 0;
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		ServerPlayer sp = src.getServer().getPlayerList().getPlayerByName(name);
		if (sp == null) {
			src.sendFailure(Component.literal("假人 " + name + " 不在线"));
			return 0;
		}
		bot.eatOnce(sp, true);
		src.sendSuccess(() -> Component.literal(
				"已让 " + name + " 强制吃一次（无视饥饿值与开关）——看背包里的食物是否减少 1"), true);
		return 1;
	}

	private static int eatInfo(CommandContext<CommandSourceStack> ctx) {		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		src.sendSuccess(() -> Component.literal(name + " 自动进食: "
				+ (bot.isEatEnabled() ? "开启（默认）" : "关闭")
				+ "，属于行为模拟、非保命机制；保命请用 /cbot feed "
				+ name + " on"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 测试钩子

	/**
	 * 测试钩子总开关，<b>默认关闭</b>。
	 *
	 * <p>钩子（{@code feed test} / {@code eat test}）会直接改写假人的 {@code FoodData}
	 * 或强制它进食，属于调试手段，不该在日常使用中误触。默认关闭，需要时显式打开。</p>
	 */
	private static boolean testHooks = false;

	private static int setTestHooks(CommandContext<CommandSourceStack> ctx, boolean on) {
		CommandSourceStack src = ctx.getSource();
		testHooks = on;
		src.sendSuccess(() -> Component.literal(
				"测试钩子已" + (on ? "开启（feed test / eat test 可用）" : "关闭（默认）")), true);
		return 1;
	}

	private static int testHooksInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("测试钩子: " + (testHooks ? "开启" : "关闭（默认）")
				+ "；用 /cbot debug hooks on|off 切换"), false);
		return 1;
	}

	/** 测试钩子的统一守卫 */
	private static boolean requireTestHooks(CommandSourceStack src) {
		if (testHooks) return true;
		src.sendFailure(Component.literal(
				"测试钩子默认关闭。要验证这条路径，先执行 /cbot debug hooks on"));
		return false;
	}

	// ------------------------------------------------------------------ 统计 / 帮助

	private static int stats(CommandContext<CommandSourceStack> ctx, String name) {
		CommandSourceStack src = ctx.getSource();
		if (name != null) {
			LootBot bot = BotManager.get(name);
			if (bot == null) {
				src.sendFailure(Component.literal("没有该假人的记录: " + name));
				return 0;
			}
			sendLines(src, "【" + bot.getName() + "】\n" + bot.describeStats());
			return 1;
		}
		var bots = BotManager.all();
		if (bots.isEmpty()) {
			src.sendSuccess(() -> Component.literal("当前没有任何假人任务"), false);
			return 0;
		}
		for (LootBot bot : bots) {
			sendLines(src, "【" + bot.getName() + "】\n" + bot.describeStats());
		}
		return bots.size();
	}

	/** 按行发送，避免长消息被聊天栏截断 */
	private static void sendLines(CommandSourceStack src, String text) {
		for (String line : text.split("\n")) {
			src.sendSuccess(() -> Component.literal(line), false);
		}
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		sendLines(src, String.join("\n",
				"===== ContainerBot 命令 =====",
				"【假人】",
				"  /cbot spawn <名字>            生成假人（在你站的位置）",
				"  /cbot despawn <名字>          移除假人（物品掉地上）",
				"【取货】只拿指定物品",
				"  /cbot start <名字> <物品> <数量> [半径] [潜影盒阈值]",
				"  /cbot get <名字> <数量> <物品...>     物品放最后，可写中文",
				"【搬家】掏空区域内所有容器",
				"  /cbot move <名字> [半径]",
				"  /cbot route <名字> <x> <y> <z> <物品...>   只搬指定物品送到该点",
				"【目的地】",
				"  /cbot deposit                  准星对着箱子 → 倒入容器",
				"  /cbot deposit drop             准星对着任意位置 → 扔在地上",
				"  /cbot deposit <x> <y> <z> [维度]   手输坐标，自动判定",
				"  /cbot deposit clear",
				"【区域】",
				"  /cbot pos1 | pos2 [<x> <y> <z> [维度]]   设选区两角",
				"  /cbot region | region clear",
				"【过滤】只对搬家生效",
				"  /cbot filter <名字> white|black <物品...>",
				"  /cbot filter <名字> clear",
				"【控制】",
				"  /cbot status                  查看进度",
				"  /cbot stats [名字]            搬运统计",
				"  /cbot pause|resume [名字]     暂停 / 继续（保留进度）",
				"  /cbot stop <名字>             停止任务",
				"  /cbot clear [名字]            清空任务（含循环）",
				"  /cbot repeat <名字> <秒>      循环任务；<名字> off 停止",
				"【调速】",
				"  /cbot pace <tick>             节流：每容器停 N tick（20=1秒）",
				"  /cbot rate <件/小时>          限速：限制实际搬运吞吐",
				"【诊断】",
				"  /cbot debug on|off|dump       诊断开关 / 导出记录",
				"物品名：minecraft:diamond / diamond / 钻石（中文需客户端是中文）"));
		return 1;
	}

	// ------------------------------------------------------------------ 盘点 / 导航 / 自检

	/** 只统计不搬运：扫描容器并汇总内容物 */
	private static int scan(CommandContext<CommandSourceStack> ctx, int radius) {
		CommandSourceStack src = ctx.getSource();
		UUID owner = key(src);
		BlockPos[] sel = SELECTIONS.get(owner);
		ResourceKey<Level> dim = SELECTION_DIMS.get(owner);
		ServerPlayer player = src.getPlayer();

		ServerLevel level;
		List<BlockPos> found;
		String scope;

		if (sel != null && sel[0] != null && sel[1] != null && validateBox(sel[0], sel[1]) == null) {
			level = dim != null ? src.getServer().getLevel(dim)
					: (player != null && player.level() instanceof ServerLevel l ? l : null);
			if (level == null) {
				src.sendFailure(Component.literal("选区所在维度不可用"));
				return 0;
			}
			found = ContainerScanner.scanBox(level, sel[0], sel[1]);
			scope = "指定区域 " + describeBox(sel[0], sel[1]);
		} else if (player != null && player.level() instanceof ServerLevel l) {
			level = l;
			found = ContainerScanner.scanRadius(level, player.blockPosition(), radius);
			scope = "半径 " + radius + " 格";
		} else {
			src.sendFailure(Component.literal("控制台没有位置概念，请先用 /cbot pos1/pos2 设选区"));
			return 0;
		}

		Map<String, Integer> byType = new java.util.TreeMap<>();
		Map<Item, Integer> items = new java.util.LinkedHashMap<>();
		BlockPos fullest = null;
		int fullestUsed = -1;
		int fullestSize = 0;
		int containers = 0;

		for (BlockPos p : found) {
			if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
			containers++;
			// 不能用 getClass().getSimpleName()：Fabric 运行时用的是 intermediary 类名，
			// 客户端和服务端都会显示成 class_2595 这种。改用方块自己的本地化名。
			String typeName;
			try {
				typeName = level.getBlockState(p).getBlock().getName().getString();
			} catch (Throwable t) {
				typeName = "容器";
			}
			byType.merge(typeName, 1, Integer::sum);

			int size = c.getContainerSize();
			int used = 0;
			for (int i = 0; i < size; i++) {
				net.minecraft.world.item.ItemStack st = c.getItem(i);
				if (st.isEmpty()) continue;
				used++;
				items.merge(st.getItem(), st.getCount(), Integer::sum);
			}
			if (used > fullestUsed) {
				fullestUsed = used;
				fullestSize = size;
				fullest = p;
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("扫描范围: ").append(scope).append("  容器 ").append(containers).append(" 个");
		if (!byType.isEmpty()) {
			sb.append("\n类型: ");
			int n = 0;
			for (var e : byType.entrySet()) {
				if (n++ > 0) sb.append(" · ");
				sb.append(e.getKey()).append(" ").append(e.getValue());
			}
		}
		if (items.isEmpty()) {
			sb.append("\n内容物: （全空）");
		} else {
			sb.append("\n内容物: ");
			int n = 0;
			long total = 0;
			for (var e : items.entrySet()) total += e.getValue();
			for (var e : items.entrySet()) {
				if (n++ > 10) { sb.append(" · 等 ").append(items.size()).append(" 种"); break; }
				if (n > 1) sb.append(" · ");
				sb.append(ItemNames.displayName(e.getKey())).append(" ").append(e.getValue());
			}
			sb.append("\n合计 ").append(total).append(" 件");
		}
		// 只有确实存在非空容器时才报「最满的」。
		// 全空时 fullestUsed 是 0，若照常输出会得到「已用 0/27 格」——
		// 既没意义，又和上面的「内容物: （全空）」重复，还容易让人误以为里面还有东西。
		if (fullest != null && fullestUsed > 0) {
			sb.append("\n最满的容器: ").append(fmt(fullest))
					.append(" 已用 ").append(fullestUsed).append("/").append(fullestSize).append(" 格");
		}
		final String report = sb.toString();
		sendLines(src, report);
		return 1;
	}

	/** 单纯前往某坐标：近则走，远或跨维度则传送 */
	private static int gotoPos(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		BlockPos target = new BlockPos(
				IntegerArgumentType.getInteger(ctx, "x"),
				IntegerArgumentType.getInteger(ctx, "y"),
				IntegerArgumentType.getInteger(ctx, "z"));

		if (src.getServer().getPlayerList().getPlayerByName(name) == null) {
			src.sendFailure(Component.literal("找不到玩家或假人 " + name + "，请先执行 /cbot spawn " + name));
			return 0;
		}

		ResourceKey<Level> dim = src.getPlayer() != null
				? src.getPlayer().level().dimension() : Level.OVERWORLD;
		String dimStr = null;
		try {
			dimStr = StringArgumentType.getString(ctx, "dim");
		} catch (IllegalArgumentException ignored) {
			// 未指定维度
		}
		if (dimStr != null) {
			ResourceLocation id = ResourceLocation.tryParse(
					dimStr.indexOf(':') >= 0 ? dimStr : "minecraft:" + dimStr);
			if (id == null) {
				src.sendFailure(Component.literal("维度 ID 无效: " + dimStr));
				return 0;
			}
			dim = ResourceKey.create(Registries.DIMENSION, id);
		}

		// goto 与搬货任务互斥：先停掉正在跑的搬货任务
		LootBot lb = BotManager.get(name);
		if (lb != null && !requireAvailable(src, lb)) return 0;
		if (lb != null && lb.isRunning()) {
			lb.clearAll();
		}

		BotManager.startGoto(new GotoJob(name, target, dim));
		final ResourceKey<Level> d = dim;
		src.sendSuccess(() -> Component.literal(
				"已让 " + name + " 前往 " + fmt(target)
						+ (d != null ? "（" + d.location() + "）" : "")
						+ "；距离超过 64 格或跨维度会直接传送"), true);
		return 1;
	}

	/** 自检：把各环节跑一遍，确认模组在这个环境里是否正常 */
	private static int selfTest(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		ServerLevel level = player != null && player.level() instanceof ServerLevel l ? l : null;

		List<String> out = new ArrayList<>();
		out.add("===== ContainerBot 自检 =====");
		out.add("[✓] 模组已加载，命令可用");

		// 1) Carpet 动作包
		String carpet = com.containerbot.CarpetBridge.describeStatus();
		out.add((carpet.startsWith("可用") ? "[✓] " : "[!] ") + "Carpet 动作包: " + carpet);

		// 2) 物品名解析
		var r1 = ItemNames.resolve("minecraft:diamond");
		var r2 = ItemNames.resolve("diamond");
		out.add((r1.ok() && r2.ok() ? "[✓] " : "[!] ") + "物品名解析: ID 可用=" + r1.ok() + " 简写可用=" + r2.ok());
		var rcn = ItemNames.resolve("钻石");
		out.add((rcn.ok() ? "[✓] " : "[!] ") + "中文物品名: "
				+ (rcn.ok() ? "可用（" + rcn.item() + "）" : "不可用（服务端语言非中文）"));

		// 3) 寻路
		if (level != null) {
			BlockPos from = player.blockPosition();
			BlockPos to = from.offset(3, 0, 0);
			var path = PathPlanner.findPath(level, from, to, 4.0);
			out.add((path != null ? "[✓] " : "[!] ") + "A* 寻路: "
					+ (path != null ? "成功（" + path.size() + " 个路点）" : "失败（附近可能没有可站立点）"));

			// 4) 容器扫描
			int n = ContainerScanner.scanRadius(level, from, 16).size();
			out.add("[✓] 容器扫描: 半径 16 内找到 " + n + " 个");

			// 5) 维度传送可用性
			out.add("[✓] 跨维度传送: 依赖原版 /execute in <维度> run tp，可用");
		} else {
			out.add("[!] 控制台无法做位置相关自检（寻路/扫描），请由玩家执行");
		}

		out.add("其余环节（开箱、取货、卸货）需实际任务验证，可先用 /cbot scan 盘点");
		for (String line : out) {
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	// ------------------------------------------------------------------ 诊断

	private static int setDebug(CommandContext<CommandSourceStack> ctx, boolean on) {
		CommandSourceStack src = ctx.getSource();
		Diag.setEnabled(on);
		src.sendSuccess(() -> Component.literal("诊断输出已" + (on ? "开启" : "关闭")
				+ (on ? "（状态迁移会打到聊天栏与控制台）" : "")), true);
		return 1;
	}

	private static int debugStatus(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("诊断输出: " + (Diag.isEnabled() ? "开启" : "关闭")
				+ "；/cbot debug on|off 切换，/cbot debug dump 输出最近记录"), false);
		// Carpet 接入状态——升级 Carpet 后先看这一行
		src.sendSuccess(() -> Component.literal("Carpet 动作包: "
				+ com.containerbot.CarpetBridge.describeStatus()), false);
		return 1;
	}

	private static int debugDump(CommandContext<CommandSourceStack> ctx) {
		int n = Diag.dump(ctx.getSource().getServer(), ctx.getSource().getPlayer());
		return Math.max(1, n);
	}

	// ------------------------------------------------------------------ 卸货箱

	/**
	 * 把玩家正看着的方块指定为卸货箱。
	 *
	 * <p>用射线检测取视线命中的方块（与原版交互同一机制），并校验它确实是容器。
	 * 这样玩家不必手动输入坐标，对着箱子执行一条命令即可。</p>
	 */
	private static int setDeposit(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("该命令只能由玩家执行（需要看着一个箱子）"));
			return 0;
		}

		HitResult hit = player.pick(6.0, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			src.sendFailure(Component.literal("请对准一个箱子再执行（视线 6 格内）"));
			return 0;
		}

		BlockPos pos = blockHit.getBlockPos();
		if (!(player.level() instanceof ServerLevel level) || !(level.getBlockEntity(pos) instanceof Container)) {
			src.sendFailure(Component.literal("你看着的方块不是容器: " + fmt(pos)));
			return 0;
		}

		DEPOSITS.put(player.getUUID(), pos.immutable());
		DEPOSIT_DROP.put(player.getUUID(), Boolean.FALSE);   // 看着容器指定 → 容器模式
		// 记下维度，供跨维度搬家时直接传送
		if (player.level() instanceof ServerLevel lvl) {
			DEPOSIT_DIMS.put(player.getUUID(), lvl.dimension());
		}		src.sendSuccess(() -> Component.literal(
				"卸货箱已指定为 " + fmt(pos) + "（维度 " + player.level().dimension().location()
						+ "）；假人背包满时会把货物倒进这里"), true);
		return 1;
	}

	/**
	 * 把玩家正看着的方块设为「扔在地上」的投放点。
	 *
	 * <p>与 {@code /cbot deposit} 的区别：<b>不要求</b>该方块是容器。
	 * 假人走到这里后会把货直接丢出来，适合倾倒进漏斗、收集装置或临时堆放。</p>
	 */
	private static int setDepositDrop(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("该命令只能由玩家执行（需要看着一个位置）"));
			return 0;
		}

		// 看着方块就用它，没看着就用脚下
		BlockPos pos;
		HitResult hit = player.pick(6.0, 1.0f, false);
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			pos = blockHit.getBlockPos().above();   // 站在方块上方，避免埋进地里
		} else {
			pos = player.blockPosition();
		}

		UUID owner = key(src);
		DEPOSITS.put(owner, pos.immutable());
		DEPOSIT_DROP.put(owner, Boolean.TRUE);
		if (player.level() instanceof ServerLevel lvl) {
			DEPOSIT_DIMS.put(owner, lvl.dimension());
		}

		src.sendSuccess(() -> Component.literal(
				"投放点已设为 " + fmt(pos) + "（维度 " + player.level().dimension().location()
						+ "），模式：直接扔在地上"), true);
		return 1;
	}

	/** 切回「倒进容器」模式（位置不变，需要那里确实是容器） */
	private static int setDepositBox(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		UUID owner = key(src);
		DEPOSIT_DROP.put(owner, Boolean.FALSE);
		BlockPos pos = DEPOSITS.get(owner);

		if (pos == null) {
			src.sendSuccess(() -> Component.literal("已切换为【倒进容器】模式，但还没设过位置，"
					+ "请对着箱子执行 /cbot deposit"), false);
			return 1;
		}
		src.sendSuccess(() -> Component.literal("已切换为【倒进容器】模式，位置 " + fmt(pos)
				+ "（若该处不是容器，卸货时会报错）"), true);
		return 1;
	}

	/**
	 * 手输坐标指定卸货点 / 搬家目的地。
	 *
	 * <p>适合目的地很远、或需要跨维度的场合。维度可省略，默认玩家当前维度；
	 * 支持简写（{@code the_nether} 等价于 {@code minecraft:the_nether}）。</p>
	 */
	private static int setDepositAt(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();

		BlockPos pos = new BlockPos(
				IntegerArgumentType.getInteger(ctx, "x"),
				IntegerArgumentType.getInteger(ctx, "y"),
				IntegerArgumentType.getInteger(ctx, "z"));

		// 默认维度：玩家 → 其当前维度；控制台 → 主世界
		ServerPlayer player = src.getPlayer();
		ResourceKey<Level> dim = player != null ? player.level().dimension() : Level.OVERWORLD;
		String dimStr = null;
		try {
			dimStr = StringArgumentType.getString(ctx, "dim");
		} catch (IllegalArgumentException ignored) {
			// 未提供维度参数
		}
		if (dimStr != null) {
			ResourceLocation dimId = ResourceLocation.tryParse(
					dimStr.indexOf(':') >= 0 ? dimStr : "minecraft:" + dimStr);
			if (dimId == null) {
				src.sendFailure(Component.literal("维度 ID 无效: " + dimStr));
				return 0;
			}
			dim = ResourceKey.create(Registries.DIMENSION, dimId);
		}

		DEPOSITS.put(key(src), pos.immutable());
		DEPOSIT_DIMS.put(key(src), dim);

		// 自动判定放置方式：那个位置是容器就倒进去，否则直接扔在地上。
		// 这样既能省去记「drop / box」开关，也让控制台可以设置投放点
		// （控制台没有视线方向，用不了 /cbot deposit drop）。
		boolean isContainer = false;
		try {
			ServerLevel lvl = src.getServer().getLevel(dim);
			if (lvl != null && lvl.isLoaded(pos)) {
				isContainer = lvl.getBlockEntity(pos) instanceof Container;
			}
		} catch (Throwable ignored) {
			// 区块未加载等情况，按非容器处理
		}
		DEPOSIT_DROP.put(key(src), !isContainer);

		final ResourceKey<Level> dd = dim;
		final boolean drop = !isContainer;
		src.sendSuccess(() -> Component.literal(
				"卸货点已设为 " + fmt(pos) + "（维度 " + dd.location() + "），模式："
						+ (drop ? "直接扔在地上" : "倒入容器")), true);
		return 1;
	}

	private static int clearDeposit(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("该命令只能由玩家执行"));
			return 0;
		}
		DEPOSITS.remove(player.getUUID());
		src.sendSuccess(() -> Component.literal("已取消卸货箱指定（改为自动找附近容器）"), false);
		return 1;
	}

	// ------------------------------------------------------------------ 子命令

	private static int spawn(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		MinecraftServer server = src.getServer();

		if (server.getPlayerList().getPlayerByName(name) != null) {
			src.sendFailure(Component.literal("已存在同名玩家或假人: " + name));
			return 0;
		}

		// 关键设计：借 Carpet 的 /player 命令生成假人，
		// 从而完全不依赖 Carpet 内部类，Carpet 版本变动不会波及本模组。
		// 命令源沿用执行者自身（位置/维度/权限都正确，假人将生成在执行者所在处）。
		server.getCommands().performPrefixedCommand(src, "player " + name + " spawn");

		// 生成假人不做可用性检查——它本来就是"把这个假人准备好"的动作。
		// 新记录默认被标记为「有任务」，需要 /cbot free <名字> 才能接任务。
		LootBot bot = BotManager.getOrCreate(name);
		bot.markWaitingSpawn();

		src.sendSuccess(() -> Component.literal("已请求生成假人 " + name + "，请稍候"), true);
		return 1;
	}

	/**
	 * 生成假人并<b>直接标记为空闲</b>。
	 *
	 * <p>纯粹是 {@code spawn + free} 的合并，省一次敲命令。
	 * 严格说它放宽了「默认有任务」这条安全策略——但因为是显式命令，
	 * 意图明确，不存在"误把别人的假人当成空闲"的风险。</p>
	 */
	private static int spawnFree(CommandContext<CommandSourceStack> ctx) {
		int r = spawn(ctx);
		if (r == 0) return 0;

		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot != null) {
			bot.setHasTaskFlag(false);
		}
		ctx.getSource().sendSuccess(() -> Component.literal(
				"并把 " + name + " 标记为【空闲】，可直接下任务"), false);
		return 1;
	}

	/**
	 * @param radius           显式半径；传 0 表示「未指定」，此时优先使用玩家选区
	 * @param shulkerThreshold 潜影盒策略启用阈值；0 表示禁用该策略
	 */
	private static int start(CommandContext<CommandSourceStack> ctx, int radius, int shulkerThreshold) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		// 物品名有两个来源：
		//   · /cbot start ... <item>   → ResourceLocationArgument（英文 ID）
		//   · /cbot get   ... <items>  → greedyString（可写中文，物品放最后）
		// 同一个处理函数按参数名探测，避免两套逻辑。
		String itemStr = null;
		try {
			itemStr = ResourceLocationArgument.getId(ctx, "item").toString();
		} catch (IllegalArgumentException noSuchArg) {
			try {
				itemStr = StringArgumentType.getString(ctx, "items");
			} catch (IllegalArgumentException ignored) {
				// 两个都没有
			}
		}
		if (itemStr == null) {
			src.sendFailure(Component.literal("缺少物品名"));
			return 0;
		}
		int count = IntegerArgumentType.getInteger(ctx, "count");

		Item item = resolveItem(itemStr);
		if (item == null) {
			src.sendFailure(Component.literal("未知物品: " + itemStr + "（示例: minecraft:diamond）"));
			return 0;
		}

		// 只要玩家/假人确实在线，就允许自动补建任务记录。
		// 这样即使用户绕过 /cbot spawn、直接用 Carpet 的 /player 生成了假人，
		// 也能立刻下任务，不会卡在「没有该假人的记录」。
		var existing = src.getServer().getPlayerList().getPlayerByName(name);
		if (existing == null) {
			src.sendFailure(Component.literal("找不到玩家或假人 " + name + "，请先执行 /cbot spawn " + name));
			return 0;
		}
		LootBot bot = BotManager.getOrCreate(name);
		// 安全策略：只有标记为空闲的假人才能接新任务
		if (!requireAvailable(src, bot)) return 0;

		// 未显式给半径时，若设过选区则用选区
		BlockPos boxA = null;
		BlockPos boxB = null;
		BlockPos deposit = null;
		ResourceKey<Level> depositDim = null;
		ResourceKey<Level> boxDim = null;
		boolean dropMode = false;
		ServerPlayer player = src.getPlayer();
		if (radius <= 0) {
			// 选区按「命令来源」取，控制台也能用坐标形式设的选区
			UUID selOwner = key(src);
			BlockPos[] sel = SELECTIONS.get(selOwner);
			if (sel != null && sel[0] != null && sel[1] != null && validateBox(sel[0], sel[1]) == null) {
				boxA = sel[0];
				boxB = sel[1];
				boxDim = SELECTION_DIMS.get(selOwner);
			}
		}
		// 卸货点按「命令来源」取，控制台也能用
		deposit = DEPOSITS.get(key(src));
		depositDim = DEPOSIT_DIMS.get(key(src));
		dropMode = Boolean.TRUE.equals(DEPOSIT_DROP.get(key(src)));

		bot.submit(src.getServer(), LootTask.loot(item, count, radius > 0 ? radius : 32,
				boxA, boxB, boxDim, Math.max(0, shulkerThreshold), deposit, depositDim, dropMode,
				PACES.getOrDefault(key(src), 0), RATES.getOrDefault(key(src), 0),
				List.copyOf(bot.getFilterWhite())));

		final String scope = (boxA != null)
				? "指定区域 " + describeBox(boxA, boxB)
				: "半径 " + (radius > 0 ? radius : 32);
		final BlockPos dep = deposit;
		final boolean multi = bot.getQueueSize() > 0;
		final String itemLabel = itemStr;   // lambda 需要 effectively final
		src.sendSuccess(() -> Component.literal(
				(multi ? "已排队（前面还有任务）: " : "已启动: ")
						+ name + " 收集 " + count + " 个 " + itemLabel + "，范围 " + scope
						+ (shulkerThreshold > 0 && count >= shulkerThreshold ? "，启用潜影盒整盒搬运" : "")
						+ (dep != null ? "，卸货箱 " + fmt(dep) : "")), true);
		return 1;
	}

	/**
	 * 搬家模式：掏空选定区域（或半径）内所有容器，全部倒进指定目的地。
	 *
	 * <p>与取货模式的区别：
	 * <ul>
	 *   <li>不筛选物品——容器里的一切都搬走，潜影盒整盒搬</li>
	 *   <li>没有数量上限——直到所有容器都掏空为止</li>
	 *   <li>必须有目的地（{@code /cbot deposit} 指定），否则货物没处放</li>
	 *   <li>目的地可跨维度，假人会直接传送过去</li>
	 * </ul>
	 */
	private static int startMove(CommandContext<CommandSourceStack> ctx, int radius) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");

		var existing = src.getServer().getPlayerList().getPlayerByName(name);
		if (existing == null) {
			src.sendFailure(Component.literal("找不到玩家或假人 " + name + "，请先执行 /cbot spawn " + name));
			return 0;
		}

		ServerPlayer player = src.getPlayer();
		UUID owner = key(src);

		// 目的地必须有，否则搬出来的东西没处放
		BlockPos deposit = DEPOSITS.get(owner);
		if (deposit == null) {
			src.sendFailure(Component.literal(
					"还没指定搬家目的地，请先 /cbot deposit（看着箱子）或 /cbot deposit <x> <y> <z> [维度]"));
			return 0;
		}
		ResourceKey<Level> depositDim = DEPOSIT_DIMS.get(owner);
		boolean dropMode = Boolean.TRUE.equals(DEPOSIT_DROP.get(owner));

		// 未给半径时优先用选区（选区按「命令来源」取，控制台也可用）
		BlockPos boxA = null;
		BlockPos boxB = null;
		ResourceKey<Level> boxDim = null;
		if (radius <= 0) {
			BlockPos[] sel = SELECTIONS.get(owner);
			if (sel != null && sel[0] != null && sel[1] != null && validateBox(sel[0], sel[1]) == null) {
				boxA = sel[0];
				boxB = sel[1];
				boxDim = SELECTION_DIMS.get(owner);
			}
		}
		if (boxA == null && radius <= 0) {
			src.sendFailure(Component.literal(
					"搬家模式需要先框定区域（/cbot pos1 + /cbot pos2），或显式给半径，例如 /cbot move "
							+ name + " 48"));
			return 0;
		}

		LootBot bot = BotManager.getOrCreate(name);
		// 安全策略：只有标记为空闲的假人才能接新任务
		if (!requireAvailable(src, bot)) return 0;
		final LootTask t = LootTask.move(radius > 0 ? radius : 32, boxA, boxB, boxDim,
				deposit, depositDim, dropMode,
				PACES.getOrDefault(owner, 0), RATES.getOrDefault(owner, 0),
				List.copyOf(bot.getFilterWhite()));
		bot.submit(src.getServer(), t);

		final BlockPos dp = deposit;
		final ResourceKey<Level> d = depositDim;
		src.sendSuccess(() -> Component.literal(
				"已启动搬家: " + name + " 掏空 " + t.scopeText() + " 的所有容器 → " + fmt(dp)
						+ (d != null ? "（维度 " + d.location() + "）" : "")
						+ (dropMode ? "，直接扔在地上" : "，倒入容器")), true);
		return 1;
	}

	/** 清空所有假人的任务 */
	private static int clearAll(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		int total = 0;
		for (LootBot bot : BotManager.all()) {
			total += bot.clearAll();
		}
		final int n = total;
		src.sendSuccess(() -> Component.literal("已清空所有假人的任务（共 " + n + " 个）"), true);
		return Math.max(1, n);
	}

	/** 清空指定假人的任务 */
	private static int clearOne(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);
		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		int n = bot.clearAll();
		src.sendSuccess(() -> Component.literal("已清空 " + name + " 的任务（共 " + n + " 个）"), true);
		return Math.max(1, n);
	}

	private static int stop(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		LootBot bot = BotManager.get(name);

		if (bot == null) {
			src.sendFailure(Component.literal("没有该假人的记录: " + name));
			return 0;
		}
		bot.fail("已被手动停止");
		src.sendSuccess(() -> Component.literal("已停止 " + name), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var bots = BotManager.all();

		if (bots.isEmpty()) {
			src.sendSuccess(() -> Component.literal("当前没有任何假人任务"), false);
			return 0;
		}
		for (LootBot bot : bots) {
			String line = bot.describe();
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return bots.size();
	}

	private static int despawn(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");

		LootBot bot = BotManager.get(name);
		if (bot != null) bot.fail("假人已被移除");

		src.getServer().getCommands().performPrefixedCommand(src, "player " + name + " kill");
		BotManager.remove(name);

		src.sendSuccess(() -> Component.literal("已移除假人 " + name), true);
		return 1;
	}

	// ------------------------------------------------------------------ 工具

	/**
	 * 解析物品名：同时支持英文 ID 与中文名。
	 *
	 * <p>实际逻辑委托给 {@link ItemNames}——它先试资源 ID（含省略命名空间的写法），
	 * 再按本地化名反查，因此 {@code minecraft:diamond}、{@code diamond}、{@code 钻石}
	 * 三种写法都能用。</p>
	 */
	private static Item resolveItem(String raw) {
		return ItemNames.resolve(raw).item();
	}

	private static String fmt(BlockPos p) {
		return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
	}
}
