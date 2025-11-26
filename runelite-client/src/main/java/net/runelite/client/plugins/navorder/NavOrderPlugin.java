package net.runelite.client.plugins.navorder;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
    name = "Nav Order",
    description = "Customize sidebar navigation order",
    tags = {"sidebar", "navigation"}
)
@Singleton
public class NavOrderPlugin extends Plugin
{
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientUI clientUI;

    @Inject
    private net.runelite.client.eventbus.EventBus eventBus;

    // Do not inject NavOrderPanel here; instantiate lazily in startUp to avoid
    // creating Swing components before Look-and-Feel is initialized.

    private NavigationButton navButton;

    @Override
    protected void startUp() throws Exception
    {
        final NavOrderPanel panel = injector.getInstance(NavOrderPanel.class);

        navButton = NavigationButton.builder()
            .tooltip("Nav Order")
            .icon(ClientUI.ICON_16)
            .priority(50)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);
        
        // Refresh panel on plugin load/unload so removed/added nav buttons are visible
        eventBus.register(this);

        // If a backup of the user's ordering exists (plugin was previously disabled), restore it
        String backup = configManager.getConfiguration("runelite", "nav.order.backup", String.class);
        String current = configManager.getConfiguration("runelite", "nav.order", String.class);
        log.debug("NavOrderPlugin.startUp: current nav.order='{}', backup='{}'", current, backup);
        if ((current == null || current.isEmpty()) && backup != null && !backup.isEmpty())
        {
            log.debug("NavOrderPlugin.startUp: restoring nav.order from backup");
            configManager.setConfiguration("runelite", "nav.order", backup);

            // Reapply saved user order when the plugin is enabled so the ordering takes effect
            clientUI.onUserNavOrderSaved();

            // Schedule a delayed reapply to ensure any late-registered nav buttons are ordered
            Timer t = new Timer(250, new ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    log.debug("NavOrderPlugin.startUp: running delayed reapply and clearing backup");
                    clientUI.onUserNavOrderSaved();
                    configManager.unsetConfiguration("runelite", "nav.order.backup");
                }
            });
            t.setRepeats(false);
            t.start();
        }
        else
        {
            clientUI.onUserNavOrderSaved();
        }
    }

    @Override
    protected void shutDown() throws Exception
    {
        clientToolbar.removeNavigation(navButton);
        // Disable custom ordering when plugin is disabled: back up the saved order and clear it
        String current = configManager.getConfiguration("runelite", "nav.order", String.class);
        log.debug("NavOrderPlugin.shutDown: current nav.order='{}'", current);
        if (current != null && !current.isEmpty())
        {
            log.debug("NavOrderPlugin.shutDown: backing up nav.order and clearing it");
            configManager.setConfiguration("runelite", "nav.order.backup", current);
            configManager.unsetConfiguration("runelite", "nav.order");
        }

        clientUI.onUserNavOrderSaved();
    }
    
    @com.google.common.eventbus.Subscribe
    public void onPluginChanged(net.runelite.client.events.PluginChanged ev)
    {
        // only refresh when a plugin was loaded or unloaded
        log.debug("NavOrderPlugin: PluginChanged event for {} loaded={}", ev.getPlugin().getClass().getSimpleName(), ev.isLoaded());
        try
        {
            NavOrderPanel p = injector.getInstance(NavOrderPanel.class);
            p.refresh();
        }
        catch (Exception ex)
        {
            log.debug("NavOrderPlugin: failed to refresh NavOrderPanel via injector", ex);
        }
    }
}
