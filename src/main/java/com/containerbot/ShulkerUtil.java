package com.containerbot;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 潜影盒内容物读取工具。
 *
 * <p>1.20.5 起，潜影盒的内容物不再是 NBT，而是数据组件
 * {@code DataComponents.CONTAINER}（{@link ItemContainerContents}）。
 * 这里统一封装读取逻辑，并额外提供「按目标物品统计数量」的便捷方法。</p>
 */
public final class ShulkerUtil {

	private ShulkerUtil() {}

	/** 该物品堆是否是潜影盒（即带有容器组件） */
	public static boolean isShulkerBox(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.has(DataComponents.CONTAINER);
	}

	/** 取出一只潜影盒里的全部物品（副本，不影响原堆） */
	public static List<ItemStack> contents(ItemStack shulker) {
		List<ItemStack> out = new ArrayList<>();
		if (!isShulkerBox(shulker)) return out;

		ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
		if (contents == null) return out;

		contents.nonEmptyItems().forEach(stack -> {
			if (stack != null && !stack.isEmpty()) {
				out.add(stack.copy());
			}
		});
		return out;
	}

	/**
	 * 统计一只潜影盒里装了多少个目标物品。
	 *
	 * <p>用于「整盒搬走但按盒内实际数量计数」——盒子本身只占假人 1 格背包，
	 * 但计入进度的是盒内真实个数，这样「要 256 个钻石」不会被一个盒子
	 * 直接算成超额达成。</p>
	 */
	public static int countItem(ItemStack shulker, net.minecraft.world.item.Item item) {
		int total = 0;
		for (ItemStack inner : contents(shulker)) {
			if (inner.is(item)) {
				total += inner.getCount();
			}
		}
		return total;
	}
}
