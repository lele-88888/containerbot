package com.containerbot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 诊断追踪设施。
 *
 * <h3>为什么要有这个类</h3>
 * 跨维度投送那个 bug 我改了 6 个版本都没修好，因为<b>每一轮都是在没有数据的情况下
 * 猜原因</b>——猜"是不是位置没同步"，就加冷却；猜"是不是占位不对"，就改传送落点。
 * 结果 6 次全部落空。
 *
 * <p>第 7 轮改成先加诊断输出，一次就拿到了决定性信息（原版把右键判定成了
 * 「物品交互」而非「打开容器」）。教训很明确：<b>修 bug 必须先有观测手段</b>。</p>
 *
 * <p>所以把这个手段做成常驻设施：状态迁移、关键决策、外部调用结果都可以按开关输出，
 * 不需要每次改代码临时加 <code>System.out</code>。</p>
 *
 * <h3>用法</h3>
 * <pre>
 *   /cbot debug on      开启（默认关闭）
 *   /cbot debug off     关闭
 *   /cbot debug         查看当前状态
 * </pre>
 */
public final class Diag {

	/** 全局开关。开启后所有 trace/transition 才会实际输出 */
	private static volatile boolean enabled = false;

	/** 保留最近若干条诊断，便于事后用 /cbot debug dump 查看 */
	private static final int RING_SIZE = 200;
	private static final Deque<String> RING = new ArrayDeque<>();

	private Diag() {}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	/** 记录一条诊断：进环形缓冲 + 控制台日志 + （开启时）聊天栏 */
	public static void trace(MinecraftServer server, String bot, String msg) {
		String line = "[" + bot + "] " + msg;
		synchronized (RING) {
			RING.addLast(line);
			while (RING.size() > RING_SIZE) {
				RING.removeFirst();
			}
		}
		if (!enabled) return;

		ContainerBotMod.LOGGER.info("[ContainerBot][诊断] {}", line);
		if (server != null) {
			try {
				server.getPlayerList().broadcastSystemMessage(
						Component.literal("[诊断] " + line).withStyle(ChatFormatting.GRAY), false);
			} catch (Throwable ignored) {
				// 诊断输出失败绝不影响任务
			}
		}
	}

	/** 状态迁移追踪——这是最常用的一类 */
	public static void transition(MinecraftServer server, String bot, String from, String to, String why) {
		trace(server, bot, "状态 " + from + " -> " + to + "  原因: " + why);
	}

	/** 记录一次外部调用的结果（反射、原版 API 等），失败可观测 */
	public static void call(MinecraftServer server, String bot, String what, Object result) {
		trace(server, bot, "调用 " + what + " 返回 " + String.valueOf(result));
	}

	/** 把环形缓冲里的诊断发回给玩家，便于排查历史 */
	public static int dump(MinecraftServer server, ServerPlayer player) {
		if (server == null) return 0;
		java.util.List<String> copy;
		synchronized (RING) {
			copy = new java.util.ArrayList<>(RING);
		}
		if (copy.isEmpty()) {
			server.sendSystemMessage(Component.literal("暂无诊断记录"));
			return 0;
		}
		server.sendSystemMessage(Component.literal("=== 最近 " + copy.size() + " 条诊断 ==="));
		for (String line : copy) {
			server.sendSystemMessage(Component.literal(line));
		}
		return copy.size();
	}
}
