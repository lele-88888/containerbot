package com.containerbot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物品名解析：支持 <b>资源 ID</b> 与 <b>中文名</b> 两种写法。
 *
 * <h3>为什么需要</h3>
 * Minecraft 的 {@code ResourceLocationArgument} 只认 {@code [a-z0-9_.-:/]}，
 * <b>汉字在第一个字就被拒绝</b>，命令根本走不到我们代码里。所以凡是需要输入物品名的
 * 地方，改用能接收任意文本的参数类型，再靠本类把「钻石」这类中文名反查成
 * {@code minecraft:diamond}。
 *
 * <h3>匹配优先级</h3>
 * <ol>
 *   <li>完整资源 ID：{@code minecraft:diamond}</li>
 *   <li>省略命名空间的 ID：{@code diamond}</li>
 *   <li>本地化名<b>完全相等</b>：{@code 钻石}</li>
 *   <li>本地化名<b>包含</b>输入：{@code 钻石} → 也会匹配「钻石块」这类更长的名字，
 *       但只返回完全相等的那一个；没有完全相等时才用包含匹配</li>
 * </ol>
 *
 * <p>本地化名取决于服务端语言（Carpet 的 {@code language} 设置），中文环境即为中文名。</p>
 */
public final class ItemNames {

	/** 本地化名（小写） → 物品 ID 列表（可能有重名，例如不同颜色的床） */
	private static Map<String, List<ResourceLocation>> byName;

	private ItemNames() {}

	/** 惰性构建反查表（约 1400 个物品，一次即可） */
	private static synchronized Map<String, List<ResourceLocation>> table() {
		if (byName != null) return byName;

		Map<String, List<ResourceLocation>> map = new HashMap<>();
		for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item == null || item == Items.AIR) continue;

			String name;
			try {
				// Item 没有 getDescription()，包一层 ItemStack 取显示名
				name = new ItemStack(item).getHoverName().getString();
			} catch (Throwable t) {
				continue;
			}
			if (name == null || name.isBlank()) continue;

			map.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
		}
		byName = map;
		return map;
	}

	/** 解析结果：要么成功拿到物品，要么带上"为什么失败"的说明 */
	public record Result(Item item, String error) {
		public boolean ok() {
			return item != null;
		}
	}

	/**
	 * 解析一个物品名（ID 或中文名）。
	 *
	 * @param raw 用户输入，例如 {@code minecraft:diamond} / {@code diamond} / {@code 钻石}
	 */
	public static Result resolve(String raw) {
		if (raw == null) return new Result(null, "物品名为空");
		String s = raw.trim();
		if (s.isEmpty()) return new Result(null, "物品名为空");

		// 1) 资源 ID（含省略命名空间的写法）
		ResourceLocation id = ResourceLocation.tryParse(s.indexOf(':') >= 0 ? s : "minecraft:" + s);
		if (id != null) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item != null && item != Items.AIR) {
				return new Result(item, null);
			}
		}
		// 纯数字/含大写等非法 ID 形式也可能走到这里，继续按名字找

		String key = s.toLowerCase(Locale.ROOT);

		// 2) 本地化名完全相等
		List<ResourceLocation> exact = table().get(key);
		if (exact != null && exact.size() == 1) {
			return new Result(BuiltInRegistries.ITEM.getValue(exact.get(0)), null);
		}
		if (exact != null && exact.size() > 1) {
			// 有重名：优先取 id 以 minecraft: 开头的那个，避免选到模组物品
			for (ResourceLocation candidate : exact) {
				if ("minecraft".equals(candidate.getNamespace())) {
					return new Result(BuiltInRegistries.ITEM.getValue(candidate), null);
				}
			}
			return new Result(BuiltInRegistries.ITEM.getValue(exact.get(0)), null);
		}

		// 3) 包含匹配（支持只输入名字的一部分）
		Set<ResourceLocation> hits = new LinkedHashSet<>();
		for (Map.Entry<String, List<ResourceLocation>> e : table().entrySet()) {
			if (e.getKey().contains(key)) {
				hits.addAll(e.getValue());
			}
		}
		if (hits.size() == 1) {
			return new Result(BuiltInRegistries.ITEM.getValue(hits.iterator().next()), null);
		}
		if (hits.size() > 1) {
			StringBuilder sb = new StringBuilder();
			int n = 0;
			for (ResourceLocation r : hits) {
				if (n++ >= 5) { sb.append(" 等"); break; }
				sb.append(n == 1 ? "" : "、").append(r);
			}
			return new Result(null, "「" + s + "」匹配到多个物品（" + sb + "），请写更精确的名字");
		}
		return new Result(null, "找不到物品「" + s + "」（可用 ID 如 minecraft:diamond，或中文名如 钻石）");
	}

	/**
	 * 解析一个名字，支持通配符 {@code *}（如 {@code *_ore}、{@code minecraft:*_ingot}）。
	 *
	 * <p>通配符会展开成多个物品；普通名字只会得到 0 或 1 个。
	 * 匹配对象包括<b>物品 ID</b>和<b>本地化名</b>，所以 {@code *矿石*} 这种写法也行。</p>
	 */
	public static List<Item> resolveAll(String raw) {
		List<Item> out = new ArrayList<>();
		if (raw == null) return out;
		String s = raw.trim();
		if (s.isEmpty()) return out;

		if (s.indexOf('*') < 0) {
			Result r = resolve(s);
			if (r.ok()) out.add(r.item());
			return out;
		}

		// 通配符：转成正则，同时匹配 ID 和本地化名
		String regex = ("(?i)" + java.util.regex.Pattern.quote(s).replace("*", "\\E.*\\Q"));
		java.util.regex.Pattern p;
		try {
			p = java.util.regex.Pattern.compile(regex);
		} catch (Throwable t) {
			return out;
		}

		for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item == null || item == Items.AIR) continue;

			boolean hit = p.matcher(id.toString()).matches();
			if (!hit) {
				try {
					hit = p.matcher(new ItemStack(item).getHoverName().getString()).matches();
				} catch (Throwable ignored) {
					// 取不到名字就只按 ID 匹配
				}
			}
			if (hit) out.add(item);
		}
		return out;
	}

	/** 解析一串逗号/顿号分隔的物品名（支持通配符） */
	public static List<Item> resolveList(String raw) {
		List<Item> out = new ArrayList<>();
		if (raw == null) return out;
		for (String part : raw.split("[,，、;；\\s]+")) {
			if (part.isBlank()) continue;
			out.addAll(resolveAll(part));
		}
		return out;
	}

	/** 解析并报告第一个错误（用于生成友好提示）；通配符匹配不到也算错误 */
	public static String resolveListError(String raw) {
		if (raw == null) return "物品列表为空";
		for (String part : raw.split("[,，、;；\\s]+")) {
			if (part.isBlank()) continue;
			if (part.indexOf('*') >= 0) {
				if (resolveAll(part).isEmpty()) {
					return "通配符「" + part + "」没有匹配到任何物品";
				}
				continue;
			}
			Result r = resolve(part);
			if (!r.ok()) return r.error();
		}
		return null;
	}

	/** 物品的显示名（用于消息输出） */
	public static String displayName(Item item) {
		if (item == null) return "?";
		try {
			return new ItemStack(item).getHoverName().getString();
		} catch (Throwable t) {
			return String.valueOf(item);
		}
	}
}
