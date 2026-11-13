package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个假人的取货任务状态机，支持任务队列。
 *
 * <pre>
 *  WAITING_SPAWN → SCAN → PATH → WALK → OPEN → LOOT ─┐
 *                   ↑                                │
 *                   └────────────────────────────────┘  （未达数量则找下一个容器）
 *                                                       ↓
 *                                        UNLOAD（背包满时卸货）
 *                                                       ↓
 *                                       下一个任务 / DONE / FAILED
 * </pre>
 *
 * <h3>主要特性</h3>
 * <ul>
 *   <li><b>任务队列</b>：一个假人可排队执行多个任务，做完一个自动接下一个</li>
 *   <li><b>潜影盒策略</b>：目标数量达到阈值时，容器里装着目标物品的潜影盒整盒搬走，
 *       但按盒内<b>实际数量</b>计入进度</li>
 *   <li><b>自动卸货</b>：背包满时自动把货物倒进附近容器，然后继续搬运</li>
 *   <li><b>进度提示</b>：开始 / 完成 / 失败时发聊天栏消息，并按百分比节流报告进度</li>
 * </ul>
 */
public class LootBot {

	public enum State {
		WAITING_SPAWN("等待假人上线"),
		SCAN("扫描容器"),
		PATH("规划路径"),
		WALK("行走中"),
		OPEN("开启容器"),
		LOOT("提取物品"),
		UNLOAD("卸货中"),
		DONE("已完成"),
		FAILED("已失败");

		private final String label;

		State(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** 打开容器后的等待上限 */
	private static final int OPEN_TIMEOUT_TICKS = 40;
	/** 生成假人的等待上限（Carpet 生成是异步的） */
	private static final int SPAWN_TIMEOUT_TICKS = 20 * 15;
	/** 连续寻路失败多少次就放弃 */
	private static final int MAX_PATH_FAILURES = 5;
	/** 进度提示的节流间隔：每到 25% 报一次 */
	private static final int PROGRESS_STEP_PERCENT = 25;

	private final String name;
	private final Deque<String> log = new ArrayDeque<>();
	/** 待执行的任务队列（先进先出） */
	private final Deque<LootTask> queue = new ArrayDeque<>();

	private State state = State.SCAN;
	private String status = "空闲";

	/** 当前任务 */
	private LootTask task;
	/** 上一次报到聊天栏的进度档位，用于节流 */
	private int lastReportedStep = -1;

	private final Set<BlockPos> visited = new HashSet<>();
	private BlockPos container;
	private Container openContainer;
	private Navigator navigator;

	private int stateTicks;
	private int spawnWaitTicks;
	private int pathFailures;
	private int containersSearched;
	private int collectedTotal;
	/** 当前任务累计取得的数量（含盒内实际数量） */
	private int collectedForTask;

	/**
	 * 任务开始时背包里已有的目标物品数量，**不计入本任务进度**。
	 *
	 * <p>初值 -1 表示尚未取样——首次调用 {@code countForTask} 时取基线，
	 * 那时还没开始取货，所以取到的就是"原本就有多少"。</p>
	 */
	private int baselineForTask = -1;
	/**
	 * 已经卸货出去的件数。
	 *
	 * <p>关键：进度必须按「已交付 + 身上携带」来算。若只看背包里当前有多少，
	 * 一卸货进度就会倒退，任务会陷入「取货→卸货→取货」的死循环。</p>
	 */
	private int deliveredCount;
	/** 已卸货轮次，用于防止卸货死循环 */
	private int unloadRounds;
	/**
	 * 本次开箱是为了卸货（而非取货）。
	 *
	 * <p>血泪教训：{@code tickOpen} 若无条件把状态置为 LOOT，会覆盖掉
	 * 「走向卸货箱」的意图——假人打开卸货箱后在里面找目标物品、找不到、
	 * 回去继续搬、背包又满、又去卸货箱……形成死循环，表现为「卡住不动」。</p>
	 */
	private boolean openingForUnload;
	/**
	 * 是否正在做「收尾卸货」。
	 *
	 * <p>血泪教训：搬家模式原先只在<b>背包满</b>时才去目的地。若区域里的东西
	 * 不多（没装满背包），任务会直接判定「所有容器已掏空」而完成——
	 * 货物却还背在假人身上，目的地是空的。所以掏空后必须再跑一趟把尾货送过去。</p>
	 */
	private boolean flushing;

	/** 待发出的失败通知。fail() 只挂起，由 tick() 统一广播 */
	private String pendingFailureNotice;
	/** 正在走向「扔地上」的投放点：tickWalk 结束后应直接进 UNLOAD 而不是 OPEN */
	private boolean dropModeWalking;
	/** 已经朝投放点走过一次了；再一次仍不到位就改传送 */
	private boolean dropWalked;
	/**
	 * 传送后的冷却 tick 数。
	 *
	 * <p>跨维度传送是<b>瞬间跳变</b>位置，服务端玩家位置与交互判定还没同步，
	 * 紧接着同一 tick 发右键会被否决。实测跨维度投送就是栽在这里——
	 * 假人明明站在箱子旁边，却连续 7 次开箱失败。等几 tick 让位置落定即可。</p>
	 */
	private int teleportCooldown;
	/**
	 * 节流等待剩余 tick。
	 *
	 * <p>每处理完一个容器后按 {@code task.intervalTicks()} 暂停一次，
	 * 降低假人的动作频率。等待期间整个状态机冻结（连 stateTicks 也不增加），
	 * 避免被开箱/行走超时误判为失败。</p>
	 */
	private int waitTicks;
	/** 当前等待的原因，仅用于状态显示（节流 / 限速） */
	private String waitReason = "节流等待";

	/**
	 * 是否已暂停。
	 *
	 * <p>暂停期间状态机完全冻结，且<b>不增加 stateTicks</b>——否则暂停久了会被
	 * 开箱/行走的超时判定误认为失败。与节流等待同一套思路，只是无限期。</p>
	 */
	private volatile boolean paused;

	/**
	 * 循环任务：跑完一轮后等 {@link #repeatIntervalTicks}，再自动重跑同一个任务。
	 *
	 * <p>语义约定（与用户确认过）：</p>
	 * <ul>
	 *   <li><b>间隔从上一轮结束时开始计时</b>——保证「两轮之间至少隔 N 秒」，
	 *       即使某轮跑得比间隔还久也不会堆积</li>
	 *   <li><b>失败也继续循环</b>——「范围内容器已搜遍」属于正常状态（没货可搬），
	 *       不该让循环停掉</li>
	 *   <li><b>循环保留</b>——假人被移除后循环设置还在，重新 spawn 能接上</li>
	 * </ul>
	 */
	private LootTask repeatTask;
	private int repeatIntervalTicks;
	private int repeatWaitTicks;
	private boolean repeatEnabled;

	/** 最近一次完成的任务，供「跑完一次再开启循环」使用 */
	private LootTask lastTask;

	// ---------------- 可用性标记（安全策略） ----------------

	/**
	 * 是否被标记为「已有任务」。
	 *
	 * <p><b>默认 true</b>，这是安全策略：绝不假设假人空闲。原因很实际——
	 * 假人可能正在做我们管不着的事（Carpet 的钓鱼、手动指令、别的模组），
	 * 贸然给它派搬货任务会打断甚至搞乱它正在做的事。</p>
	 *
	 * <p>生命周期：</p>
	 * <ul>
	 *   <li>新建记录 → <b>有任务</b></li>
	 *   <li>手动 {@code /cbot free <名字>} → 空闲，可以接任务</li>
	 *   <li>手动 {@code /cbot busy <名字>} → 收回</li>
	 *   <li><b>假人下线 → 自动重新标记为有任务</b>（下次上线不会凭空被当成空闲）</li>
	 * </ul>
	 */
	private volatile boolean hasTaskFlag = true;

	/** 是否曾经在线过——用于判断「下线」这个事件 */
	private boolean seenOnline;

	public boolean hasTaskFlag() {
		return hasTaskFlag;
	}

	public void setHasTaskFlag(boolean v) {
		hasTaskFlag = v;
	}

	/** 可用 = 没有被标记为有任务，且当前没有在跑任务 */
	public boolean isAvailable() {
		return !hasTaskFlag && task == null;
	}

	/** 不可用的原因（用于给用户明确提示） */
	public String unavailableReason() {
		if (hasTaskFlag) {
			return "已被标记为「有任务」，用 /cbot free " + name + " 解除";
		}
		if (task != null) {
			return "当前正在执行任务（" + state.label() + "），用 /cbot stop " + name + " 停掉";
		}
		return null;
	}

	// ---------------- 统计（/cbot stats） ----------------

	/** 本次任务各物品的搬运件数 */
	private final java.util.Map<net.minecraft.world.item.Item, Integer> itemStats =
			new java.util.LinkedHashMap<>();
	/** 本次任务开始的时间戳（毫秒） */
	private long taskStartMs;
	/** 本次任务的简短描述 */
	private String lastTaskDesc = "（尚未执行任务）";

	// ---------------- 自动进食 ----------------

	/** 饥饿值低于此值就吃东西（20 = 满，18 = 掉了两格） */
	private static final int EAT_THRESHOLD = 18;
	/** 是否开启自动进食（默认开启） */
	private volatile boolean eatEnabled = true;

	/**
	 * 是否开启「维持饱食度」（默认<b>开启</b>）。
	 *
	 * <p>直接操作 {@code FoodData}，把饥饿值补满。与 {@code eat} 的分工：</p>
	 * <ul>
	 *   <li>{@code eat} = <b>默认行为</b>（默认开启），让假人像真人一样吃东西</li>
	 *   <li>{@code feed} = <b>可选兜底</b>（默认关闭），直接维持饱食度，
	 *       相当于"作弊式保命"——想开就自己开</li>
	 * </ul>
	 *
	 * <p>副作用是好的：饥饿值 ≥ 18 会触发自然回血，假人残血也能慢慢恢复。</p>
	 */
	private volatile boolean feedEnabled;

	/**
	 * 精确数量模式（默认关闭）。
	 *
	 * <p>取货时 {@code QUICK_MOVE} 是<b>整组搬</b>，要 32 个但箱里是 64 一组就会拿 64。
	 * 开启本模式后，<b>超过剩余需求的那一组不会被搬</b>——保证<b>绝不超过</b>目标数量。</p>
	 *
	 * <p>代价：如果箱里只有"一组 64"而你要 32 个，它会一个都不拿（宁可少拿也不超额）。
	 * 任务结束时仍会如实报告「只找到 X/Y，还差 Z」。</p>
	 */
	private volatile boolean exactEnabled;

	public boolean isExactEnabled() {
		return exactEnabled;
	}

	public void setExactEnabled(boolean v) {
		this.exactEnabled = v;
	}

	/** 饥饿值低于此值就补满 */
	private static final int FEED_THRESHOLD = 18;
	/** 补满后的饥饿值 */
	private static final int FEED_TARGET = 20;
	/** 补满后的饱和度 */
	private static final float FEED_SATURATION = 5.0f;

	public boolean isFeedEnabled() {
		return feedEnabled;
	}

	public void setFeedEnabled(boolean v) {
		feedEnabled = v;
	}

	/**
	 * 测试钩子：把饥饿值人为打到指定值。
	 *
	 * <p>用途是<b>验证 feed 的实际效果</b>——正常途径造不出饥饿状态：
	 * 玩家数据不能用 {@code /data merge} 改、创造模式不掉饥饿、
	 * 新假人的 saturation 有 20 点厚缓冲。所以只能直接改 {@code FoodData}
	 * 把假人"弄饿"，再看 feed 会不会自动补回来。</p>
	 *
	 * @return 设置前的饥饿值；假人不在线返回 -1
	 */
	public int forceHunger(ServerPlayer bot, int food, float saturation) {
		if (bot == null || bot.isRemoved()) return -1;
		var fd = bot.getFoodData();
		int before = fd.getFoodLevel();
		fd.setFoodLevel(food);
		fd.setSaturation(saturation);
		Diag.trace(null, name, "测试钩子：把饥饿值设为 " + food + "（原 " + before + "）");
		return before;
	}

	/**
	 * 维持饱食度。
	 *
	 * <p>用阈值触发（低于 18 才补），不做每 tick 的 set——因为同值会标脏并触发同步包，
	 * 那样每 tick 都在发包，白白浪费带宽。</p>
	 */
	private void maybeFeed(ServerPlayer bot) {
		if (!feedEnabled) return;
		var fd = bot.getFoodData();
		int level = fd.getFoodLevel();
		if (level >= FEED_THRESHOLD) return;

		fd.setFoodLevel(FEED_TARGET);
		fd.setSaturation(FEED_SATURATION);
		Diag.trace(null, name, "维持饱食度: " + level + " -> " + FEED_TARGET);
	}

	public boolean isEatEnabled() {
		return eatEnabled;
	}

	public void setEatEnabled(boolean v) {
		eatEnabled = v;
	}

	/**
	 * 饿了就吃东西。
	 *
	 * <p>走原版「手持食物 + 使用」的路径：把食物切到主手，再调
	 * {@code ServerPlayerGameMode.useItem}。之后原版会自己推进进食进度、
	 * 消耗物品并恢复饥饿值——与真人吃东西完全同一条流程。</p>
	 *
	 * <p>注意假人进食需要站着不动约 1.6 秒，行走途中可能吃不完整，
	 * 下一 tick 会重新开始。</p>
	 */
	private void maybeEat(ServerPlayer bot) {
		if (!eatEnabled) return;
		eatOnce(bot, false);
	}

	/**
	 * 吃一次东西。
	 *
	 * @param force true 表示无视饥饿值与开关，强制吃（供 /cbot eat <名字> test 验证用）
	 */
	public void eatOnce(ServerPlayer bot, boolean force) {
		var food = bot.getFoodData();
		if (!force && food.getFoodLevel() >= EAT_THRESHOLD) return;
		// 已经在吃了，等它完成
		if (bot.isUsingItem()) return;

		var inv = bot.getInventory();
		int selected = inv.getSelectedSlot();

		// 1) 快捷栏 + 主背包（0..35）——切到主手吃
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			// 1.21 用数据组件判断是不是食物（没有 isEdible()）
			if (st.isEmpty() || !st.has(net.minecraft.core.component.DataComponents.FOOD)) continue;

			if (i < 9) {
				// 在快捷栏：直接切到那一格
				inv.setSelectedSlot(i);
			} else {
				// 在背包里：和当前选中的快捷栏槽位交换
				ItemStack cur = inv.getItem(selected);
				inv.setItem(i, cur);
				inv.setItem(selected, st);
			}

			ItemStack held = bot.getMainHandItem();
			bot.gameMode.useItem(bot, (ServerLevel) bot.level(), held, InteractionHand.MAIN_HAND);
			Diag.trace(null, name, "自动进食: " + ItemNames.displayName(held.getItem())
					+ " 饥饿值=" + food.getFoodLevel() + (force ? "（强制）" : ""));
			return;
		}

		// 2) 副手（slot 40）——原版不会自动从副手吃，必须显式指定那只手。
		//    主手和背包都没有食物时才回退到这里。
		ItemStack off = bot.getOffhandItem();
		if (!off.isEmpty() && off.has(net.minecraft.core.component.DataComponents.FOOD)) {
			bot.gameMode.useItem(bot, (ServerLevel) bot.level(), off, InteractionHand.OFF_HAND);
			Diag.trace(null, name, "自动进食（副手）: " + ItemNames.displayName(off.getItem())
					+ " 饥饿值=" + food.getFoodLevel());
			return;
		}

		// 没有食物可吃：不报错，只记录（避免刷屏）
		Diag.trace(null, name, "饿了但没有食物（饥饿值=" + food.getFoodLevel() + "）");
	}
	/** 无法打开/无法到达而跳过的容器数 */
	private int skippedContainers;

