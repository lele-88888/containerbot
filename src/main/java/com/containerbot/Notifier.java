package com.containerbot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * 任务消息通知。
 *
 * <p>设计取向：只报「开始 / 完成 / 失败」三个关键节点，外加进度百分比；
 * 过程中的每一步都刷屏反而会淹没真正重要的信息。</p>
 *
 * <p>消息会同时发给：
 * <ul>
 *   <li>所有在线玩家（谁能看见就通知谁）</li>
 *   <li>服务端控制台（便于无人值守时查日志）</li>
 * </ul>
 */
public final class Notifier {

	private Notifier() {}

	public static void info(MinecraftServer server, String text) {
		send(server, Component.literal("[ContainerBot] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
	}

	public static void success(MinecraftServer server, String text) {
		send(server, Component.literal("[ContainerBot] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.GREEN)));
	}

	public static void warn(MinecraftServer server, String text) {
		send(server, Component.literal("[ContainerBot] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
	}

	public static void error(MinecraftServer server, String text) {
		send(server, Component.literal("[ContainerBot] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.RED)));
	}

	/** 开始任务 */
	public static void taskStarted(MinecraftServer server, String botName, LootTask task, int queued) {
		String suffix = queued > 0 ? "（队列中还有 " + queued + " 个任务）" : "";
		if (task.moveAll()) {
			info(server, botName + " 开始搬家：" + task.scopeText() + " 的所有容器 → "
					+ task.deposit().toShortString()
					+ (task.depositDim() != null ? "（" + task.depositDim().location() + "）" : "")
					+ suffix);
			return;
		}
		info(server, botName + " 开始任务：收集 " + task.count() + " 个 " + task.itemName()
				+ "，范围 " + task.scopeText()
				+ (task.useShulkers() ? "，启用潜影盒整盒搬运" : "")
				+ suffix);
	}

	/** 进度（按百分比节流，避免刷屏） */
	public static void taskProgress(MinecraftServer server, String botName, LootTask task, int have) {
		if (task.moveAll()) {
			// 搬家模式没有目标数量，报「已搬 N 件」更有意义
			info(server, botName + " 已搬 " + have + " 件");
			return;
		}
		int pct = task.count() <= 0 ? 100 : (int) Math.min(100, have * 100L / task.count());
		info(server, botName + " 进度 " + pct + "%（" + have + "/" + task.count() + " " + task.itemName() + "）");
	}

	/** 完成 */
	public static void taskDone(MinecraftServer server, String botName, LootTask task, int have, boolean queuedMore) {
		if (task.moveAll()) {
			success(server, botName + " 搬家完成：共搬走 " + have + " 件物品（区域内容器已全部掏空）"
					+ (queuedMore ? "，继续执行下一个任务…" : ""));
			return;
		}
		success(server, botName + " 完成：已收集 " + have + "/" + task.count() + " 个 " + task.itemName()
				+ (queuedMore ? "，继续执行下一个任务…" : ""));
	}

	/** 失败：数量不足 */
	public static void taskShort(MinecraftServer server, String botName, LootTask task, int have, int searched) {
		warn(server, botName + " 结束：范围内容器已搜遍（共 " + searched + " 个），"
				+ task.itemName() + " 只找到 " + have + "/" + task.count()
				+ "，还差 " + (task.count() - have) + " 个");
	}

	/** 失败：其它原因 */
	public static void taskFailed(MinecraftServer server, String botName, String reason) {
		error(server, botName + " 任务失败：" + reason);
	}

	private static void send(MinecraftServer server, Component msg) {
		if (server == null) return;
		try {
			// 只调用 broadcastSystemMessage 即可：
			// vanilla 的实现在发给所有玩家之前，内部已经调用
			// MinecraftServer.sendSystemMessage 写了一份控制台日志。
			// 之前两个都调，导致每条消息在日志里出现两遍。
			server.getPlayerList().broadcastSystemMessage(msg, false);
		} catch (Throwable t) {
			// 广播失败时至少保证控制台可见
			try {
				server.sendSystemMessage(msg);
			} catch (Throwable ignored) {
				// 通知失败绝不影响任务本身
			}
		}
	}
}
