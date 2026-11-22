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

	// ---------------- 音效 ----------------

	/** 音效总开关（默认开） */
	private static volatile boolean soundEnabled = true;

	public static boolean isSoundEnabled() {
		return soundEnabled;
	}

	public static void setSoundEnabled(boolean v) {
		soundEnabled = v;
	}

	// 注意：1.21 里部分音效是 Holder<SoundEvent>（如音符盒），部分是裸 SoundEvent。
	// 用错会造成 "找不到符号 value()" 之类的编译错误。
	private static final net.minecraft.sounds.SoundEvent SND_DONE =
			net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value();
	private static final net.minecraft.sounds.SoundEvent SND_FAIL =
			net.minecraft.sounds.SoundEvents.VILLAGER_NO;
	private static final net.minecraft.sounds.SoundEvent SND_FULL =
			net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value();
	private static final net.minecraft.sounds.SoundEvent SND_STUCK =
			net.minecraft.sounds.SoundEvents.ANVIL_LAND;

	private Notifier() {}

	/**
	 * 给所有在线玩家放音效。
	 *
	 * <p>做法是「在每个玩家自己的位置放一次」——这样无论人在哪都听得见，
	 * 挂机时人往往不在假人旁边，按距离衰减就会漏掉。</p>
	 */
	private static void playForAll(MinecraftServer server,
	                               net.minecraft.sounds.SoundEvent sound,
	                               float volume, float pitch, int times) {
		if (server == null || !soundEnabled || sound == null) return;
		try {
			var players = server.getPlayerList().getPlayers();
			for (int k = 0; k < times; k++) {
				for (var p : players) {
					p.level().playSound(null, p.getX(), p.getY(), p.getZ(),
							sound, net.minecraft.sounds.SoundSource.MASTER, volume, pitch);
				}
			}
		} catch (Throwable ignored) {
			// 音效失败绝不能影响任务
		}
	}

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
		// 完成音：清脆一声，不吵
		playForAll(server, SND_DONE, 1.0f, 1.2f, 1);
		String tip = queuedMore ? "，继续执行下一个任务…" : "";
		if (task.moveAll()) {
			success(server, "✔ " + botName + " 搬家完成：共搬走 " + have + " 件物品（区域内容器已全部掏空）" + tip);
			return;
		}
		success(server, "✔ " + botName + " 完成：已收集 " + have + "/" + task.count() + " 个 "
				+ task.itemName() + tip);
	}

	/** 失败：数量不足 */
	public static void taskShort(MinecraftServer server, String botName, LootTask task, int have, int searched) {
		// 数量不足属于"没干完"，用警示音
		playForAll(server, SND_FAIL, 1.0f, 0.9f, 2);
		warn(server, "! " + botName + " 结束：范围内容器已搜遍（共 " + searched + " 个），"
				+ task.itemName() + " 只找到 " + have + "/" + task.count()
				+ "，还差 " + (task.count() - have) + " 个");
	}

	/**
	 * 失败：其它原因。
	 *
	 * <p>按原因选不同音效——挂机时听声音就能判断是「要你去加箱子」还是「出故障了」。</p>
	 */
	public static void taskFailed(MinecraftServer server, String botName, String reason) {
		String r = reason == null ? "" : reason;
		if (r.contains("目的地") && (r.contains("满") || r.contains("倒"))) {
			// 目的地不足：钟声连响三下，最需要人工介入的一类
			playForAll(server, SND_FULL, 1.2f, 1.4f, 3);
			error(server, "✖ " + botName + " 目的地不足：" + r);
			return;
		}
		if (r.contains("无法走到") || r.contains("卡") || r.contains("超时")) {
			playForAll(server, SND_STUCK, 1.2f, 0.9f, 1);
			error(server, "✖ " + botName + " 卡住了：" + r);
			return;
		}
		playForAll(server, SND_FAIL, 1.0f, 0.9f, 2);
		error(server, "✖ " + botName + " 任务失败：" + r);
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
