package net.runelite.client.plugins.sortablebuttons;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.SortableJTabbedPane;
import net.runelite.client.eventbus.Subscribe;

@PluginDescriptor(
	name = "Sortable Buttons",
	description = "Configure pinning and hiding for sidebar buttons.",
	enabledByDefault = false
)
public class SortableButtonsPlugin extends Plugin
{
	@Inject
	private ClientUI clientUI;

	@Inject
	private SortableButtonsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Provides
	SortableButtonsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SortableButtonsConfig.class);
	}

	@Override
	protected void startUp()
	{
		applySidebarConfig(true);
	}

	@Override
	protected void shutDown()
	{
		applySidebarConfig(false);
	}

	@Override
	public void resetConfiguration()
	{
		configManager.unsetConfiguration(SortableButtonsConfig.GROUP, SortableButtonsConfig.PINNED_ORDER_KEY);
		configManager.unsetConfiguration(SortableButtonsConfig.GROUP, SortableButtonsConfig.HIDDEN_BUTTONS_KEY);
		configManager.unsetConfiguration("runelite", "clientSidebarPinned");
		configManager.unsetConfiguration("runelite", "clientSidebarHidden");

		if (pluginManager.isPluginActive(this))
		{
			applySidebarConfig(true);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SortableButtonsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		applySidebarConfig(true);
	}

	private void applySidebarConfig(boolean pluginEnabled)
	{
		SortableJTabbedPane sidebar = clientUI.getSidebar();
		if (sidebar == null)
		{
			return;
		}

		boolean allowPinning = config.allowPinning();
		boolean allowHiding = config.allowHiding();
		sidebar.applySortableConfig(pluginEnabled, allowPinning, allowHiding);
	}
}
