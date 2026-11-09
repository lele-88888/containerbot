package com.containerbot;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * 管理所有假人任务的状态容器，并在每个服务端 tick 驱动它们。
 *
 * <p>用 {@link String#CASE_INSENSITIVE_ORDER} 作为键序，是因为 Minecraft 的玩家名
 * 本身不区分大小写。此前用 {@code LinkedHashMap} 导致
 * {@code /cbot spawn bot1} 与 {@code /cbot start Bot1} 对不上，
 * 报「没有该假人的记录」。</p>
 */
public final class BotManager {

	private static final Map<String, LootBot> BOTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	/** 单纯的「走到某处」任务，与搬货任务互斥 */
	private static final Map<String, GotoJob> GOTOS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	/**
	 * 假人分组：组名 → 成员名集合。
	 *
	 * <p>假人一多，逐个敲命令就烦。分组后可以一条命令控制一整组。</p>
	 */
	private static final Map<String, java.util.Set<String>> GROUPS =
			new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	/** 把假人加入分组（同名重复添加无副作用） */
	public static void tag(String botName, String group) {
		GROUPS.computeIfAbsent(group, k -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER))
				.add(botName);
	}

	/** 把假人移出分组；返回是否确实移除了 */
	public static boolean untag(String botName, String group) {
		java.util.Set<String> members = GROUPS.get(group);
		if (members == null) return false;
		boolean removed = members.remove(botName);
		if (members.isEmpty()) GROUPS.remove(group);
		return removed;
	}

	/** 组成员（可能为空集） */
	public static java.util.Set<String> membersOf(String group) {
		return GROUPS.getOrDefault(group, java.util.Set.of());
	}

	/** 某个假人所属的所有分组 */
	public static java.util.Set<String> groupsOf(String botName) {
		java.util.Set<String> out = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (var e : GROUPS.entrySet()) {
			if (e.getValue().contains(botName)) out.add(e.getKey());
		}
		return out;
	}

	/** 全部分组（组名 → 成员） */
	public static Map<String, java.util.Set<String>> allGroups() {
		return GROUPS;
	}

	private BotManager() {}

	public static void startGoto(GotoJob job) {
		GOTOS.put(job.getName(), job);
	}

	public static GotoJob getGoto(String name) {
		return GOTOS.get(name);
	}

	public static Collection<GotoJob> allGotos() {
		return GOTOS.values();
	}

	public static void cancelGoto(String name) {
		GOTOS.remove(name);
	}

	public static LootBot getOrCreate(String name) {
		return BOTS.computeIfAbsent(name, LootBot::new);
	}

	public static LootBot get(String name) {
		return BOTS.get(name);
	}

	public static Collection<LootBot> all() {
		return BOTS.values();
	}

	public static void remove(String name) {
		// 假人被移除时必须归还它持有的容器认领，
		// 否则那些箱子会被永久视为"有人在搬"，其他假人永远跳过。
		ContainerClaims.releaseAll(name);
		BOTS.remove(name);
	}

	/** 由 ServerTickEvents.END_SERVER_TICK 调用 */
	public static void tick(MinecraftServer server) {
		// 「走到某处」任务
		if (!GOTOS.isEmpty()) {
			for (GotoJob g : new ArrayList<>(GOTOS.values())) {
				try {
					g.tick(server);
				} catch (Exception e) {
					ContainerBotMod.LOGGER.error("[ContainerBot] goto {} 抛出异常", g.getName(), e);
					GOTOS.remove(g.getName());
				}
			}
		}

		if (BOTS.isEmpty()) return;
		// 复制一份，避免任务内部增删导致并发修改
		for (LootBot bot : new ArrayList<>(BOTS.values())) {
			try {
				bot.tick(server);
			} catch (Exception e) {
				ContainerBotMod.LOGGER.error("[ContainerBot] 任务 {} 抛出异常", bot.getName(), e);
				// 带上异常详情，否则玩家只看到「内部异常: NullPointerException」，无从下手
				String detail = e.getClass().getSimpleName()
						+ (e.getMessage() != null ? " - " + e.getMessage() : "");
				bot.fail("内部异常: " + detail);
			}
		}
	}
}
