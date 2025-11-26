package net.runelite.client.plugins.navorder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.Color;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.plugins.config.PluginToggleButton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.DragAndDropReorderPane;
import net.runelite.client.util.ImageUtil;

@Singleton
public class NavOrderPanel extends PluginPanel
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NavOrderPanel.class);

    private final ConfigManager configManager;
    private final ClientUI clientUI;
    private final DragAndDropReorderPane reorderPane = new DragAndDropReorderPane();
    private final List<NavigationButton> buttonByIndex = new ArrayList<>();
    private final Gson gson = new Gson();

    // map of nav id -> hidden state persisted separately from ordering
    private final java.util.Map<String, Boolean> hiddenMap = new java.util.HashMap<>();

    @Inject
    public NavOrderPanel(ConfigManager configManager, ClientUI clientUI)
    {
        this.configManager = configManager;
        this.clientUI = clientUI;

        // Short description at top explaining the UI
        JLabel desc = new JLabel("<html><b>Reorder sidebar entries</b><br>Drag rows to reorder items in the sidebar. Use the toggle to show or hide entries.</html>");
        desc.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        desc.setForeground(java.awt.Color.WHITE);
        add(desc);

        // Use PluginPanel's built-in wrapping scroll pane and layout. Add the reorder pane
        // directly so it participates in the panel's scrolling behavior.
        add(reorderPane);

        JPanel footer = new JPanel(new GridLayout(1, 2, 8, 8));
        JButton btnReset = new JButton("Reset");

        btnReset.addActionListener(this::onReset);

        footer.add(btnReset);

        add(footer);

        // register drag listener once - save automatically after a drag completes
        reorderPane.addDragListener(component ->
        {
            // reconstruct buttonByIndex from component client properties
            buttonByIndex.clear();
            for (Component c : reorderPane.getComponentsInLayer(javax.swing.JLayeredPane.DEFAULT_LAYER))
            {
                if (c instanceof JPanel)
                {
                    Object o = ((JPanel) c).getClientProperty("navBtn");
                    if (o instanceof NavigationButton)
                    {
                        buttonByIndex.add((NavigationButton) o);
                    }
                    else
                    {
                        buttonByIndex.add(null);
                    }
                }
            }

            // Persist new order immediately
            saveOrder();
        });

        // load persisted hidden map
        loadHiddenMap();

        rebuildList();
    }

    private void rebuildList()
    {
        log.debug("NavOrderPanel.rebuildList: called");

        // ensure no stale components remain in any layer (drag layer sometimes keeps
        // temporary components around after plugins are removed)
        for (Component c : reorderPane.getComponentsInLayer(javax.swing.JLayeredPane.DRAG_LAYER))
        {
            reorderPane.remove(c);
        }
        for (Component c : reorderPane.getComponentsInLayer(javax.swing.JLayeredPane.DEFAULT_LAYER))
        {
            reorderPane.remove(c);
        }

        // Build ordered list of buttons: prefer user-saved order when available so hidden
        // entries keep their place and can be reordered.
        List<NavigationButton> allButtons = clientUI.getAllNavigationButtons();
        java.util.List<NavigationButton> buttons = new ArrayList<>();
        buttonByIndex.clear();

        try
        {
            String json = configManager.getConfiguration("runelite", "nav.order", String.class);
            if (json != null && !json.isEmpty())
            {
                java.util.Map<String, java.util.ArrayDeque<NavigationButton>> map = new java.util.HashMap<>();
                for (NavigationButton nb : allButtons)
                {
                    String id = clientUI.computeNavIdPublic(nb);
                    map.computeIfAbsent(id == null ? "" : id, k -> new java.util.ArrayDeque<>()).add(nb);
                }

                java.util.List<String> ids = gson.fromJson(json, new TypeToken<java.util.List<String>>(){}.getType());
                if (ids != null)
                {
                    for (String id : ids)
                    {
                        java.util.ArrayDeque<NavigationButton> q = map.get(id == null ? "" : id);
                        if (q != null && !q.isEmpty())
                        {
                            buttons.add(q.removeFirst());
                        }
                    }
                }

                // append any remaining buttons not specified by the user, sorted by comparator
                java.util.List<NavigationButton> remaining = new java.util.ArrayList<>();
                for (java.util.ArrayDeque<NavigationButton> q : map.values())
                {
                    while (!q.isEmpty())
                    {
                        remaining.add(q.removeFirst());
                    }
                }
                remaining.sort(java.util.Comparator.comparingInt(NavigationButton::getPriority)
                    .thenComparing(NavigationButton::getTooltip));
                buttons.addAll(remaining);
            }
            else
            {
                buttons.addAll(allButtons);
            }
        }
        catch (Exception ex)
        {
            // fallback
            buttons.addAll(allButtons);
        }

        for (NavigationButton nb : buttons)
        {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            // drag handle on the left to signal users they can drag the row
            JLabel handle = new JLabel("\u2630");
            handle.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
            handle.setForeground(new Color(160, 160, 160));
            p.add(handle, BorderLayout.LINE_START);

            p.add(new JLabel(nb.getTooltip(), new javax.swing.ImageIcon(ImageUtil.resizeImage(nb.getIcon(), 16, 16)), SwingConstants.LEFT), BorderLayout.CENTER);
            // visibility toggle
            String id = clientUI.computeNavIdPublic(nb);
            boolean hidden = false;
            if (id != null && hiddenMap.containsKey(id))
            {
                hidden = hiddenMap.get(id);
            }

            // Protect the Nav Order entry itself from being hidden to avoid lockout
            boolean isNavOrder = "Nav Order".equals(nb.getTooltip());
            boolean isConfiguration = "Configuration".equals(nb.getTooltip());
            if (isNavOrder || isConfiguration)
            {
                hidden = false;
                clientUI.setNavigationHidden(nb, false);
            }

                if (isNavOrder || isConfiguration)
                {
                    // for the Nav Order entry and Configuration entry, don't show a toggle to avoid confusing users
                    // just ensure it's visible
                    clientUI.setNavigationHidden(nb, false);
                    JPanel spacer = new JPanel();
                    spacer.setPreferredSize(new Dimension(25, 0));
                    p.add(spacer, BorderLayout.EAST);
                }
                else
                {
                    PluginToggleButton toggle = new PluginToggleButton();
                    toggle.setSelected(!hidden);
                    toggle.setToolTipText(toggle.isSelected() ? "Hide from sidebar" : "Show in sidebar");
                    toggle.addActionListener(ev ->
                    {
                        boolean nowVisible = toggle.isSelected();
                        boolean nowHidden = !nowVisible;
                        if (id != null)
                        {
                            hiddenMap.put(id, nowHidden);
                        }
                        clientUI.setNavigationHidden(nb, nowHidden);
                        toggle.setToolTipText(nowVisible ? "Hide from sidebar" : "Show in sidebar");
                        saveHiddenMap();
                    });
                    p.add(toggle, BorderLayout.EAST);
                }
            p.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 28));
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

            // tag component with its navigation button for later reconstruction
            p.putClientProperty("navBtn", nb);
            reorderPane.add(p, javax.swing.JLayeredPane.DEFAULT_LAYER);
            buttonByIndex.add(nb);
        }

        reorderPane.revalidate();
        reorderPane.repaint();
    }

    private void loadHiddenMap()
    {
        try
        {
            String json = configManager.getConfiguration("runelite", "nav.order.visibility", String.class);
            if (json == null || json.isEmpty())
            {
                return;
            }
            java.util.Map<String, Boolean> m = gson.fromJson(json, new TypeToken<java.util.Map<String, Boolean>>(){}.getType());
            if (m != null)
            {
                hiddenMap.clear();
                hiddenMap.putAll(m);
            }
        }
        catch (Exception ex)
        {
            log.debug("NavOrderPanel.loadHiddenMap: failed to load visibility map", ex);
        }
    }

    private void saveHiddenMap()
    {
        try
        {
            String json = gson.toJson(hiddenMap, new TypeToken<java.util.Map<String, Boolean>>(){}.getType());
            configManager.setConfiguration("runelite", "nav.order.visibility", json);
        }
        catch (Exception ex)
        {
            log.debug("NavOrderPanel.saveHiddenMap: failed to save visibility map", ex);
        }
    }

    /**
     * Publicly refresh the list from current clientUI state.
     */
    public void refresh()
    {
        log.debug("NavOrderPanel.refresh: called");
        rebuildList();
    }



    private void saveOrder()
    {
        try
        {
            NavigationButton active = clientUI.getSelectedNavigationButton();
            log.debug("NavOrderPanel.saveOrder: active nav tooltip='{}' id='{}'",
                active == null ? null : active.getTooltip(),
                active == null ? null : clientUI.computeNavIdPublic(active));
        }
        catch (Exception ex)
        {
            log.debug("NavOrderPanel.saveOrder: failed to log active nav", ex);
        }

        // collect ids in visual order
        List<String> ids = new ArrayList<>();
        List<String> visualTooltips = new ArrayList<>();

        if (!buttonByIndex.isEmpty())
        {
            for (NavigationButton nb : buttonByIndex)
            {
                if (nb != null)
                {
                    ids.add(clientUI.computeNavIdPublic(nb));
                    visualTooltips.add(nb.getTooltip());
                }
                else
                {
                    ids.add("unknown");
                    visualTooltips.add("unknown");
                }
            }
        }
        else
        {
            for (Component c : reorderPane.getComponentsInLayer(javax.swing.JLayeredPane.DEFAULT_LAYER))
            {
                if (c instanceof JPanel)
                {
                    Object o = ((JPanel) c).getClientProperty("navBtn");
                    if (o instanceof NavigationButton)
                    {
                        ids.add(clientUI.computeNavIdPublic((NavigationButton) o));
                        visualTooltips.add(((NavigationButton) o).getTooltip());
                    }
                    else
                    {
                        ids.add("unknown");
                        visualTooltips.add("unknown");
                    }
                }
            }
        }

        String json = gson.toJson(ids, new TypeToken<List<String>>(){}.getType());
        log.debug("NavOrderPanel saving nav.order JSON: {}", json);
        log.debug("NavOrderPanel visual tooltips: {}", String.join(", ", visualTooltips));
        configManager.setConfiguration("runelite", "nav.order", json);
        // also save visibility map
        saveHiddenMap();
        clientUI.onUserNavOrderSaved();
    }

    private void onReset(ActionEvent ev)
    {
        configManager.unsetConfiguration("runelite", "nav.order");
        // clear visibility overrides as well
        configManager.unsetConfiguration("runelite", "nav.order.visibility");
        // unhide any hidden navs
        for (NavigationButton nb : clientUI.getAllNavigationButtons())
        {
            try
            {
                clientUI.setNavigationHidden(nb, false);
            }
            catch (Exception ex)
            {
                // ignore
            }
        }
        hiddenMap.clear();
        rebuildList();
        clientUI.onUserNavOrderSaved();
    }

    @Override
    public void onActivate()
    {
        log.debug("NavOrderPanel.onActivate: called");
        refresh();
    }
}
