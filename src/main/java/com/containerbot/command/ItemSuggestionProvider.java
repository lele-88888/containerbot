package com.containerbot.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.concurrent.CompletableFuture;

/**
 * 为物品 ID 参数提供 Tab 补全。
 *
 * <p>用 {@link SharedSuggestionProvider#suggestResource} 而非直接
 * {@code builder.suggest(id.toString())}：前者能正确处理带命名空间的 ID，
 * 并且当用户已经输入了「minecraft:」前缀时只补全后缀部分。</p>
 */
public final class ItemSuggestionProvider implements SuggestionProvider<CommandSourceStack> {

	public static final ItemSuggestionProvider INSTANCE = new ItemSuggestionProvider();

	private ItemSuggestionProvider() {}

	@Override
	public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> ctx,
	                                                     SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggestResource(
				BuiltInRegistries.ITEM.keySet(), builder);
	}
}
