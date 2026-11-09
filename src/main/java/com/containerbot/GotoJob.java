package com.containerbot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 单纯的「走到某个坐标」任务——不搬任何东西。
 *
 * <h3>为什么单独写</h3>
 * 现有的一切能力（取货、搬家）都依附于「搬货」这个目的。但很多场景需要单纯指挥假人跑腿：
 * 让它去某个点待命、去某个区域接应、或者只是把它挪走。
 *
 * <h3>走 vs 传送</h3>
 * <ul>
 *   <li><b>跨维度</b> → 直接传送（假人不会自己找传送门，硬走容易卡在下界门里）</li>
 *   <li><b>距离超过 {@value #TELEPORT_THRESHOLD} 格</b> → 直接传送（省时间，也避免长距离寻路算不动）</li>
 *   <li>否则 → 用 A* 走过去，走真实物理</li>
 * </ul>
 */
public class GotoJob {

	/** 超过这个距离就直接传送 */
	private static final double TELEPORT_THRESHOLD = 64.0;
	/** 卡住多少 tick 放弃 */
	private static final int STUCK_GIVEUP = 100;

	private final String name;
	private final BlockPos target;
	private final ResourceKey<Level> dim;

	private Navigator navigator;
	private boolean done;
	private boolean failed;
	private String status = "准备出发";
	private int ticks;

	public GotoJob(String name, BlockPos target, ResourceKey<Level> dim) {
		this.name = name;
		this.target = target.immutable();
		this.dim = dim;
	}

	public String getName() {
		return name;
	}

	public boolean isFinished() {
		return done || failed;
	}

	public String describe() {
		return name + "  前往 " + fmt(target)
				+ (dim != null ? "（" + dim.location() + "）" : "")
				+ "  [" + (done ? "已到达" : failed ? "失败" : "进行中") + "]  " + status;
	}

	public void tick(MinecraftServer server) {
		if (isFinished()) return;

		ServerPlayer bot = server.getPlayerList().getPlayerByName(name);
		if (bot == null || bot.isRemoved()) {
			failed = true;
			status = "假人不存在或已被移除";
			return;
		}

		ServerLevel level = (ServerLevel) bot.level();

		// 跨维度 → 直接传送
		if (dim != null && !level.dimension().equals(dim)) {
			teleport(server, dim, target);
			done = true;
			status = "跨维度，已传送到 " + dim.location();
			return;
		}

		double dist = Math.sqrt(bot.blockPosition().distSqr(target));

		// 远距离 → 直接传送（首次进入时判定）
		if (navigator == null && dist > TELEPORT_THRESHOLD) {
			teleport(server, level.dimension(), target);
			done = true;
			status = "距离 " + (int) dist + " 格，已传送";
			return;
		}

		// 已经到位
		if (dist <= 2.0) {
			done = true;
			status = "已到达";
			return;
		}

		// 近距离 → 走过去
		if (navigator == null) {
			List<BlockPos> path = PathPlanner.findPath(level, bot.blockPosition(), target, 4.0);
			if (path == null) {
				failed = true;
				status = "无法规划路径";
				return;
			}
			navigator = new Navigator(path, target);
			status = "行走中，路点 " + path.size();
		}

		navigator.tick(bot);

		if (navigator.isStuck()) {
			navigator.stop(bot);
			// 兜底：走不过去直接传送，保证命令总能达成
			teleport(server, level.dimension(), target);
			done = true;
			status = "寻路受阻，已直接传送";
			return;
		}

		if (navigator.isFinished()) {
			navigator.stop(bot);
			done = true;
			status = "已到达";
			return;
		}

		if (++ticks > STUCK_GIVEUP * 20) {
			navigator.stop(bot);
			failed = true;
			status = "超时";
		}
	}

	private void teleport(MinecraftServer server, ResourceKey<Level> to, BlockPos pos) {
		String cmd = "execute in " + to.location() + " run tp " + name
				+ " " + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5);
		try {
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withSuppressedOutput().withPermission(4), cmd);
		} catch (Throwable t) {
			ContainerBotMod.LOGGER.warn("[ContainerBot] goto 传送失败: {}", t.toString());
		}
	}

	private static String fmt(BlockPos p) {
		return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
	}
}
