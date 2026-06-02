package com.exchangelens;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("exchangelens")
public interface ExchangeLensConfig extends Config
{
	@ConfigItem(
		keyName = "availableCash",
		name = "Available Cash",
		description = "Your available GP for flipping (used to filter and estimate affordable quantities)"
	)
	default int availableCash()
	{
		return 10_000_000;
	}

	@ConfigItem(
		keyName = "minimumNetMargin",
		name = "Minimum Net Margin",
		description = "Minimum net margin (after tax) in GP to show a recommendation"
	)
	default int minimumNetMargin()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "minimumRoi",
		name = "Minimum ROI (%)",
		description = "Minimum return on investment percentage to show a recommendation"
	)
	default double minimumRoi()
	{
		return 0.25;
	}

	@ConfigItem(
		keyName = "minimumHourlyVolume",
		name = "Minimum Hourly Volume",
		description = "Minimum combined hourly trade volume to consider an item liquid enough"
	)
	default int minimumHourlyVolume()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "maximumCapitalPerFlip",
		name = "Maximum Capital Per Flip",
		description = "Maximum GP to allocate per flip opportunity"
	)
	default int maximumCapitalPerFlip()
	{
		return 10_000_000;
	}

	@Range(min = 10, max = 600)
	@ConfigItem(
		keyName = "refreshIntervalSeconds",
		name = "Refresh Interval (seconds)",
		description = "How often to refresh market data (minimum 10 seconds)"
	)
	default int refreshIntervalSeconds()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "includeMembersItems",
		name = "Include Members Items",
		description = "Include members-only items in recommendations"
	)
	default boolean includeMembersItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideHighRiskItems",
		name = "Hide High-Risk Items",
		description = "Hide items flagged as high-risk from recommendations"
	)
	default boolean hideHighRiskItems()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showStaleDataWarnings",
		name = "Show Stale Data Warnings",
		description = "Show a warning when market data could not be refreshed"
	)
	default boolean showStaleDataWarnings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPriceInjection",
		name = "Show Price Suggestions",
		description = "Show Exchange Lens suggested price in GE offer screen"
	)
	default boolean showPriceInjection()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colorizeSlots",
		name = "Colorize GE Slot Prices",
		description = "Color GE slot price text green/red based on profitability"
	)
	default boolean colorizeSlots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTooltipProfit",
		name = "Show Profit in Tooltip",
		description = "Show estimated profit in GE sell offer tooltip"
	)
	default boolean showTooltipProfit()
	{
		return true;
	}
}
