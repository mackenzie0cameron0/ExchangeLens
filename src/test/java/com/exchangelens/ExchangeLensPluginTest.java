package com.exchangelens;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExchangeLensPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ExchangeLensPlugin.class);
		RuneLite.main(args);
	}
}
