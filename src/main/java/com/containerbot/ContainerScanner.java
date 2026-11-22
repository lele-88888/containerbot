package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描已加载区块中的容器方块实体。
 *
 * <p>支持两种范围：以某点为中心的半径模式，以及长方体区域模式。
 * 区域模式用于「只取指定区域的箱子」。</p>
 */
public final class ContainerScanner {

	private ContainerScanner() {}

	// ------------------------------------------------------------------ 扫描缓存

	/**
	 * 扫描结果缓存。
	 *
	 * <p>为什么可以缓存：<b>容器位置</b>不会因为搬货而改变——搬空只是内容变了，
	 * 容器方块还在。而扫描本身要遍历区域内所有区块的所有方块实体，
	 * 大区域下开销不小；假人卸完货回来又要扫一遍，命中率很高。</p>
	 *
	 * <p>30 秒过期是为了让玩家中途加/拆箱子能体现出来。真正用到某个坐标时
	 * 代码仍会 `getBlockEntity` 校验，所以即使拿到过期坐标也不会出错。</p>
	 */
	private static final long CACHE_TTL_MS = 30_000L;
	private static final int CACHE_MAX = 64;

	private record CacheEntry(List<BlockPos> positions, long at) {}

	private static final java.util.Map<String, CacheEntry> CACHE =
			new java.util.LinkedHashMap<>(16, 0.75f, true);

	private static long cacheHits;
	private static long cacheMisses;

	/** 命中 / 未命中次数（用于诊断扫描开销） */
	public static long[] cacheStats() {
		return new long[]{cacheHits, cacheMisses};
	}

	/** 清空缓存（任务开始时调用，保证每个新任务看到的是最新世界） */
	public static void invalidateAll() {
		CACHE.clear();
	}

	private static String key(ServerLevel level, int minX, int minY, int minZ,
	                          int maxX, int maxY, int maxZ) {
		return level.dimension().location() + "|" + minX + "," + minY + "," + minZ
				+ "|" + maxX + "," + maxY + "," + maxZ;
	}

	/** 半径模式：以 center 为中心、水平半径 radius 内（垂直 ±24）的容器 */
	public static List<BlockPos> scanRadius(ServerLevel level, BlockPos center, int radius) {
		int minX = center.getX() - radius;
		int maxX = center.getX() + radius;
		int minZ = center.getZ() - radius;
		int maxZ = center.getZ() + radius;
		int minY = center.getY() - 24;
		int maxY = center.getY() + 24;

		String k = key(level, minX, minY, minZ, maxX, maxY, maxZ);
		CacheEntry e = get(k);
		if (e != null) return new ArrayList<>(e.positions());

		double rSq = (double) radius * radius;
		List<BlockPos> result = new ArrayList<>();
		gather(level, minX, minZ, maxX, maxZ, minY, maxY,
				p -> {
					double dx = (p.getX() + 0.5) - (center.getX() + 0.5);
					double dz = (p.getZ() + 0.5) - (center.getZ() + 0.5);
					return dx * dx + dz * dz <= rSq;
				},
				result);
		result.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
		put(k, result);
		return result;
	}

	/**
	 * 区域模式：长方体 [a, b] 内（含边界）的容器。
	 * 两个角点顺序任意，内部会自行取 min/max。
	 */
	public static List<BlockPos> scanBox(ServerLevel level, BlockPos a, BlockPos b) {
		int minX = Math.min(a.getX(), b.getX());
		int maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY());
		int maxY = Math.max(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ());
		int maxZ = Math.max(a.getZ(), b.getZ());

		String k = key(level, minX, minY, minZ, maxX, maxY, maxZ);
		CacheEntry e = get(k);
		if (e != null) return new ArrayList<>(e.positions());

		List<BlockPos> result = new ArrayList<>();
		gather(level, minX, minZ, maxX, maxZ, minY, maxY, p -> true, result);

		BlockPos center = new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
		result.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
		put(k, result);
		return result;
	}

	private static CacheEntry get(String k) {
		CacheEntry e = CACHE.get(k);
		if (e == null) {
			cacheMisses++;
			return null;
		}
		if (System.currentTimeMillis() - e.at() > CACHE_TTL_MS) {
			CACHE.remove(k);
			cacheMisses++;
			return null;
		}
		cacheHits++;
		return e;
	}

	private static void put(String k, List<BlockPos> result) {
		if (CACHE.size() >= CACHE_MAX) {
			var it = CACHE.keySet().iterator();
			if (it.hasNext()) {
				it.next();
				it.remove();
			}
		}
		CACHE.put(k, new CacheEntry(List.copyOf(result), System.currentTimeMillis()));
	}

	// ------------------------------------------------------------------ 内部

	private interface Filter {
		boolean test(BlockPos p);
	}

	private static void gather(ServerLevel level,
	                           int minX, int minZ, int maxX, int maxZ,
	                           int minY, int maxY,
	                           Filter filter, List<BlockPos> out) {
		// 注意用 >> 4 而非 / 16：算术右移对负坐标也是向下取整，与区块编号一致
		int minChunkX = minX >> 4;
		int maxChunkX = maxX >> 4;
		int minChunkZ = minZ >> 4;
		int maxChunkZ = maxZ >> 4;

		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				LevelChunk chunk;
				try {
					chunk = level.getChunk(cx, cz);
				} catch (Exception e) {
					continue; // 区块不可用则跳过
				}
				if (chunk == null) continue;

				for (BlockEntity be : chunk.getBlockEntities().values()) {
					// 只认原版容器接口：箱子、木桶、潜影盒、漏斗、熔炉等都实现它
					if (!(be instanceof Container)) continue;

					BlockPos p = be.getBlockPos();
					if (p.getX() < minX || p.getX() > maxX) continue;
					if (p.getY() < minY || p.getY() > maxY) continue;
					if (p.getZ() < minZ || p.getZ() > maxZ) continue;
					if (!filter.test(p)) continue;

					out.add(p.immutable());
				}
			}
		}
	}
}
