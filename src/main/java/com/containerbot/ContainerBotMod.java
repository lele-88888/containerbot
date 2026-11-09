package com.containerbot;

import com.containerbot.command.BotCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口。
 *
 * 设计要点：本模组刻意 <b>不依赖 Carpet 的编译期 API</b>。
 * 假人的生成通过派发 Carpet 的 /player 命令完成，其余全部使用原版接口。
 * 好处是 Carpet 版本升级不会导致本模组编译失败——这类项目最常见的死因。
 */
public class ContainerBotMod implements ModInitializer {

	public static final String MOD_ID = "containerbot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				BotCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(BotManager::tick);

		LOGGER.info("[ContainerBot] 已加载，指令: /cbot");
	}
}
