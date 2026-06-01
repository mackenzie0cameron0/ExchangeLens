package com.exchangelens;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Exchange Lens",
	description = "Grand Exchange flipping assistant with margin analysis and flip tracking",
	tags = {"grand exchange", "ge", "flip", "market", "price"}
)
public class ExchangeLensPlugin extends Plugin
{
	@Inject
	private ExchangeLensConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Exchange Lens started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Exchange Lens stopped!");
	}

	@Provides
	ExchangeLensConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExchangeLensConfig.class);
	}
}
