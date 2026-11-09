package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 沿路点驱动假人行走。
 *
 * <p>原理：{@code ServerPlayer} 每 tick 的 {@code travel()} 会消费 {@code zza}（前进）
 * 与 {@code xxa}（横移）这两个输入字段，前进方向由 {@code yRot} 决定。
 * 正常玩家的这两个字段由客户端按键经网络包写入；假人没有客户端，
 * 因此这里直接写入，效果等价于「持续按住前进键」——走的是真实物理，而非瞬移。</p>
 */
public class Navigator {

	/** 连续多少 tick 无位移判定为卡住（20 tick = 1 秒） */
	private static final int STUCK_LIMIT = 60;
	/** 单次行走的最长 tick 数，超时即放弃（交由上层兜底） */
	private static final int MAX_TOTAL_TICKS = 20 * 90;

	private final List<BlockPos> path;
	private final BlockPos goal;

	private int index;
	private int stuckTicks;
	private int totalTicks;
	private BlockPos lastPos;

	private boolean finished;
	private boolean stuck;

	public Navigator(List<BlockPos> path, BlockPos goal) {
		this.path = path;
		this.goal = goal;
	}

	/**
	 * 驱动一 tick。
	 *
	 * @return true 表示本轮行走已结束（到达、卡住或超时），需由调用方检查
	 *         {@link #isStuck()} 区分结果
	 */
	public boolean tick(ServerPlayer bot) {
		if (finished || stuck) return true;

		if (++totalTicks > MAX_TOTAL_TICKS) {
			stuck = true;
			return true;
		}
		if (path.isEmpty() || index >= path.size()) {
			finished = true;
			return true;
		}

		BlockPos wp = path.get(index);
		Vec3 target = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
		Vec3 pos = bot.position();

		double dx = target.x - pos.x;
		double dz = target.z - pos.z;
		double dy = target.y - pos.y;
		double horiz = Math.sqrt(dx * dx + dz * dz);

		// 接近当前路点则推进到下一个
		if (horiz < 0.6 && Math.abs(dy) < 1.5) {
			index++;
			stuckTicks = 0;
			lastPos = null;
			if (index >= path.size()) {
				finished = true;
				return true;
			}
			return false;
		}

		// 转向目标：yaw 0 = +Z，逆时针为正。
		// 直接设置朝向是必要的：Player.travel() 依赖 yRot 决定前进方向。
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(0.0f);

		// 写入行进输入。服务端玩家不会自行根据输入字段模拟位移，
		// 因此优先交给 Carpet 的动作包（这是 /player move 背后的机制）；
		// 只有在 Carpet 缺失时才降级为直接写原版字段。
		if (!CarpetBridge.move(bot, 1.0f, 0.0f)) {
			bot.zza = 1.0f;
			bot.xxa = 0.0f;
		}

		// 需要抬升时跳一下（原版玩家跨 1 格台阶必须起跳）
		if (dy > 0.5 && bot.onGround()) {
			Vec3 v = bot.getDeltaMovement();
			bot.setDeltaMovement(v.x, 0.42, v.z);
		}

		// 卡住检测
		BlockPos cur = bot.blockPosition();
		if (lastPos != null && lastPos.equals(cur)) {
			if (++stuckTicks > STUCK_LIMIT) {
				stuck = true;
				return true;
			}
		} else {
			stuckTicks = 0;
			lastPos = cur;
		}
		return false;
	}

	/**
	 * 松开「按键」，避免行走结束后假人继续走。
	 *
	 * <p>实测教训：Carpet 动作包里的 {@code setForward} 是<b>持续状态</b>，
	 * 只把原版 {@code zza}/{@code xxa} 归零并不能让它停下——任务完成后假人
	 * 仍会沿原方向一直走出去。所以必须同时停掉动作包。</p>
	 */
	public void stop(ServerPlayer bot) {
		CarpetBridge.stop(bot);
		bot.zza = 0.0f;
		bot.xxa = 0.0f;
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isStuck() {
		return stuck;
	}

	public BlockPos getGoal() {
		return goal;
	}

	public int getProgress() {
		return index;
	}

	public int getLength() {
		return path.size();
	}
}