	private void resetStats(LootTask t) {
		itemStats.clear();
		skippedContainers = 0;
		taskStartMs = System.currentTimeMillis();
		lastTaskDesc = t.moveAll()
				? "搬家 · " + t.scopeText()
				: "取货 " + t.count() + " 个 " + t.itemName() + " · " + t.scopeText();
	}

	/** 生成统计报告（多行，调用方按 \n 拆分） */
	public String describeStats() {
		StringBuilder sb = new StringBuilder();
		sb.append("任务: ").append(lastTaskDesc);

		long ms = taskStartMs > 0 ? System.currentTimeMillis() - taskStartMs : 0;
		if (ms > 0) {
			long sec = ms / 1000;
			sb.append("  耗时 ").append(sec / 60).append(" 分 ").append(sec % 60).append(" 秒");
		}

		sb.append("\n已搬 ").append(totalMoved).append(" 件");
		if (ms >= 1000) {
			double perMin = totalMoved * 60000.0 / ms;
			sb.append("（约 ").append(String.format("%.0f", perMin)).append(" 件/分钟）");
		}
		sb.append("  已搜容器 ").append(containersSearched).append(" 个");
		if (skippedContainers > 0) {
			sb.append("，跳过 ").append(skippedContainers).append(" 个");
		}

		if (!itemStats.isEmpty()) {
			sb.append("\n明细: ");
			int n = 0;
			for (var e : itemStats.entrySet()) {
				if (n++ > 0) sb.append(" · ");
				if (n > 8) { sb.append("等 ").append(itemStats.size()).append(" 种"); break; }
				sb.append(ItemNames.displayName(e.getKey())).append(" ").append(e.getValue());
			}
		}
		return sb.toString();
	}

	/**
	 * 搬家时的物品过滤。
	 *
	 * <p>白名单非空 → 只搬白名单里的；黑名单 → 除了黑名单里的都搬。
	 * 两者可同时设置：先过黑名单，再过白名单（交集语义）。</p>
	 *
	 * <p>只影响<b>搬家模式</b>（{@code moveAll}）。取货模式本来就只搬指定物品，
	 * 不需要过滤。</p>
	 */
	private final Set<net.minecraft.world.item.Item> filterWhite = new LinkedHashSet<>();
	private final Set<net.minecraft.world.item.Item> filterBlack = new LinkedHashSet<>();

