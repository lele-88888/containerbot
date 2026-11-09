package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 简易 A* 寻路。
 *
 * <p>为什么不用原版 {@code WalkNodeEvaluator}：它强制要求传入一个 {@code Mob}，
 * 而 Carpet 假人本质是 {@code ServerPlayer}（玩家），不是生物，套不进去。
 * 因此这里直接基于方块碰撞形状自行搜索：可控、无版本风险、不需要伪造生物实体。</p>
 *
 * <p>节点 = 方块坐标（代表「脚所在的那一格」），移动代价考虑平走 / 上台阶 / 下落。</p>
 */
public final class PathPlanner {

	/** 水平方向：4 正向 + 4 斜向 */
	private static final int[][] DIRS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	/** 允许的最大下落高度 */
	private static final int MAX_FALL = 4;
	/** 搜索节点上限，防止在无解地形上耗尽时间 */
	private static final int MAX_NODES = 30000;

	private PathPlanner() {}

	/**
	 * 寻路到目标容器附近。
	 *
	 * @param level   所在世界
	 * @param start   起点（脚部方块坐标）
	 * @param goal    目标容器坐标
	 * @param reachSq 判定「已到达」的水平距离平方阈值
	 * @return 路点列表（不含起点）；失败返回 null
	 */
	public static List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos goal, double reachSq) {
		if (!canStandAt(level, start)) {
			// 起点被卡住时，尝试向上找一格可站立的位置
			BlockPos up = start.above();
			if (canStandAt(level, up)) {
				start = up;
			} else {
				return null;
			}
		}

		Map<BlockPos, Double> gScore = new HashMap<>();
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		PriorityQueue<Entry> open = new PriorityQueue<>();

		gScore.put(start, 0.0);
		open.add(new Entry(start, heuristic(start, goal)));

		int expanded = 0;

		while (!open.isEmpty()) {
			Entry cur = open.poll();
			BlockPos curPos = cur.pos();

			if (!closed.add(curPos)) continue;
			if (++expanded > MAX_NODES) break;

			if (reached(curPos, goal, reachSq)) {
				return reconstruct(cameFrom, curPos);
			}

			double curG = gScore.getOrDefault(curPos, Double.MAX_VALUE);

			for (int[] d : DIRS) {
				BlockPos next = resolveMove(level, curPos, d[0], d[1]);
				if (next == null || closed.contains(next)) continue;

				double step = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
				int dy = next.getY() - curPos.getY();
				// 上台阶 / 下落附加代价，让路径更偏好平地
				if (dy > 0) {
					step += 1.5;
				} else if (dy < 0) {
					step += 0.4 * (-dy);
				}

				double tentative = curG + step;
				if (tentative < gScore.getOrDefault(next, Double.MAX_VALUE)) {
					gScore.put(next, tentative);
					cameFrom.put(next, curPos);
					open.add(new Entry(next, tentative + heuristic(next, goal)));
				}
			}
		}
		return null;
	}

	/** 判断某坐标是否可作为落脚点：脚下实心、身位与头部可通行 */
	public static boolean canStandAt(ServerLevel level, BlockPos p) {
		return isPassable(level, p) && isPassable(level, p.above()) && isSolid(level, p.below());
	}

	public static boolean isPassable(ServerLevel level, BlockPos p) {
		BlockState state = level.getBlockState(p);
		VoxelShape shape = state.getCollisionShape(level, p);
		return shape.isEmpty();
	}

	public static boolean isSolid(ServerLevel level, BlockPos p) {
		BlockState state = level.getBlockState(p);
		return !state.getCollisionShape(level, p).isEmpty();
	}

	/** 尝试从 from 沿 (dx,dz) 移动，返回实际落脚点；不可达返回 null。 */
	private static BlockPos resolveMove(ServerLevel level, BlockPos from, int dx, int dz) {
		// 斜向移动禁止穿墙角
		if (dx != 0 && dz != 0) {
			if (!isPassable(level, new BlockPos(from.getX() + dx, from.getY(), from.getZ()))) return null;
			if (!isPassable(level, new BlockPos(from.getX(), from.getY(), from.getZ() + dz))) return null;
		}

		int nx = from.getX() + dx;
		int nz = from.getZ() + dz;

		// 优先同层，其次上台阶，最后向下落
		BlockPos same = new BlockPos(nx, from.getY(), nz);
		if (canStandAt(level, same)) return same;

		BlockPos up = new BlockPos(nx, from.getY() + 1, nz);
		if (canStandAt(level, up)) return up;

		for (int dy = 1; dy <= MAX_FALL; dy++) {
			BlockPos cand = new BlockPos(nx, from.getY() - dy, nz);
			if (canStandAt(level, cand)) return cand;
			// 落点上方被实心方块堵住，则不可能继续下落
			if (isSolid(level, cand)) return null;
		}
		return null;
	}

	private static boolean reached(BlockPos p, BlockPos goal, double reachSq) {
		double dx = (p.getX() + 0.5) - (goal.getX() + 0.5);
		double dz = (p.getZ() + 0.5) - (goal.getZ() + 0.5);
		double dy = p.getY() - goal.getY();
		return (dx * dx + dz * dz) <= reachSq && Math.abs(dy) <= 2;
	}

	private static double heuristic(BlockPos p, BlockPos goal) {
		double dx = p.getX() - goal.getX();
		double dy = p.getY() - goal.getY();
		double dz = p.getZ() - goal.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
		List<BlockPos> path = new ArrayList<>();
		BlockPos cur = end;
		while (cur != null) {
			path.add(cur);
			cur = cameFrom.get(cur);
		}
		Collections.reverse(path);
		if (!path.isEmpty()) path.remove(0); // 去掉起点本身
		return path;
	}

	private record Entry(BlockPos pos, double f) implements Comparable<Entry> {
		@Override
		public int compareTo(Entry o) {
			return Double.compare(this.f, o.f());
		}
	}
}
