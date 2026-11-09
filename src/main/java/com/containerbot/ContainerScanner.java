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

	/** 半径模式：以 center 为中心、水平半径 radius 内（垂直 ±24）的容器 */
	public static List<BlockPos> scanRadius(ServerLevel level, BlockPos center, int radius) {
		double rSq = (double) radius * radius;
		List<BlockPos> result = new ArrayList<>();
		gather(level,
				center.getX() - radius, center.getZ() - radius,
				center.getX() + radius, center.getZ() + radius,
				center.getY() - 24, center.getY() + 24,
				p -> {
					double dx = (p.getX() + 0.5) - (center.getX() + 0.5);
					double dz = (p.getZ() + 0.5) - (center.getZ() + 0.5);
					return dx * dx + dz * dz <= rSq;
				},
				result);
		result.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
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

		List<BlockPos> result = new ArrayList<>();
		gather(level, minX, minZ, maxX, maxZ, minY, maxY, p -> true, result);

		BlockPos center = new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
		result.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
		return result;
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