	public Set<net.minecraft.world.item.Item> getFilterWhite() {
		return filterWhite;
	}

	public Set<net.minecraft.world.item.Item> getFilterBlack() {
		return filterBlack;
	}

	public boolean hasFilter() {
		return !filterWhite.isEmpty() || !filterBlack.isEmpty();
	}

	public void clearFilter() {
		filterWhite.clear();
		filterBlack.clear();
	}

	/** 该物品是否允许被搬走 */
	private boolean allowedByFilter(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		// 潜影盒按"盒子本身"判断：黑名单里是潜影盒就跳过，白名单里有潜影盒才搬
		var item = stack.getItem();
		if (filterBlack.contains(item)) return false;
		// 任务自带白名单（分流）优先——每个排队任务可以有自己的目标物品集合
		if (task != null && task.hasWhiteList()) return task.whiteList().contains(item);
		if (!filterWhite.isEmpty()) return filterWhite.contains(item);
		return true;
	}

	public String describeFilter() {
		if (!hasFilter()) return "无过滤";
		StringBuilder sb = new StringBuilder();
		if (!filterWhite.isEmpty()) sb.append("白名单 ").append(joinNames(filterWhite));
		if (!filterBlack.isEmpty()) {
			if (sb.length() > 0) sb.append("；");
			sb.append("黑名单 ").append(joinNames(filterBlack));
		}
		return sb.toString();
	}

