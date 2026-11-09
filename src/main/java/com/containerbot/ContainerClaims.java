package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 全局「容器认领表」——防止多个假人抢同一个箱子。
 *
 * <h3>为什么需要</h3>
 * 每个 {@link LootBot} 原本只维护自己的 {@code visited} 集合。两个假人在同一片区域工作时，
 * 会各自选中<b>同一个</b>最近的容器：先到的把它搬空，后到的白跑一趟，效率直接腰斩。
 *
 * <h3>做法</h3>
 * 假人决定前往某个容器时先来认领；认领失败（已被别人占）就跳过，继续找下一个。
 * 认领在以下时机释放：
 * <ul>
 *   <li>该容器被搬完 / 确认无货（加入 visited 时）</li>
 *   <li>任务结束、被清空、假人被移除</li>
 * </ul>
 *
 * <h3>为什么 key 要带维度</h3>
 * 同一个坐标在主世界、下界、末地都存在。若只用 {@code BlockPos} 做 key，
 * 下界的箱子和主世界同坐标的箱子会被误认为同一个。
 */
public final class ContainerClaims {

	/** 维度 + 坐标 构成唯一 key */
	private record Key(ResourceKey<Level> dim, BlockPos pos) {}

	/** 容器 → 认领它的假人名 */
	private static final Map<Key, String> CLAIMS = new HashMap<>();

	private ContainerClaims() {}

	/**
	 * 尝试认领一个容器。
	 *
	 * @return true 表示可以前往（本次认领成功，或原本就属于自己）
	 */
	public static synchronized boolean claim(ResourceKey<Level> dim, BlockPos pos, String botName) {
		Key k = new Key(dim, pos.immutable());
		String owner = CLAIMS.get(k);
		if (owner == null || owner.equalsIgnoreCase(botName)) {
			CLAIMS.put(k, botName);
			return true;
		}
		return false;
	}

	/** 该容器是否已被【别的】假人认领 */
	public static synchronized boolean isClaimedByOther(ResourceKey<Level> dim, BlockPos pos, String botName) {
		String owner = CLAIMS.get(new Key(dim, pos));
		return owner != null && !owner.equalsIgnoreCase(botName);
	}

	/** 释放单个容器（只有持有者能释放，避免误删别人的） */
	public static synchronized void release(ResourceKey<Level> dim, BlockPos pos, String botName) {
		Key k = new Key(dim, pos);
		String owner = CLAIMS.get(k);
		if (owner != null && owner.equalsIgnoreCase(botName)) {
			CLAIMS.remove(k);
		}
	}

	/**
	 * 释放某个假人持有的全部认领。
	 *
	 * <p>任务开始与结束时都调用一次：开始时清掉上一轮可能残留的认领，
	 * 结束时把没走完的流程（中途失败、被清空）一并归还。</p>
	 */
	public static synchronized void releaseAll(String botName) {
		Iterator<Map.Entry<Key, String>> it = CLAIMS.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getValue().equalsIgnoreCase(botName)) {
				it.remove();
			}
		}
	}

	/** 当前被认领的容器数量（诊断用） */
	public static synchronized int size() {
		return CLAIMS.size();
	}

	/** 谁认领了这个容器（诊断用，无主返回 null） */
	public static synchronized String ownerOf(ResourceKey<Level> dim, BlockPos pos) {
		return CLAIMS.get(new Key(dim, pos));
	}
}
