package com.containerbot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * 通过反射驱动 Carpet 的 {@code EntityPlayerActionPack}，从而让假人真正走动。
 *
 * <h3>为什么要用 Carpet 的动作包，而不是直接写 zza/xxa</h3>
 * 原版服务端玩家并不根据输入字段自行模拟位移——真实玩家是客户端算好位置再上报，
 * 服务端只做校验。直接写 {@code zza} 不保证产生位移。
 * Carpet 的动作包才是「驱动假人」的正道，它也是 {@code /player <名字> move forward}
 * 这类指令背后的实现，经过大量实战检验。
 *
 * <h3>为什么用反射而不是直接依赖</h3>
 * 玩家实例里装的是 Carpet 的 <b>1.21.7 分支</b>构建（{@code v1.4.177}），
 * 其内部 API 与 master 并不一致。编译期硬依赖会把模组锁死在某个 Carpet 版本上；
 * 反射只在运行期解析一次方法句柄，解析不到就优雅降级到写原版输入字段。
 *
 * <h3>已由 javap 确认的 API（carpet-1.21.7-1.4.177+v250630）</h3>
 * <pre>
 * carpet.fakes.ServerPlayerInterface#getActionPack() → carpet.helpers.EntityPlayerActionPack
 *   EntityPlayerActionPack#setForward(float)
 *   EntityPlayerActionPack#setStrafing(float)
 *   EntityPlayerActionPack#look(float yaw, float pitch)
 *   EntityPlayerActionPack#lookAt(net.minecraft.world.phys.Vec3)
 *   EntityPlayerActionPack#stopMovement()
 * </pre>
 */
public final class CarpetBridge {

	/** 解析结果：true 表示 Carpet 动作包可用 */
	private static boolean available;
	private static boolean resolved;

	private static Method mGetActionPack;
	private static Method mSetForward;
	private static Method mSetStrafing;
	private static Method mLook;
	private static Method mLookAt;
	private static Method mStopMovement;

	private CarpetBridge() {}

	/** 上次解析的结果描述，用于诊断输出 */
	private static String statusText = "尚未解析";

	/** 惰性解析一次；失败则永久降级，避免每 tick 反复尝试 */
	private static synchronized void resolve() {
		if (resolved) return;
		resolved = true;

		// 逐项解析并记录缺失项。
		//
		// Carpet 升级时内部 API 可能改名/改签名——本模组只反射这 6 个成员，
		// 一旦对不上，这里会明确列出"缺哪个"，不必再去猜。
		StringBuilder missing = new StringBuilder();

		try {
			Class<?> iface = Class.forName("carpet.fakes.ServerPlayerInterface");
			Class<?> pack = Class.forName("carpet.helpers.EntityPlayerActionPack");

			mGetActionPack = pick(iface, missing, "getActionPack");
			mSetForward    = pick(pack, missing, "setForward", float.class);
			mSetStrafing   = pick(pack, missing, "setStrafing", float.class);
			mLook          = pick(pack, missing, "look", float.class, float.class);
			mLookAt        = pick(pack, missing, "lookAt", Vec3.class);
			mStopMovement  = pick(pack, missing, "stopMovement");

			// 移动所需的最低要求；look/lookAt 缺失时由调用方各自降级
			available = mGetActionPack != null && mSetForward != null && mSetStrafing != null;

			if (available) {
				statusText = "可用" + (missing.length() == 0 ? "（全部成员解析成功）"
						: "（部分成员缺失: " + missing + "，相关动作会降级）");
				ContainerBotMod.LOGGER.info("[ContainerBot] 已接入 Carpet 动作包，假人将使用真实移动。{}",
						statusText);
			} else {
				statusText = "不可用（缺失: " + missing + "）";
				ContainerBotMod.LOGGER.warn(
						"[ContainerBot] Carpet 动作包不可用 —— {}。已降级为直接写原版输入字段。"
								+ "若假人不移动或转向异常，很可能是 Carpet 版本变了、内部 API 对不上，"
								+ "请把本行完整内容反馈。", statusText);
			}
		} catch (Throwable t) {
			available = false;
			statusText = "不可用（" + t.getClass().getSimpleName() + ": " + t.getMessage() + "）";
			ContainerBotMod.LOGGER.warn(
					"[ContainerBot] Carpet 动作包不可用 —— {}。已降级为直接写原版输入字段。"
							+ "请确认已安装 Carpet，以及版本是否与假人的操作方式兼容。", statusText);
		}
	}

	/** 反射取一个方法；失败就记下名字并返回 null（不影响其它成员） */
	private static Method pick(Class<?> owner, StringBuilder missing, String name, Class<?>... params) {
		try {
			return owner.getMethod(name, params);
		} catch (Throwable t) {
			if (missing.length() > 0) missing.append(", ");
			missing.append(name);
			Diag.trace(null, "CarpetBridge", "反射失败 " + owner.getSimpleName() + "#" + name);
			return null;
		}
	}

	/** 供诊断命令输出当前 Carpet 接入状态 */
	public static String describeStatus() {
		resolve();
		return statusText;
	}

	/** Carpet 动作包是否可用 */
	public static boolean isAvailable() {
		resolve();
		return available;
	}

	private static Object pack(ServerPlayer player) {
		try {
			return mGetActionPack.invoke(player);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 设置前进/横移输入。二者都是 -1.0 ~ 1.0。
	 *
	 * @return true 表示成功交给了 Carpet 动作包；false 表示调用方应自行降级处理
	 */
	public static boolean move(ServerPlayer player, float forward, float strafe) {
		if (!isAvailable()) return false;
		Object p = pack(player);
		if (p == null) return false;
		try {
			mSetForward.invoke(p, forward);
			mSetStrafing.invoke(p, strafe);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 让假人看向指定角度。Carpet 的 look 是增量转向，故此处直接调用 */
	public static boolean look(ServerPlayer player, float yaw, float pitch) {
		if (!isAvailable()) return false;
		Object p = pack(player);
		if (p == null) return false;
		try {
			mLook.invoke(p, yaw, pitch);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 让假人看向空间中的某一点（用于开箱前对准容器） */
	public static boolean lookAt(ServerPlayer player, Vec3 target) {
		if (!isAvailable()) return false;
		Object p = pack(player);
		if (p == null) return false;
		try {
			mLookAt.invoke(p, target);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * 停止一切移动输入。
	 *
	 * <p>注意：只调用 {@code stopMovement()} 是<b>不够</b>的——它停的是「动作列表」，
	 * 而 {@code setForward()} 设置的前进输入是独立的持续状态。实测只调
	 * {@code stopMovement()} 后，假人在任务完成后仍会一直往前走。
	 * 因此这里显式把前进/横移归零。</p>
	 */
	public static boolean stop(ServerPlayer player) {
		if (!isAvailable()) return false;
		Object p = pack(player);
		if (p == null) return false;
		try {
			mSetForward.invoke(p, 0.0f);
			mSetStrafing.invoke(p, 0.0f);
			mStopMovement.invoke(p);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
