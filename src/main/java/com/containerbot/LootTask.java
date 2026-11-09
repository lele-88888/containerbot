package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 一次任务的完整定义。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>取货模式</b>（{@code moveAll = false}）：只拿 {@code item}，拿够 {@code count} 个即完成。</li>
 *   <li><b>搬家模式</b>（{@code moveAll = true}）：把选定区域内<b>所有</b>容器掏空，
 *       东西全部倒进 {@code deposit} 指定的「输入口」。{@code item}/{@code count} 被忽略。</li>
 * </ul>
 *
 * @param item             目标物品（搬家模式忽略）
 * @param count            目标数量（搬家模式忽略）
 * @param radius           半径模式下的搜寻半径
 * @param boxA             区域模式角点 1（可空；非空时忽略 radius）
 * @param boxB             区域模式角点 2（可空）
 * @param shulkerThreshold 目标数量超过此值时才启用「潜影盒整盒搬运」策略；0 表示禁用
 * @param deposit          卸货点 / 搬家目的地。背包满时把货物倒进这里
 * @param depositDim       目的地的维度。为空表示与出发地同维度；
 *                         非空且与出发地不同时会直接传送过去
 * @param moveAll          是否为搬家模式
 * @param dropMode         目的地放置方式：
 *                         {@code false} = 倒进目的地的容器（默认）；
 *                         {@code true}  = 直接扔在地上（目的地不要求是容器）
 * @param intervalTicks    节流间隔：每处理完一个容器后暂停的 tick 数（20 tick = 1 秒）。
 *                         0 表示全速（默认）。用于降低假人的动作频率，
 *                         既让行为更自然，也能显著减轻服务端压力
 *                         （容器很多或涉及跨维度传送时尤其明显）。
 * @param itemsPerHour     吞吐限速：平均每小时最多搬运多少件物品。0 = 不限。
 *                         与 intervalTicks 的区别：intervalTicks 限制的是「动作频率」，
 *                         本字段限制的是「实际搬运量」——不管一个容器里有多少东西，
 *                         平均速率都不会超过这个值。适合精确控制对服务器的压力。
 */
public record LootTask(Item item,
                       int count,
                       int radius,
                       BlockPos boxA,
                       BlockPos boxB,
                       ResourceKey<Level> boxDim,
                       int shulkerThreshold,
                       BlockPos deposit,
                       ResourceKey<Level> depositDim,
                       boolean moveAll,
                       boolean dropMode,
                       int intervalTicks,
                       int itemsPerHour,
                       java.util.List<Item> whiteList) {

	/** 默认的潜影盒策略启用阈值 */
	public static final int DEFAULT_SHULKER_THRESHOLD = 256;

	/** 一小时对应的 tick 数（20 tick/秒 × 3600 秒） */
	public static final long TICKS_PER_HOUR = 72_000L;

	/** 构造「取货模式」任务 */
	public static LootTask loot(Item item, int count, int radius, BlockPos boxA, BlockPos boxB,
	                            ResourceKey<Level> boxDim, int shulkerThreshold,
	                            BlockPos deposit, ResourceKey<Level> depositDim,
	                            boolean dropMode, int intervalTicks, int itemsPerHour,
	                            java.util.List<Item> whiteList) {
		return new LootTask(item, count, radius, boxA, boxB, boxDim, shulkerThreshold,
				deposit, depositDim, false, dropMode,
				Math.max(0, intervalTicks), Math.max(0, itemsPerHour),
				whiteList == null ? java.util.List.of() : java.util.List.copyOf(whiteList));
	}

	/** 构造「搬家模式」任务：掏空区域内所有容器，全部送到 deposit */
	public static LootTask move(int radius, BlockPos boxA, BlockPos boxB, ResourceKey<Level> boxDim,
	                            BlockPos deposit, ResourceKey<Level> depositDim, boolean dropMode,
	                            int intervalTicks, int itemsPerHour,
	                            java.util.List<Item> whiteList) {
		return new LootTask(null, 0, radius, boxA, boxB, boxDim, 0, deposit, depositDim, true,
				dropMode, Math.max(0, intervalTicks), Math.max(0, itemsPerHour),
				whiteList == null ? java.util.List.of() : java.util.List.copyOf(whiteList));
	}

	/** 本任务自带的白名单（分流用）；为空表示不额外限制 */
	public boolean hasWhiteList() {
		return whiteList != null && !whiteList.isEmpty();
	}

	public boolean isBoxMode() {
		return boxA != null && boxB != null;
	}

	public boolean hasDeposit() {
		return deposit != null;
	}

	/** 搬家模式必须有目的地，否则东西没处放 */
	public boolean isValid() {
		return !moveAll || deposit != null;
	}

	public boolean useShulkers() {
		return !moveAll && shulkerThreshold > 0 && count >= shulkerThreshold;
	}

	/** 用于聊天栏显示的物品名（本地化名称，比原始 ID 更友好） */
	public String itemName() {
		if (item == null) return "全部物品";
		// 注意：Item 没有 getDescription()。取显示名的可靠方式是
		// 包一个 ItemStack 再调 getHoverName()。
		String s = new ItemStack(item).getHoverName().getString();
		return s == null || s.isEmpty() ? String.valueOf(item) : s;
	}

	public String scopeText() {
		if (isBoxMode()) {
			return "指定区域 (" + minX() + "," + minY() + "," + minZ() + ")~("
					+ maxX() + "," + maxY() + "," + maxZ() + ")";
		}
		return "半径 " + radius + " 格";
	}

	public int minX() { return Math.min(boxA.getX(), boxB.getX()); }
	public int maxX() { return Math.max(boxA.getX(), boxB.getX()); }
	public int minY() { return Math.min(boxA.getY(), boxB.getY()); }
	public int maxY() { return Math.max(boxA.getY(), boxB.getY()); }
	public int minZ() { return Math.min(boxA.getZ(), boxB.getZ()); }
	public int maxZ() { return Math.max(boxA.getZ(), boxB.getZ()); }
}