	/** 集合内容的简短描述（诊断用），用注册 ID 而非本地化名，避免看不出差别 */
	private static String describeSet(Set<net.minecraft.world.item.Item> items) {
		StringBuilder sb = new StringBuilder();
		for (var it : items) {
			if (sb.length() > 0) sb.append(",");
			sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it));
		}
		return sb.toString();
	}

	private static String joinNames(Set<net.minecraft.world.item.Item> items) {		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (var it : items) {
			if (n++ > 0) sb.append("、");
			if (n > 8) { sb.append("等").append(items.size()).append("种"); break; }
			sb.append(ItemNames.displayName(it));
		}
		return sb.toString();
	}

	public boolean isRepeating() {
		return repeatEnabled && repeatTask != null;
	}

	public int getRepeatIntervalTicks() {
		return repeatIntervalTicks;
	}

	public int getRepeatWaitTicks() {
		return repeatWaitTicks;
	}

	/**
	 * 开启循环。优先用当前正在跑的任务，否则用最近跑完的那次。
	 *
	 * @return true 表示成功；false 表示还没有可循环的任务
	 */
	public boolean enableRepeat(int intervalTicks) {
		LootTask target = task != null ? task : lastTask;
		if (target == null) return false;
		this.repeatTask = target;
		this.repeatIntervalTicks = Math.max(20, intervalTicks);
		this.repeatEnabled = true;
		this.repeatWaitTicks = 0;
		// 空闲时开启：下一 tick 会立刻开一轮（见 tick 里的「立即开始新一轮」分支）
		this.status = task != null
				? "循环已开启（本轮结束后生效）"
				: "循环已开启，马上开始第一轮";
		return true;
	}

	/** 停止循环（不影响正在跑的这一轮） */
	public void disableRepeat() {
		boolean wasRepeating = isRepeating();
		repeatEnabled = false;
		repeatTask = null;
		repeatWaitTicks = 0;
		// 若此刻正停在「等下一轮」的状态，文案要一并收尾，
		// 否则会留下「循环任务：本轮结束，等 N 秒后重跑」这种过时提示。
		if (wasRepeating && task == null) {
			this.state = State.DONE;
			this.status = "循环已停止";
		}
	}

	public String describeRepeat() {
		if (!isRepeating()) return "未开启循环";
		int sec = repeatIntervalTicks / 20;
		String waiting = repeatWaitTicks > 0 ? "，下一轮还有 " + (repeatWaitTicks / 20 + 1) + " 秒" : "";
		return "每 " + sec + " 秒重跑一次（" + repeatTask.scopeText() + waiting + "）";
	}

	/** 任务开始至今经过的 tick 数（限速用，只在实际推进时累加） */
	private int taskTicks;
	/** 任务开始至今累计搬进假人的件数（限速用，只增不减） */
	private int totalMoved;

	/**
	 * 按「物品/小时」限速。
	 *
	 * <p>与 {@code intervalTicks}（节流）的区别：节流限制的是<b>动作频率</b>
	 * （每个容器之间固定停顿），而这里限制的是<b>实际搬运量</b>——
	 * 不管一个容器里装了多少东西，长期平均速率都不会超过设定值。</p>
	 *
	 * <p>做法是简单的预算式限速：按已过时间算出"此刻最多允许搬多少件"，
	 * 超了就等到允许为止。</p>
	 *
	 * @return true 表示已进入限速等待，调用方应立即返回
	 */
	private boolean rateLimit() {
		int rate = task.itemsPerHour();
		if (rate <= 0) return false;

		double allowed = (double) rate * taskTicks / LootTask.TICKS_PER_HOUR;

		// 诊断先行：把内部数值打出来，避免再次盲改
		Diag.trace(null, name, "限速检查 目标=" + rate + "件/h 已搬=" + totalMoved
				+ " 已用tick=" + taskTicks + " 允许=" + String.format("%.1f", allowed)
				+ " 超速=" + (totalMoved > allowed));

		if (totalMoved <= allowed) return false;

		long needTicks = (long) Math.ceil(totalMoved * (double) LootTask.TICKS_PER_HOUR / rate);
		long wait = needTicks - taskTicks;
		if (wait <= 0) return false;

		waitTicks = (int) Math.max(waitTicks, Math.min(20L * 60, wait));
		waitReason = "限速等待";
		status = "限速等待（目标 " + rate + " 件/小时，已搬 " + totalMoved + " 件）";
		return true;
	}

	public boolean isPaused() {
		return paused;
	}

	/** 暂停：立即停止移动输入，保留当前进度 */
	public void pause() {
		paused = true;
	}

	/** 继续：从暂停处恢复，不重头开始 */
	public void resume() {
		paused = false;
	}

	/** 开始一段节流等待（interval 为 0 时不做任何事） */
	private void pace() {
		if (task != null && task.intervalTicks() > 0) {
			waitTicks = task.intervalTicks();
			waitReason = "节流等待";
		}
	}

	// ------------------------------------------------------------------ 对外接口

	public LootBot(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public State getState() {
		return state;
	}

	public String getStatus() {
		return status;
	}

	public List<String> getLog() {
		return new ArrayList<>(log);
	}

	public int getContainersSearched() {
		return containersSearched;
	}

	public int getCollectedTotal() {
		return collectedTotal;
	}

	public boolean hasTask() {
		return task != null;
	}

	public int getQueueSize() {
		return queue.size();
	}

	public boolean isRunning() {
		return state != State.DONE && state != State.FAILED;
	}

	/** 生成假人后调用，等待其真正上线 */
	public void markWaitingSpawn() {
		this.state = State.WAITING_SPAWN;
		this.status = "等待假人生成";
		this.spawnWaitTicks = 0;
	}

	/**
	 * 提交一个任务。
	 *
	 * <p>若当前空闲则立即开始；否则排队，等前一个做完自动接上。</p>
	 *
	 * @return true 表示立即开始，false 表示已排入队列
	 */
	public boolean submit(MinecraftServer server, LootTask t) {
		if (task == null && (state == State.DONE || state == State.FAILED || state == State.SCAN)) {
			beginTask(server, t);
			return true;
		}
		queue.addLast(t);
		return false;
	}

	/** 清空任务队列与当前任务 */
	public int clearAll() {
		int n = queue.size() + (task != null ? 1 : 0);
		queue.clear();
		task = null;
		// 清空任务时一并停掉循环，否则下一轮又会被自动拉起
		disableRepeat();
		state = State.DONE;
		status = "任务已清空";
		return n;
	}

	/** 只清空排队中的任务，保留正在执行的 */
	public int clearQueue() {
		int n = queue.size();
		queue.clear();
		return n;
	}

	public void fail(String reason) {
		this.state = State.FAILED;
		this.status = reason;
		this.log.add("失败: " + reason);
		// 失败即归还所有容器认领，否则别的假人会一直跳过这些箱子
		ContainerClaims.releaseAll(name);
		// 关键：fail() 可能在任意位置被调用（含 BotCommand 的 stop/despawn），
		// 而那时不一定有 server 引用。这里只挂起通知，由 tick() 统一发出。
		this.pendingFailureNotice = reason;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(name)
		  .append("  [").append(paused ? "已暂停" : state.label()).append("]  ")
		  .append(status);
		if (task != null) {
			if (task.moveAll()) {
				// 搬家模式没有目标数量，显示「已搬 N 件」而不是「N/0」
				sb.append("  已搬 ").append(collectedForTask).append(" 件");
			} else {
				sb.append("  ").append(collectedForTask).append("/").append(task.count())
				  .append(" ").append(task.itemName());
			}
		}
		sb.append("  已搜容器=").append(containersSearched);
		if (!queue.isEmpty()) {
			sb.append("  队列=").append(queue.size());
		}
		if (navigator != null && state == State.WALK) {
			sb.append("  路点=").append(navigator.getProgress()).append("/").append(navigator.getLength());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ 任务生命周期

	private void beginTask(MinecraftServer server, LootTask t) {
		// 清掉上一轮可能残留的容器认领，避免占着不放
		ContainerClaims.releaseAll(name);
		resetStats(t);
		Diag.trace(server, name, "开始任务 item=" + t.itemName() + " count=" + t.count()
				+ " 搬家=" + t.moveAll() + " 扔地上=" + t.dropMode()
				+ " 目的地=" + (t.deposit() == null ? "null" : fmt(t.deposit()))
				+ " 目的维度=" + (t.depositDim() == null ? "null" : t.depositDim().location())
				+ " 范围=" + t.scopeText());
		this.task = t;
		this.visited.clear();
		this.container = null;
		this.openContainer = null;
		this.navigator = null;
		this.pathFailures = 0;
		this.containersSearched = 0;
		this.collectedForTask = 0;
		this.baselineForTask = -1;
		this.deliveredCount = 0;
		this.returnDim = null;
		this.returnPos = null;
		this.flushing = false;
		this.teleportCooldown = 0;
		this.waitTicks = 0;
		this.taskTicks = 0;
		this.totalMoved = 0;
		this.lastReportedStep = -1;
		this.unloadRounds = 0;
		this.stateTicks = 0;
		this.state = State.SCAN;
		this.status = "开始搜寻";
		this.log.clear();

		Notifier.taskStarted(server, name, t, queue.size());
	}

	/** 当前任务结束，尝试接上队列里的下一个 */
	/**
	 * 搬家完成后的二次确认：重新扫一遍区域，看还有没有非空容器。
	 *
	 * <p>「任务报告成功」和「真的搬空了」不是一回事——可能有的容器打不开、
	 * 有的在寻路失败后被跳过。这里如实核对并报告。</p>
	 *
	 * @return 一行说明；无法确认时返回 null
	 */
	private String verifyEmptied(MinecraftServer server, ServerPlayer bot, LootTask t) {
		try {
			ServerLevel level = (ServerLevel) bot.level();
			// 区域可能设在别的维度：只在假人当前维度核对（跨维度时它已回到出发地）
			List<BlockPos> found = t.isBoxMode()
					? ContainerScanner.scanBox(level, t.boxA(), t.boxB())
					: ContainerScanner.scanRadius(level, bot.blockPosition(), t.radius());

			int nonEmpty = 0;
			int checked = 0;
			for (BlockPos p : found) {
				// 目的地不是待搬容器，不算在内
				if (t.hasDeposit() && p.equals(t.deposit())) continue;
				if (!(level.getBlockEntity(p) instanceof Container c)) continue;
				checked++;
				for (int i = 0; i < c.getContainerSize(); i++) {
					if (!c.getItem(i).isEmpty()) {
						nonEmpty++;
						break;
					}
				}
			}

			Diag.trace(server, name, "搬空核对 检查=" + checked + " 非空=" + nonEmpty);

			if (nonEmpty == 0) {
				return "已确认 " + checked + " 个容器全部为空";
			}
			return "⚠ 还有 " + nonEmpty + " 个容器非空（共检查 " + checked
					+ " 个，可能有打不开或被跳过的）";
		} catch (Throwable e) {
			return null;
		}
	}

	private void completeTask(MinecraftServer server, ServerPlayer bot, boolean success, String reason) {
		LootTask done = this.task;
		// 任务结束即归还容器认领
		ContainerClaims.releaseAll(name);
		if (done != null) {
			if (success) {
				Notifier.taskDone(server, name, done, collectedForTask, !queue.isEmpty());
				// 搬家模式：额外做一次「真的搬空了吗」的核对
				if (done.moveAll() && bot != null) {
					String note = verifyEmptied(server, bot, done);
					if (note != null) {
						Notifier.info(server, name + " " + note);
					}
				}
			} else if (reason != null && reason.contains("范围内容器已搜遍")) {
				// 只有"数量不足"才该报「还差 N 个」；
				// 其它失败原因必须如实报出，否则会误导（曾经一律报"已搜遍"）
				Notifier.taskShort(server, name, done, collectedForTask, containersSearched);
			} else {
				Notifier.taskFailed(server, name, reason == null ? "未知原因" : reason);
			}
		}
		this.task = null;
		this.openContainer = null;
		// 记下这一轮的任务，供「跑完一次再开启循环」使用
		if (done != null) {
			this.lastTask = done;
		}
		// 必须先让假人停下来，否则它会沿原方向一直走出去。
		// bot 允许为 null（例如假人已被移除的场景），此时直接丢弃导航器。
		if (navigator != null) {
			if (bot != null) {
				navigator.stop(bot);
			}
			navigator = null;
		}

		LootTask next = queue.pollFirst();
		if (next != null) {
			beginTask(server, next);
		} else if (repeatEnabled && repeatTask != null) {
			// 循环模式：失败也继续——"范围内容器已搜遍"是正常状态，
			// 说明这轮没货可搬，不该让循环停掉。
			this.state = success ? State.DONE : State.FAILED;
			this.repeatWaitTicks = repeatIntervalTicks;
			int sec = repeatIntervalTicks / 20;
			this.status = "循环任务：本轮结束，等 " + sec + " 秒后重跑";
			Notifier.info(server, name + " 本轮结束，下一轮 " + sec + " 秒后开始");
		} else {
			this.state = success ? State.DONE : State.FAILED;
			this.status = reason;
		}
	}

	// ------------------------------------------------------------------ 主循环

	public void tick(MinecraftServer server) {
		// 先把挂起的失败通知发出去。
		// 必须放在最前面：状态变成 FAILED 后下面的分支可能提前 return，
		// 放后面会导致"失败没有提示"。
		if (pendingFailureNotice != null) {
			String reason = pendingFailureNotice;
			pendingFailureNotice = null;
			Notifier.taskFailed(server, name, reason);
		}

		ServerPlayer bot = server.getPlayerList().getPlayerByName(name);

		// 自动进食/维持饱食度：放在任务状态机之前，空闲时也生效
		if (bot != null && !bot.isRemoved()) {
			seenOnline = true;
			maybeFeed(bot);
			maybeEat(bot);
		} else if (seenOnline) {
			// 假人下线：自动重新标记为「有任务」，避免下次被误当成空闲
			seenOnline = false;
			hasTaskFlag = true;
			Diag.trace(server, name, "假人下线，自动标记为「有任务」");
		}

		if (state == State.WAITING_SPAWN) {
			if (bot != null && !bot.isRemoved()) {
				state = State.SCAN;
				status = "假人已就绪";
				if (!queue.isEmpty() && task == null) {
					beginTask(server, queue.pollFirst());
				}
			} else if (++spawnWaitTicks > SPAWN_TIMEOUT_TICKS) {
				fail("假人生成超时");
			}
			return;
		}

		// 循环任务：两轮之间的等待。
		//
		// 注意必须放在 `if (task == null) return;` **之前**——一轮结束后 task 是 null，
		// 若放在后面就永远轮不到这里，循环再也不会重启（实测症状：状态停在
		// 「本轮结束，等 N 秒后重跑」不再变化）。
		// 循环已开启、但当前空闲且没有待倒计时 → 立刻开一轮。
		//
		// 这个分支是为了修「空闲时开循环毫无反应」：
		// enableRepeat() 把 repeatWaitTicks 置为 0，而下面的倒计时分支
		// 条件是 `repeatWaitTicks > 0`，0 永远进不去，于是一轮也启动不了。
		// 实测症状：任务跑完后敲 /cbot repeat Bot 60，日志里一句都没有。
		if (task == null && repeatEnabled && repeatTask != null
				&& repeatWaitTicks <= 0 && queue.isEmpty()
				&& state != State.WAITING_SPAWN) {
			Diag.trace(server, name, "循环：空闲且无待倒计时，立即开始新一轮");
			beginTask(server, repeatTask);
			return;
		}

		if (repeatWaitTicks > 0) {
			repeatWaitTicks--;
			if (repeatWaitTicks <= 0 && repeatEnabled && repeatTask != null) {
				beginTask(server, repeatTask);
			}
			return;
		}

		// 空闲且队列里有任务 → 接上
		if (task == null && !queue.isEmpty() && state != State.WAITING_SPAWN) {
			beginTask(server, queue.pollFirst());
		}

		if (task == null) return;

		if (bot == null || bot.isRemoved()) {
			completeTask(server, null, false, "假人不存在或已被移除");
			return;
		}

		// 暂停：状态机完全冻结，连 stateTicks 都不增加（否则会被超时判定误判失败）。
		// 同时清掉移动输入，保证真的站着不动。
		if (paused) {
			CarpetBridge.stop(bot);
			bot.zza = 0.0f;
			bot.xxa = 0.0f;
			status = "已暂停";
			return;
		}

		// 计时必须在【等待分支之前】累加！
		//
		// 限速的预算是「速率 × 已用时间」。若等待期间不计时，预算永远涨不上去，
		// "等够了"这个条件永远不会成立，就会变成等待→超时→再等待的死循环。
		// 实测症状：已搬 64 件卡死 70 秒，等待量在 25 和 92 tick 之间反复。
		taskTicks++;

		// 节流/限速等待：整个状态机冻结，stateTicks 不增加，
		// 否则会被开箱/行走的超时判定误认为失败。（taskTicks 已在上方累加）
		if (waitTicks > 0) {
			waitTicks--;
			status = waitReason + "中（剩 " + waitTicks + " tick）";
			// 顺手停掉残留移动输入，保证真的站着不动
			CarpetBridge.stop(bot);
			bot.zza = 0.0f;
			bot.xxa = 0.0f;
			return;
		}

		// 传送后冷却：位置瞬间跳变，需等服务端同步后再交互。
		// 注意 return 时不增加 stateTicks，否则会被开箱超时误判为失败。
		if (teleportCooldown > 0) {
			teleportCooldown--;
			if (state == State.OPEN) {
				status = "传送后等待位置同步（剩 " + teleportCooldown + " tick）";
				return;
			}
		}

		stateTicks++;

		// 只要不是「正在行走」，就确保 Carpet 动作包的前进输入是零。
		//
		// 这是「假人跑了」的根治手段：动作包里的 setForward 是持续状态，
		// 一旦某条路径漏掉了一次 stop（例如从 WALK 直接进 OPEN 再到完成），
		// 假人就会沿原方向一直走出去。每 tick 兜底清零，成本极低。
		if (state != State.WALK) {
			CarpetBridge.stop(bot);
			bot.zza = 0.0f;
			bot.xxa = 0.0f;
		}

		// 在中央记录状态迁移——这样无需在每个调用点手工加诊断，
		// 任何状态跳变都会带上"为什么"进诊断缓冲。修 bug 时先看这里。
		State before = state;

		switch (state) {
			case SCAN -> tickScan(server, bot);
			case PATH -> tickPath(bot);
			case WALK -> tickWalk(bot);
			case OPEN -> tickOpen(server, bot);
			case LOOT -> tickLoot(server, bot);
			case UNLOAD -> tickUnload(server, bot);
			default -> { /* WAITING_SPAWN / DONE / FAILED 已处理 */ }
		}

		if (state != before) {
			Diag.transition(server, name, before.label(), state.label(), status);
		}
	}

	// ------------------------------------------------------------------ 各状态

	private void tickScan(MinecraftServer server, ServerPlayer bot) {
		status = "扫描附近容器";

		// 限速：超过目标吞吐就等待，等够了再继续搬
		if (rateLimit()) return;

		// 搬家模式没有数量上限，不检查是否达成
		if (!task.moveAll() && countForTask(bot) >= task.count()) {
			completeTask(server, bot, true, "已达成目标数量");
			return;
		}

		ServerLevel level = (ServerLevel) bot.level();

		// 选区可能设在别的维度：先把假人传送过去，
		// 否则它会去扫"当前维度里同样坐标"的地方，一个箱子都找不到。
		if (task.isBoxMode() && task.boxDim() != null
				&& !level.dimension().equals(task.boxDim())) {
			if (server.getLevel(task.boxDim()) == null) {
				completeTask(server, bot, false,
						"找不到选区所在维度: " + task.boxDim().location());
				return;
			}
			BlockPos c = task.boxA();
			teleportToDim(server, task.boxDim(), c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5);
			status = "传送到选区所在维度 " + task.boxDim().location();
			stateTicks = 0;
			return;
		}

		List<BlockPos> found = task.isBoxMode()
				? ContainerScanner.scanBox(level, task.boxA(), task.boxB())
				: ContainerScanner.scanRadius(level, bot.blockPosition(), task.radius());

		BlockPos next = null;
		for (BlockPos p : found) {
			// 目的地（卸货箱/投放点）不是待搬容器，必须排除：
			// 既不该去"掏"它把刚送去的货搬回来，也不该因为它是唯一剩余
			// 容器就让扫描误判成"还有得搬"。
			if (task.hasDeposit() && p.equals(task.deposit())) continue;
			if (visited.contains(p)) continue;

			// 已经被别的假人认领 → 跳过，去找下一个。
			// 这一步同时承担"检查 + 占用"，多个假人不会抢同一个箱子。
			if (!ContainerClaims.claim(level.dimension(), p, name)) {
				Diag.trace(server, name, "容器 " + fmt(p) + " 已被 "
						+ ContainerClaims.ownerOf(level.dimension(), p) + " 认领，跳过");
				continue;
			}
			next = p;
			break;
		}

		if (next == null) {
			if (task.moveAll()) {
				if (!flushing && countAllItems(bot) > 0 && task.hasDeposit()) {
					flushing = true;
					state = State.UNLOAD;
					stateTicks = 0;
					status = "容器已掏空，送最后一批货去目的地";
					return;
				}
				// 关键：收尾卸货没成功时绝不能报「完成」。
				// 曾经的 bug——传送到了目的地但箱子打不开，箱子被标记为已搜，
				// 扫描随即找不到容器，于是错误地报成功，而货还在假人身上。
				int stillHolding = task.moveAll() ? countAllItems(bot) : 0;
				if (flushing && stillHolding > 0) {
					returnHome(server, bot);
					completeTask(server, bot, false,
							"货物没能送达目的地，仍有 " + stillHolding + " 件在身上");
					return;
				}
				completeTask(server, bot, true, "所有容器已掏空");
			} else {
				// 数量不足 → 结束任务并明确提示差多少
				completeTask(server, bot, false, "范围内容器已搜遍");
			}
			return;
		}

		container = next;
		openingForUnload = false;         // 这次是正常取货
		state = State.PATH;
		stateTicks = 0;
		status = "目标容器 " + fmt(next);
	}

	private void tickPath(ServerPlayer bot) {
		// 已经站在容器旁/容器上 → 无需寻路，直接进交互。
		//
		// 跨维度传送后必然出现这种情况（落点就在目的地上）。此时 A* 的起点
		// 不是合法站立点（箱子内部），findPath 会返回 null，被误报成
		// 「连续 N 次无法走到目的地容器」。
		BlockPos cur = bot.blockPosition();
		if (cur.equals(container) || cur.equals(container.above())
				|| cur.distSqr(container) <= 4) {
			state = State.OPEN;
			stateTicks = 0;
			return;
		}

		ServerLevel level = (ServerLevel) bot.level();
		List<BlockPos> path = PathPlanner.findPath(level, bot.blockPosition(), container, 9.0);

		if (path == null) {
			// 目的地容器寻路失败不能被标记「已搜」，否则收尾卸货永远不再重试
			if (openingForUnload) {
				if (++unloadRounds > 6) {
					fail("连续 " + unloadRounds + " 次无法走到目的地容器");
					return;
				}
				state = State.UNLOAD;
				stateTicks = 0;
				status = "无法到达目的地容器，重试 (" + unloadRounds + ")";
				return;
			}
			visited.add(container);
			if (++pathFailures >= MAX_PATH_FAILURES) {
				state = State.SCAN;
				stateTicks = 0;
				status = "连续 " + pathFailures + " 次无法规划路径，放弃剩余容器";
				return;
			}
			state = State.SCAN;
			stateTicks = 0;
			status = "无法到达，跳过该容器";
			return;
		}

		navigator = new Navigator(path, container);
		state = State.WALK;
		stateTicks = 0;
		status = "行走中，路点 " + path.size() + " 个";
	}

	private void tickWalk(ServerPlayer bot) {
		if (navigator == null) {
			state = State.SCAN;
			return;
		}

		navigator.tick(bot);

		if (navigator.isStuck()) {
			// 兜底：走不过去就直接传送到容器旁，保证任务能继续
			navigator.stop(bot);
			teleportNear(bot, container);
			status = "寻路受阻，已兜底传送到容器旁";
			state = State.OPEN;
			stateTicks = 0;
			return;
		}

		if (navigator.isFinished()) {
			navigator.stop(bot);
			// 「扔地上」的投放点不是容器，不能走 OPEN（那里要求目的地是方块实体）
			state = dropModeWalking ? State.UNLOAD : State.OPEN;
			stateTicks = 0;
		}
	}

	private void tickOpen(MinecraftServer server, ServerPlayer bot) {
		status = "尝试打开容器";
		ServerLevel level = (ServerLevel) bot.level();

		if (!(level.getBlockEntity(container) instanceof Container chest)) {
			visited.add(container);
			state = State.SCAN;
			stateTicks = 0;
			return;
		}

		Vec3 center = Vec3.atCenterOf(container);
		// 太远够不到，以及【站在容器正上方】都要先挪位。
		//
		// 注意判定用的是 container 本身而不是 container.above()：箱子的碰撞箱
		// 只有 14/16 高，站上去时脚在 y=64.875，blockPosition() 恰好等于箱子
		// 自己的坐标（而不是上方那格）。早先只判 above() 导致漏判，
		// 跨维度传送后开箱失败。
		if (bot.blockPosition().equals(container)
				|| bot.blockPosition().equals(container.above())
				|| bot.distanceToSqr(center) > 20.0) {
			teleportNear(bot, container);
		}

		faceTo(bot, center);

		// 两条打开路径，按用途分开：
		//
		//  · 取货（源容器）→ 走原版 useItemOn，等价于真人右键。已验证可靠，
		//    保留"真实交互"语义。
		//  · 投送（目的地容器）→ 直接 openMenu。稳定性优先：诊断实测跨维度传送后
		//    原版右键会被判定成"物品交互"（wasItemInteraction=true）而不打开容器，
		//    连续 7 次重试全部失败。目的地是用户自己指定的输入口，
		//    这里不追求拟真，追求一定能倒进去。
		String useResult;
		String useError = "";
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, container, false);
		try {
			if (openingForUnload) {
				if (chest instanceof MenuProvider provider) {
					bot.openMenu(provider);
					useResult = "openMenu(" + provider.getClass().getSimpleName() + ")";
				} else {
					// 不是 MenuProvider 就退回右键路径
					useResult = "非MenuProvider,回退:" + (bot.gameMode == null ? "gameMode=null"
							: String.valueOf(bot.gameMode.useItemOn(bot, level,
									bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit)));
				}
			} else if (bot.gameMode == null) {
				useResult = "gameMode=null";
			} else {
				useResult = String.valueOf(bot.gameMode.useItemOn(
						bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit));
			}
		} catch (Throwable e) {
			useResult = "抛异常";
			useError = " " + e.getClass().getSimpleName() + ":" + e.getMessage();
			ContainerBotMod.LOGGER.warn("[ContainerBot] {} 打开容器异常: {}", name, e.toString());
		}

		AbstractContainerMenu menu = bot.containerMenu;
		if (menu != null && menu != bot.inventoryMenu) {
			openContainer = chest;
			// 按本次开箱的意图决定去向，绝不能一律置为 LOOT
			state = openingForUnload ? State.UNLOAD : State.LOOT;
			stateTicks = 0;
			status = openingForUnload ? "卸货箱已打开" : "容器已打开";

			// 「已搜容器」在真正打开时就计一次，而不是等"翻完发现没货"才计。
			// 否则只要取到货就直接完成任务、提前 return，这个数会一直显示 0，
			// 与实际行为（明明开过箱子）自相矛盾。卸货箱不算搜索。
			if (!openingForUnload) {
				containersSearched++;
			}
			return;
		}

		if (stateTicks > OPEN_TIMEOUT_TICKS) {
			// 打不开时输出完整诊断，避免继续盲猜
			Notifier.warn(server, name + " 开箱诊断: 目标=" + fmt(container)
					+ " 维度=" + level.dimension().location()
					+ " 假人=" + fmt(bot.blockPosition())
					+ " 距离²=" + String.format("%.2f", bot.distanceToSqr(center))
					+ " useItemOn=" + useResult + useError
					+ " menu=" + (menu == null ? "null" : menu.getClass().getSimpleName())
					+ " 是背包菜单=" + (menu == bot.inventoryMenu)
					+ " 创造=" + bot.isCreative() + " 旁观=" + bot.isSpectator()
					+ " BE=" + String.valueOf(level.getBlockEntity(container))
					+ " 主手=[" + bot.getMainHandItem() + "]");
			// 目的地容器打不开时不能标记「已搜」，否则收尾卸货永远不再重试
			if (openingForUnload) {
				if (++unloadRounds > 6) {
					fail("连续 " + unloadRounds + " 次无法打开目的地容器 " + fmt(container));
					return;
				}
				state = State.UNLOAD;
				stateTicks = 0;
				status = "打不开目的地容器，重试 (" + unloadRounds + ")";
				return;
			}
			visited.add(container);
			state = State.SCAN;
			stateTicks = 0;
			containersSearched++;
			skippedContainers++;
			status = "无法打开容器，跳过";
		}
	}

	private void tickLoot(MinecraftServer server, ServerPlayer bot) {
		status = "提取物品";
		AbstractContainerMenu menu = bot.containerMenu;

		if (menu == null || menu == bot.inventoryMenu || openContainer == null) {
			state = State.SCAN;
			stateTicks = 0;
			return;
		}

		// 搬家模式没有数量上限，只有取货模式才检查是否达成
		if (!task.moveAll() && countForTask(bot) >= task.count()) {
			closeContainer(bot);
			reportProgress(server, bot, true);
			completeTask(server, bot, true, "已达成目标数量");
			return;
		}

		if (isInventoryFull(bot)) {
			closeContainer(bot);
			// 自动卸货，然后继续搬
			state = State.UNLOAD;
			stateTicks = 0;
			status = "背包已满，准备卸货";
			return;
		}

		// 记录本次从容器里实际搬进假人的件数（限速用，只增不减）
		int beforeLoot = task.moveAll() ? countAllItems(bot) : countItemInInventory(bot, task);

		boolean movedAny = false;
		List<Slot> slots = menu.slots;
		for (int i = 0; i < slots.size(); i++) {
			Slot slot = slots.get(i);

			// 只处理容器本身的槽位，绝不能对玩家背包槽位做 QUICK_MOVE，
			// 否则方向相反：会把背包里的东西塞进箱子。
			if (slot.container != openContainer) continue;

			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			// ---- 搬家模式：容器里的一切都搬走，除非被白/黑名单过滤掉 ----
			if (task.moveAll()) {
				boolean allowed = allowedByFilter(stack);
				// 诊断：黑名单曾出现"漏掉某一格"的怪现象，这里逐槽位记录判定结果
				Diag.trace(server, name, "搬家判定 容器=" + fmt(container)
						+ " 槽位=" + i
						+ " 物品=" + stack.getItem()
						+ " x" + stack.getCount()
						+ " 允许=" + allowed
						+ " 白=[" + describeSet(filterWhite) + "]"
						+ " 黑=[" + describeSet(filterBlack) + "]");
				if (!allowed) continue;
				// 必须在 clicked 之前记录：ItemStack 是可变的，
				// 搬走之后原槽位会被清空，此时再读就变成 Air/0 了。
				var statItem = stack.getItem();
				int statCount = stack.getCount();
				menu.clicked(i, 0, ClickType.QUICK_MOVE, bot);
				itemStats.merge(statItem, statCount, Integer::sum);
				movedAny = true;
				// 每次搬完检查背包，满了就先去卸货
				if (isInventoryFull(bot)) break;
				continue;
			}

			// 优先：目标数量够大时，装着目标物品的潜影盒整盒搬走
			if (task.useShulkers() && ShulkerUtil.isShulkerBox(stack)) {
				if (ShulkerUtil.countItem(stack, task.item()) > 0) {
					menu.clicked(i, 0, ClickType.QUICK_MOVE, bot);
					movedAny = true;
					if (countForTask(bot) >= task.count()) break;
					continue;
				}
			}

			if (!stack.is(task.item())) continue;

			// 精确模式：整组搬会超额 → 用鼠标分流精确取走 need 个
			if (exactEnabled) {
				int need = task.count() - countForTask(bot);
				if (need <= 0) break;
				if (stack.getCount() > need) {
					int have = stack.getCount();
					var statItem = stack.getItem();
					if (splitTake(menu, i, have, need, bot)) {
						itemStats.merge(statItem, need, Integer::sum);
						movedAny = true;
						Diag.trace(server, name, "精确拆分：从 " + have + " 个中取走 " + need + " 个");
						break;   // 已经够了
					}
					continue;    // 这个槽拆不了，试下一个
				}
			}

			// QUICK_MOVE = 真人「Shift + 左键」，整组搬进背包
			// 统计必须在 clicked 之前取，见上方说明
			var statItem2 = stack.getItem();
			int statCount2 = stack.getCount();
			menu.clicked(i, 0, ClickType.QUICK_MOVE, bot);
			itemStats.merge(statItem2, statCount2, Integer::sum);
			movedAny = true;

			if (countForTask(bot) >= task.count()) break;
		}

		totalMoved += Math.max(0,
				(task.moveAll() ? countAllItems(bot) : countItemInInventory(bot, task)) - beforeLoot);

		reportProgress(server, bot, false);

		// 搬家模式不受数量限制；取货模式才检查达成
		if (!task.moveAll() && countForTask(bot) >= task.count()) {
			closeContainer(bot);
			completeTask(server, bot, true, "已达成目标数量");
			return;
		}

		if (!movedAny) {
			visited.add(container);
			// 计数已在开箱时加过，这里不再重复
			closeContainer(bot);
			state = State.SCAN;
			stateTicks = 0;
			status = task.moveAll() ? "该容器已掏空，换下一个" : "该容器无目标物品，换下一个";
			pace();   // 节流：处理完一个容器后按设定停顿
		} else if (isInventoryFull(bot)) {
			// 搬满了就先去卸货，再回来继续掏这个容器
			closeContainer(bot);
			state = State.UNLOAD;
			stateTicks = 0;
			status = "背包已满，准备卸货";
		}
	}

	/** 卸货：把背包里的目标物品倒进附近的容器，腾出空间后继续搬 */
	private void tickUnload(MinecraftServer server, ServerPlayer bot) {
		status = "卸货中";
		ServerLevel level = (ServerLevel) bot.level();

		// 「扔在地上」模式：目的地不要求是容器，走到就直接丢
		if (task.dropMode()) {
			tickUnloadDrop(server, bot, level);
			return;
		}

		if (unloadRounds > 8) {
			closeContainer(bot);
			fail("连续卸货失败，放弃（附近没有可用的空容器？）");
			return;
		}

		AbstractContainerMenu menu = bot.containerMenu;
		if (menu == null || menu == bot.inventoryMenu || openContainer == null) {
			// 优先使用玩家指定的卸货箱/搬家目的地；
			// 只有在没指定时才退化为「自动找附近容器」。
			BlockPos dump = task.hasDeposit() ? task.deposit() : findDumpTarget(level, bot);
			if (dump == null) {
				fail("背包已满且找不到可卸货的容器（用 /cbot deposit 指定一个）");
				return;
			}

			// 跨维度：目的地与当前维度不同时，直接传送过去。
			// 假人不会自己找传送门，硬走容易卡在下界门里，所以用传送最可靠。
			//
			// 诊断先行：上一轮加了诊断才发现这条分支从未被执行，
			// 而前 6 个版本都在盲改它内部的代码。这里把实际取值打出来。
			Diag.trace(server, name, "卸货点维度检查: 目的=" + task.depositDim()
					+ " 当前=" + level.dimension()
					+ " 需要传送=" + (task.depositDim() != null
							&& !level.dimension().equals(task.depositDim()))
					+ " 卸货点=" + fmt(dump)
					+ " 假人=" + fmt(bot.blockPosition()));

			if (task.depositDim() != null && !level.dimension().equals(task.depositDim())) {
				// 记住出发地，卸完货要回来接着搬
				if (returnDim == null) {
					returnDim = level.dimension();
					returnPos = bot.blockPosition();
				}
				teleportToDim(server, task.depositDim(),
						dump.getX() + 0.5, dump.getY() + 1.0, dump.getZ() + 0.5);
				status = "已传送到 " + task.depositDim().location() + " 卸货";
				stateTicks = 0;
				unloadRounds++;
				return;   // 下一 tick 在新维度里重新走向容器
			}

			container = dump;
			openingForUnload = true;      // 告诉 tickOpen：这次是去卸货
			state = State.PATH;
			stateTicks = 0;
			unloadRounds++;
			return;
		}

		// 已打开容器 → 把货物整组塞进去
		BlockPos dumpPos = container;
		int before = countAllItems(bot);

		boolean movedAny = false;
		List<Slot> slots = menu.slots;
		for (int i = 0; i < slots.size(); i++) {
			Slot slot = slots.get(i);
			if (slot.container == openContainer) continue;   // 只动玩家背包侧
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			// 搬家模式：背包里的一切都倒进去（包括刚搬来的潜影盒本身）
			if (task.moveAll()) {
				menu.clicked(i, 0, ClickType.QUICK_MOVE, bot);
				movedAny = true;
				continue;
			}

			boolean isTarget = stack.is(task.item());
			boolean isTargetShulker = task.useShulkers()
					&& ShulkerUtil.isShulkerBox(stack)
					&& ShulkerUtil.countItem(stack, task.item()) > 0;
			if (!isTarget && !isTargetShulker) continue;

			menu.clicked(i, 0, ClickType.QUICK_MOVE, bot);
			movedAny = true;
		}

		int after = countAllItems(bot);
		closeContainer(bot);

		if (!movedAny) {
			// 搬家模式下卸货箱已满也属于「倒不进去」，直接明确报错
			fail(task.moveAll() ? "目的地容器已满，无法继续倒货" : "背包已满且无法把货物倒进容器");
			return;
		}

		// 把「搬出去多少」计入已交付，否则进度会因卸货而倒退
		deliveredCount += Math.max(0, before - after);

		// 关键：把卸货箱标记为已搜，否则下次扫描会把它当目标容器，
		// 把刚倒出去的货物又搬回来，形成死循环。
		if (dumpPos != null) {
			visited.add(dumpPos);
		}

		// 跨维度搬家：卸完货要回到出发地继续掏容器，
		// 否则下一轮 SCAN 会去扫目的地的区域，而不是待搬的储物区。
		if (returnDim != null && !((ServerLevel) bot.level()).dimension().equals(returnDim)) {
			BlockPos back = returnPos != null ? returnPos : dumpPos;
			teleportToDim(server, returnDim, back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
			status = "已返回 " + returnDim.location() + " 继续搬运（已交付 " + deliveredCount + "）";
		} else {
			status = "卸货完成（已交付 " + deliveredCount + "），继续搬运";
		}

		// 收尾卸货：尾货已送达，任务才算真正完成
		if (flushing) {
			completeTask(server, bot, true, "所有容器已掏空，货物已送达目的地");
			return;
		}

		state = State.SCAN;
		stateTicks = 0;
	}

	/** 卸货完成、跨维度时用来回到出发地 */
	private ResourceKey<Level> returnDim;
	private BlockPos returnPos;

	/**
	 * 跨维度传送。
	 *
	 * <p>不用 {@code ServerPlayer.teleportTo(ServerLevel,...)}——1.21.8 没有该签名。
	 * 改为借用原版 {@code /execute in <维度> run tp} 的完整逻辑：它已经正确处理了
	 * 维度切换、区块加载、载具与乘客等所有边界情况，比手写传送可靠得多。</p>
	 */
	private void teleportToDim(MinecraftServer server, ResourceKey<Level> dim, double x, double y, double z) {
		String cmd = "execute in " + dim.location() + " run tp " + name + " " + x + " " + y + " " + z;
		try {
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withSuppressedOutput().withPermission(4), cmd);
			// 传送后位置是瞬间跳变的，交互判定需要几 tick 才能同步
			teleportCooldown = Math.max(teleportCooldown, 12);
		} catch (Throwable t) {
			ContainerBotMod.LOGGER.warn("[ContainerBot] {} 跨维度传送失败: {}", name, t.toString());
		}
	}

	/**
	 * 「扔在地上」模式的卸货：不打开任何容器，走到目的地后把背包里的东西全丢出来。
	 *
	 * <p>用于「把货倾倒在某处」的场景，例如喂给漏斗/收集装置，或单纯的临时堆放。</p>
	 */
	private void tickUnloadDrop(MinecraftServer server, ServerPlayer bot, ServerLevel level) {
		BlockPos dest = task.deposit();
		if (dest == null) {
			fail("未指定投放点");
			return;
		}

		// 跨维度 → 传送过去
		if (task.depositDim() != null && !level.dimension().equals(task.depositDim())) {
			if (returnDim == null) {
				returnDim = level.dimension();
				returnPos = bot.blockPosition();
			}
			teleportToDim(server, task.depositDim(),
					dest.getX() + 0.5, dest.getY() + 1.0, dest.getZ() + 0.5);
			status = "已传送到 " + task.depositDim().location() + " 投放";
			stateTicks = 0;
			return;
		}

		// 还没到位 → 先走过去；走一次仍不到位就传送。
		// 因为寻路只保证到目标附近（9 格容差），光靠走可能永远进不了 2 格内，
		// 实测会导致反复重新寻路直到放弃。
		if (bot.blockPosition().distSqr(dest) > 4.0) {
			if (dropWalked) {
				teleportNear(bot, dest);
				dropWalked = false;
				stateTicks = 0;
				return;
			}
			container = dest;
			openingForUnload = true;
			dropWalked = true;
			// 扔地上模式不走 OPEN（那里要求目的地是容器），走完直接进 UNLOAD
			state = State.PATH;
			dropModeWalking = true;
			stateTicks = 0;
			status = "前往投放点 " + fmt(dest);
			return;
		}

		// 到位了 → 全丢出去
		int before = countAllItems(bot);
		if (before == 0) {
			finishUnloadTrip(server, bot, dest);
			return;
		}

		dropEverything(bot);
		int after = countAllItems(bot);
		if (after >= before) {
			fail("无法在投放点 " + fmt(dest) + " 丢出物品（位置被占用？）");
			return;
		}
		deliveredCount += before - after;

		if (after > 0) {
			status = "投放中…还剩 " + after + " 件";
			return;   // 下一 tick 继续丢
		}
		finishUnloadTrip(server, bot, dest);
	}

	/** 把背包主槽位里的东西全部丢到脚下 */
	private void dropEverything(ServerPlayer bot) {
		var inv = bot.getInventory();
		int limit = Math.min(MAIN_SLOTS, inv.getContainerSize());
		for (int i = 0; i < limit; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			ItemStack copy = stack.copy();
			inv.setItem(i, ItemStack.EMPTY);
			bot.drop(copy, false, false);
		}
		bot.containerMenu.broadcastChanges();
	}

	/** 一趟卸货/投放结束：处理回程、收尾完成、或继续搬 */
	private void finishUnloadTrip(MinecraftServer server, ServerPlayer bot, BlockPos dumpPos) {
		if (dumpPos != null) {
			visited.add(dumpPos);
		}

		// 跨维度：卸完要回到出发地继续掏容器，
		// 否则下一轮 SCAN 会去扫目的地的区域，而不是待搬的储物区。
		if (returnDim != null && !((ServerLevel) bot.level()).dimension().equals(returnDim)) {
			BlockPos back = returnPos != null ? returnPos : dumpPos;
			teleportToDim(server, returnDim, back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
			status = "已返回 " + returnDim.location() + " 继续搬运（已交付 " + deliveredCount + "）";
		} else {
			status = "卸货完成（已交付 " + deliveredCount + "），继续搬运";
		}

		if (flushing) {
			completeTask(server, bot, true, "所有容器已掏空，货物已送达目的地");
			return;
		}

		dropModeWalking = false;
		dropWalked = false;
		state = State.SCAN;
		stateTicks = 0;
		pace();   // 节流：一趟卸货结束后按设定停顿
	}

	/** 若当前不在出发地维度，传送回去 */
	private void returnHome(MinecraftServer server, ServerPlayer bot) {
		if (returnDim == null) return;
		if (((ServerLevel) bot.level()).dimension().equals(returnDim)) return;
		BlockPos back = returnPos != null ? returnPos : bot.blockPosition();
		teleportToDim(server, returnDim, back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
	}

	/** 找一个可以倒货的容器（不是当前正在搜的那个） */
	private BlockPos findDumpTarget(ServerLevel level, ServerPlayer bot) {
		List<BlockPos> found = ContainerScanner.scanRadius(level, bot.blockPosition(), Math.max(16, task.radius()));
		for (BlockPos p : found) {
			if (!p.equals(container)) {
				return p;
			}
		}
		return null;
	}

	/** 按百分比节流地报告进度 */
	private void reportProgress(MinecraftServer server, ServerPlayer bot, boolean force) {
		int have = countForTask(bot);
		int pct = task.count() <= 0 ? 100 : (int) Math.min(100, have * 100L / task.count());
		int step = pct / PROGRESS_STEP_PERCENT;
		if (force || step > lastReportedStep) {
			lastReportedStep = step;
			Notifier.taskProgress(server, name, task, have);
		}
	}

	// ------------------------------------------------------------------ 工具方法

	private void closeContainer(ServerPlayer bot) {
		try {
			if (bot.containerMenu != null && bot.containerMenu != bot.inventoryMenu) {
				bot.closeContainer();
			}
		} catch (Exception ignored) {
			// 关闭失败不影响后续流程
		}
		openContainer = null;
	}

	/** 统计任务进度 = 已卸货交付的 + 身上还带着的。
	 *  顺带刷新 {@code collectedForTask}，供状态显示与完成报告使用。
	 *  搬家模式没有目标物品，改计「所有物品总数」。 */
	/**
	 * 容器某个槽位里/外「精确取走」need 个（need &lt; 该槽数量）。
	 *
	 * <p>原版没有"取一半"这种操作，只能用<b>鼠标分流</b>一步一步做，
	 * 全程等价真人操作：</p>
	 * <ol>
	 *   <li>左键点该槽 → 光标拿起整组 S 个</li>
	 *   <li>右键点同一槽 (S - need) 次 → 每次放回 1 个</li>
	 *   <li>左键点背包空位 → 光标上剩的 need 个进背包</li>
	 * </ol>
	 *
	 * <p>第二步最多 63 次点击，都是本地菜单操作，不产生网络往返，可以接受。</p>
	 *
	 * @return true 表示成功取走恰好 need 个
	 */
	private boolean splitTake(AbstractContainerMenu menu, int menuSlot, int have, int need, ServerPlayer bot) {
		if (need <= 0 || need >= have) return false;

		// 1. 拿起整组
		menu.clicked(menuSlot, 0, ClickType.PICKUP, bot);
		int carried = menu.getCarried().getCount();
		if (carried != have) {
			// 没按预期拿起来 → 把光标上的东西放回去，放弃这个槽
			if (carried > 0) {
				menu.clicked(menuSlot, 0, ClickType.PICKUP, bot);
			}
			return false;
		}

		// 2. 右键一枚一枚放回，直到光标只剩 need 个
		int putBack = have - need;
		for (int k = 0; k < putBack; k++) {
			menu.clicked(menuSlot, 1, ClickType.PICKUP, bot);
		}

		// 3. 放到背包空位
		int free = findFreePlayerSlot(menu, bot);
		if (free < 0) {
			// 背包满了：整组放回原槽，放弃
			menu.clicked(menuSlot, 0, ClickType.PICKUP, bot);
			Diag.trace(null, name, "精确拆分失败：背包没有空位");
			return false;
		}
		menu.clicked(free, 0, ClickType.PICKUP, bot);
		return true;
	}

	/** 在打开的菜单里找一个玩家侧的背包空位（返回菜单槽位索引，找不到返回 -1） */
	private int findFreePlayerSlot(AbstractContainerMenu menu, ServerPlayer bot) {
		for (int i = 0; i < menu.slots.size(); i++) {
			net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
			if (slot.container == openContainer) continue;   // 容器侧跳过
			if (!slot.getItem().isEmpty()) continue;
			if (!slot.mayPickup(bot)) continue;
			return i;
		}
		return -1;
	}

	private int countForTask(ServerPlayer bot) {
		int raw = deliveredCount
				+ (task.moveAll() ? countAllItems(bot) : countItemInInventory(bot, task));

		if (task.moveAll()) {
			collectedForTask = raw;
			return collectedForTask;
		}

		// 必须扣掉任务开始前背包里就有的数量，否则会出现
		// 「已达成目标数量 + 已搜容器=0」这种自相矛盾的状态：
		// 假人本来就揣着 64 个钻石，一个箱子没搜就判完成。
		if (baselineForTask < 0) {
			baselineForTask = raw;
		}
		collectedForTask = Math.max(0, raw - baselineForTask);
		return collectedForTask;
	}

	/** 背包里所有物品的总数（搬家模式用） */
	private int countAllItems(ServerPlayer bot) {
		int total = 0;
		var inv = bot.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			total += inv.getItem(i).getCount();
		}
		return total;
	}

	private int countItemInInventory(ServerPlayer bot, LootTask t) {
		int total = 0;
		var inv = bot.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (s.is(t.item())) {
				total += s.getCount();
			} else if (t.useShulkers() && ShulkerUtil.isShulkerBox(s)) {
				// 整盒搬走，但按盒内实际数量计数
				total += ShulkerUtil.countItem(s, t.item());
			}
		}
		return total;
	}

	public int countInInventory(ServerPlayer bot) {
		return task == null ? 0 : countItemInInventory(bot, task);
	}

	/**
	 * 背包是否已满。
	 *
	 * <p>注意：{@code Inventory.getContainerSize()} 返回 41（36 主槽 + 4 护甲 + 1 副手），
	 * 而护甲与副手槽平时永远是空的。若用 41 判断，该条件永不成立——实测表现为
	 * 假人卡在「提取物品」状态不动。因此这里只看 0..35 这 36 个主槽位。</p>
	 */
	private static final int MAIN_SLOTS = 36;

	private boolean isInventoryFull(ServerPlayer bot) {
		var inv = bot.getInventory();
		int limit = Math.min(MAIN_SLOTS, inv.getContainerSize());
		for (int i = 0; i < limit; i++) {
			if (inv.getItem(i).isEmpty()) return false;
		}
		return true;
	}

	private void faceTo(ServerPlayer bot, Vec3 target) {
		Vec3 eye = bot.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(pitch);
	}

	/** 把假人放到容器相邻的可站立格子上 */
	private void teleportNear(ServerPlayer bot, BlockPos pos) {
		ServerLevel level = (ServerLevel) bot.level();
		// 去掉「原地」偏移（站在容器上打不开），并多试几圈更远的位置
		int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
		for (int[] o : offsets) {
			BlockPos cand = pos.offset(o[0], 0, o[1]);
			if (PathPlanner.canStandAt(level, cand)) {
				bot.teleportTo(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
				bot.setDeltaMovement(Vec3.ZERO);
				teleportCooldown = Math.max(teleportCooldown, 6);
				return;
			}
		}
		// 实在找不到可站立格时，放到旁边【同一高度】。
		// 绝不能放到容器顶上方（pos.getY()+1）——站箱顶会导致右键打不开，
		// 实测跨维度投送就是栽在这里。
		bot.teleportTo(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
		bot.setDeltaMovement(Vec3.ZERO);
		teleportCooldown = Math.max(teleportCooldown, 6);
	}

	private static String fmt(BlockPos p) {
		return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
	}
}
