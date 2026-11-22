package com.containerbot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 假人的生存维持：饱食度维持（feed）与自动进食（eat）。
 *
 * <h3>为什么单独成类</h3>
 * 这块逻辑和搬货状态机<b>完全没有耦合</b>——它只关心假人的 {@code FoodData} 和背包。
 * 从 2000 多行的 {@code LootBot} 里拆出来，既缩小了主类，也让这部分能被独立阅读和测试。
 *
 * <h3>两种模式的分工</h3>
 * <ul>
 *   <li>{@code eat} = 默认行为（默认开），让假人像真人一样吃背包里的食物</li>
 *   <li>{@code feed} = 可选兜底（默认关），直接改 {@code FoodData} 补满，
 *       相当于"作弊式保命"</li>
 * </ul>
 *
 * <p><b>为什么 feed 是直接改数据而不是加状态效果</b>：原版<b>没有</b> "Saturation 状态效果"，
 * 只有 Hunger。想维持饱食度只能操作 {@code FoodData}。</p>
 */
public final class SurvivalKit {

	/** 饥饿值低于此值就吃东西（20 = 满，18 = 掉了两格） */
	private static final int EAT_THRESHOLD = 18;

	/** 饥饿值低于此值就补满 */
	private static final int FEED_THRESHOLD = 18;
	/** 补满后的饥饿值 */
	private static final int FEED_TARGET = 20;
	/** 补满后的饱和度 */
	private static final float FEED_SATURATION = 5.0f;

	private final String botName;

	/** 自动进食（默认开启） */
	private volatile boolean eatEnabled = true;
	/** 饱食度维持（默认关闭） */
	private volatile boolean feedEnabled;

	public SurvivalKit(String botName) {
		this.botName = botName == null ? "?" : botName;
	}

	// ------------------------------------------------------------------ 开关

	public boolean isEatEnabled() {
		return eatEnabled;
	}

	public void setEatEnabled(boolean v) {
		this.eatEnabled = v;
	}

	public boolean isFeedEnabled() {
		return feedEnabled;
	}

	public void setFeedEnabled(boolean v) {
		this.feedEnabled = v;
	}

	// ------------------------------------------------------------------ 每 tick

	/** 每个假人 tick 调用一次：先补饱食度（如果开着），再考虑吃东西 */
	public void tick(ServerPlayer bot) {
		if (bot == null || bot.isRemoved()) return;
		maybeFeed(bot);
		maybeEat(bot);
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
		Diag.trace(null, botName, "维持饱食度: " + level + " -> " + FEED_TARGET);
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
	 * @param force true 表示无视饥饿值与开关，强制吃（供 {@code /cbot eat <名字> test} 验证用）
	 */
	public void eatOnce(ServerPlayer bot, boolean force) {
		if (bot == null || bot.isRemoved()) return;
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
			Diag.trace(null, botName, "自动进食: " + ItemNames.displayName(held.getItem())
					+ " 饥饿值=" + food.getFoodLevel() + (force ? "（强制）" : ""));
			return;
		}

		// 2) 副手（slot 40）——原版不会自动从副手吃，必须显式指定那只手。
		//    主手和背包都没有食物时才回退到这里。
		ItemStack off = bot.getOffhandItem();
		if (!off.isEmpty() && off.has(net.minecraft.core.component.DataComponents.FOOD)) {
			bot.gameMode.useItem(bot, (ServerLevel) bot.level(), off, InteractionHand.OFF_HAND);
			Diag.trace(null, botName, "自动进食（副手）: " + ItemNames.displayName(off.getItem())
					+ " 饥饿值=" + food.getFoodLevel());
			return;
		}

		// 没有食物可吃：不报错，只记录（避免刷屏）
		Diag.trace(null, botName, "饿了但没有食物（饥饿值=" + food.getFoodLevel() + "）");
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
		Diag.trace(null, botName, "测试钩子：把饥饿值设为 " + food + "（原 " + before + "）");
		return before;
	}
}
