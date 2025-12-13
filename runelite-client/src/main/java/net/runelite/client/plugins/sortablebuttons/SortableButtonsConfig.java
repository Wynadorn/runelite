package net.runelite.client.plugins.sortablebuttons;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(SortableButtonsConfig.GROUP)
public interface SortableButtonsConfig extends Config
{
	String GROUP = "sortablebuttons";
	String PINNED_ORDER_KEY = "pinnedOrder";
	String HIDDEN_BUTTONS_KEY = "hiddenButtons";

	@ConfigItem(
		keyName = "allowPinning",
		name = "Allow button pinning",
		description = "Allow pinning sidebar buttons to a pinned section."
	)
	default boolean allowPinning()
	{
		return false;
	}

	@ConfigItem(
		keyName = "allowHiding",
		name = "Allow button hiding",
		description = "Allow moving sidebar buttons into the hidden section."
	)
	default boolean allowHiding()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pinnedOrder",
		name = "Pinned order",
		description = "CSV of pinned navigation tooltips.",
		hidden = true
	)
	default String pinnedOrder()
	{
		return "";
	}

	@ConfigItem(
		keyName = "hiddenButtons",
		name = "Hidden buttons",
		description = "CSV of hidden navigation tooltips.",
		hidden = true
	)
	default String hiddenButtons()
	{
		return "";
	}
}
