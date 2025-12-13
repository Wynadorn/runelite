package net.runelite.client.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.swing.BoxLayout;
import javax.swing.JLayer;
import javax.swing.JComponent;
import javax.swing.plaf.LayerUI;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.Component;
import java.awt.IllegalComponentStateException;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import lombok.Getter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.sortablebuttons.SortableButtonsConfig;

/**
 * A replacement for the previous JTabbedPane-based sidebar which renders
 * navigation buttons (pinned and sorted) as a single vertical list and
 * manages plugin panels via a CardLayout.
 */
public class SortableJTabbedPane extends JPanel
{
	private static final int ICON_SIZE = 16;
	private static final int DEFAULT_WIDTH = 274; // approximate previous sidebar panel width (reduced by 26px)
	private static final int DRAG_THRESHOLD = 8; // pixels (euclidean)
	private static final String CONFIG_GROUP = SortableButtonsConfig.GROUP;
	private static final String CONFIG_PINNED = SortableButtonsConfig.PINNED_ORDER_KEY;
	private static final String CONFIG_HIDDEN = SortableButtonsConfig.HIDDEN_BUTTONS_KEY;
	private static final int MIN_PINNED_HEIGHT = 24;

	private static final boolean DEBUG = Boolean.getBoolean("runelite.debug.ui");
	private static final Logger LOGGER = LoggerFactory.getLogger(SortableJTabbedPane.class);
	private static final Color DEBUG_NAV_BG_BLUE = new Color(0x0000FF);
	private static final Color DEBUG_PINNED_BG_GREEN = new Color(0x00FF00);
	private static final Color DEBUG_SORTED_BG_ORANGE = new Color(0xFF7700);
	private static final Color DEBUG_HIDDEN_BG_RED = new Color(0xFF0000);
	private static final Color DEBUG_CARDS_BG_GREY = new Color(0x777);
	private static final Color DEBUG_FOOTER_BG_CYAN = new Color(0x00FFFF);
	private static final Color DEBUG_BUTTON_BG_YELLOW = new Color(0xFFFF00);
	private static final Color SELECTED_STRIPE_COLOR = new Color(0xFF7700);
	// Feature flags controlled by the Sortable Buttons plugin/config
	private boolean pinningEnabled = true;
	private boolean hidingEnabled = true;

    // Sidebar/button background color (consistent across panels)
    private static final Color BUTTON_BG = new Color(0x1e1e1e);

	// Panels
	private final JPanel navigationButtonsPanel = new JPanel();
	private final JPanel pinnedButtonsPanel = new JPanel()
	{
		@Override
		public Dimension getPreferredSize()
		{
			if (!pinningEnabled)
			{
				return new Dimension(0, 0);
			}
			Dimension d = super.getPreferredSize();
			// If there are no visible pinned buttons, reserve a small area so we can
			// show a pin placeholder hint; when dragging we also want the minimum height.
			int visiblePinned = 0;
			for (NavigationButton nb : pinnedButtons)
			{
				if (!hiddenButtons.contains(nb))
				{
					visiblePinned++;
				}
			}
			if (visiblePinned == 0 && !isDragging)
			{
				if (d == null)
				{
					d = new Dimension(0, MIN_PINNED_HEIGHT);
				}
				d.height = MIN_PINNED_HEIGHT;
				return d;
			}
			if (d == null)
			{
				d = new Dimension(0, MIN_PINNED_HEIGHT);
			}
			if (d.height < MIN_PINNED_HEIGHT)
			{
				d.height = MIN_PINNED_HEIGHT;
			}
			return d;
		}
	};
	private final JPanel sortedButtonsPanel = new JPanel();
	private final JPanel hiddenButtonsPanel = new JPanel();

	private final JPanel sidebarContentPanel = new JPanel();
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel footerHolder_DeleteMe = new JPanel();

	// Data structures
	private final List<NavigationButton> pinnedButtons = new ArrayList<>();
	private final List<NavigationButton> sortedButtons = new ArrayList<>();
	private final Map<NavigationButton, JButton> buttonMap = new LinkedHashMap<>();
	private final Map<NavigationButton, PluginPanel> panelMap = new LinkedHashMap<>();
	private final List<ChangeListener> changeListeners = new ArrayList<>();
	private final List<String> pinnedOrderTooltips = new ArrayList<>();
	private final Set<String> hiddenTooltips = new LinkedHashSet<>();
	private final List<NavigationButton> hiddenButtons = new ArrayList<>();

	private final PaintedButton hiddenZoneButton;
	// Placeholder pin icon for empty pinned area
	private final Icon pinPlaceholderIcon;

	private final ConfigManager configManager; // may be null in tests

	@Getter
	private NavigationButton selectedNavigation;

	// Dragging state (ghost + placeholder)
	private javax.swing.JWindow dragGhostWindow;
	private AWTEventListener globalDragListener;
	private JPanel insertionPlaceholder;
	private NavigationButton draggingNavigation;
	private JButton draggingButton;
	private Container dragSourceParent;
	private int dragSourceIndex = -1;
	private Point dragOffset;
	private boolean isDragging = false;
	private JPanel currentTargetPanel;
	private int currentTargetIndex = -1;

	// Button implementation that paints its own background so the Look-and-Feel
	// cannot change selection/hover visuals. Hover shows `#111111`; otherwise
	// the background remains `BUTTON_BG`. Selection should only be indicated
	// by the orange stripe painted by the navigation panel.
	private class PaintedButton extends JButton
	{
		private boolean hover = false;
		private Color forcedBackground = null;

		PaintedButton()
		{
			super();
			setContentAreaFilled(false);
			setOpaque(false);
		}

		PaintedButton(Icon i)
		{
			super(i);
			setContentAreaFilled(false);
			setOpaque(false);
		}

		@Override
		public Dimension getMaximumSize()
		{
			Dimension d = super.getPreferredSize();
			d.width = Short.MAX_VALUE;
			return d;
		}

		public void setHover(boolean h)
		{
			if (this.hover != h)
			{
				this.hover = h;
				repaint();
			}
		}

		public void setForcedBackground(Color c)
		{
			if (this.forcedBackground != c)
			{
				this.forcedBackground = c;
				repaint();
			}
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			try
			{
				Color bg = BUTTON_BG;
				if (forcedBackground != null)
				{
					bg = forcedBackground;
				}
				else if (hover && !isSelected() && !isDragging)
				{
					bg = new Color(0x111111);
				}
				g2.setColor(bg);
				g2.fillRect(0, 0, getWidth(), getHeight());
			}
			finally
			{
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}
	// Highlight colors
	private static final Color HIGHLIGHT_HIDDEN_COLOR = new Color(0x444444);
	private static final Color HIGHLIGHT_SORTED_COLOR = new Color(0x3A3A3A);

	private boolean isDragAllowed()
	{
		return pinningEnabled || hidingEnabled;
	}

	// (No saved highlight state needed; buttons/panels are always opaque and have uniform background)

	public SortableJTabbedPane(ConfigManager configManager)
	{
		this.configManager = configManager;
		// initialize painted hidden-zone button (custom painting)
		hiddenZoneButton = new PaintedButton();
		setLayout(new BorderLayout());
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Navigation section (vertical list) - use BorderLayout so the sorted area expands
		navigationButtonsPanel.setLayout(new BorderLayout());
		// keep navigation area opaque with a consistent background
		navigationButtonsPanel.setOpaque(true);
		navigationButtonsPanel.setBackground(BUTTON_BG);
		// subtle left divider between content and navigation buttons
		navigationButtonsPanel.setBorder(new javax.swing.border.MatteBorder(0, 1, 0, 0, new Color(0x171717)));
		sortedButtonsPanel.setBackground(BUTTON_BG);
		pinnedButtonsPanel.setLayout(new BoxLayout(pinnedButtonsPanel, BoxLayout.Y_AXIS));
		pinnedButtonsPanel.setOpaque(true);
		pinnedButtonsPanel.setBackground(BUTTON_BG);
		// Allow the pinned area to collapse to zero when empty; the overridden
		// `getPreferredSize` will provide a minimum height when necessary (e.g. during drag).
		//pinnedButtonsPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, MIN_PINNED_HEIGHT));
		pinnedButtonsPanel.setMinimumSize(new Dimension(0, 0));
		sortedButtonsPanel.setLayout(new BoxLayout(sortedButtonsPanel, BoxLayout.Y_AXIS));
		sortedButtonsPanel.setOpaque(true);
		sortedButtonsPanel.setBackground(BUTTON_BG);
		footerHolder_DeleteMe.setLayout(new BorderLayout());
		footerHolder_DeleteMe.setOpaque(false);
		hiddenButtonsPanel.setLayout(new BorderLayout());
		hiddenButtonsPanel.setOpaque(true);
		hiddenButtonsPanel.setBackground(BUTTON_BG);
		hiddenButtonsPanel.setMinimumSize(new Dimension(0, MIN_PINNED_HEIGHT));
		// ensure the hidden drop zone remains visible even when no hidden items exist
		hiddenButtonsPanel.setPreferredSize(new Dimension(0, MIN_PINNED_HEIGHT));
		hiddenButtonsPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, MIN_PINNED_HEIGHT));

		// Hidden zone button
		hiddenZoneButton.setVisible(false);
		hiddenZoneButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0; iconTextGap:8;");
		hiddenZoneButton.setBorder(new javax.swing.border.EmptyBorder(3,8,3,8));
		hiddenZoneButton.addActionListener(e -> showHiddenPopup());
		hiddenButtonsPanel.add(hiddenZoneButton, BorderLayout.CENTER);

		// Try to load a shared/plugin-provided invisible icon like other panels (e.g. LootTracker)
		BufferedImage bi = ImageUtil.loadImageResource(LootTrackerPlugin.class, "invisible_icon.png");
		ImageIcon sharedHiddenIcon = null;
		if (bi != null)
		{
			sharedHiddenIcon = new ImageIcon(ImageUtil.resizeImage(bi, ICON_SIZE, ICON_SIZE));
			hiddenZoneButton.setIcon(sharedHiddenIcon);
		}
		else
		{
			hiddenZoneButton.setText("X");
		}

		// hiddenZoneButton uses custom painting; update hover state instead of
		// manipulating background so LAF cannot override visuals.
		hiddenZoneButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!isDragging && !hiddenZoneButton.isSelected())
				{
					hiddenZoneButton.setHover(true);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hiddenZoneButton.setHover(false);
			}
		});

		// Load the pin placeholder icon (used when pinned area has no buttons).
		// Prefer a bundled mdi_pin resource; fall back to the shared/plugin invisible icon.
		BufferedImage pinImg = ImageUtil.loadImageResource(SortableJTabbedPane.class, "pin-solid.png");
		if (pinImg == null)
		{
			// also try utility location
			pinImg = ImageUtil.loadImageResource(SortableJTabbedPane.class, "/util/pin-solid.png");
		}
		ImageIcon pinIcon = null;
		if (pinImg != null)
		{
			pinIcon = new ImageIcon(ImageUtil.resizeImage(pinImg, ICON_SIZE, ICON_SIZE));
		}

		if (pinIcon != null)
		{
			pinPlaceholderIcon = pinIcon;
		}
		else if (sharedHiddenIcon != null)
		{
			// Previously used shared hidden icon as placeholder; keep as fallback
			pinPlaceholderIcon = sharedHiddenIcon;
		}
		else
		{
			// fallback: use an empty transparent image so the icon is always defined
			pinPlaceholderIcon = new ImageIcon(new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB));
		}

		// add into navigation panel: pinned (north), sorted (center, expands), hidden+footer (south)
		// Wrap pinned and hidden areas so we can insert separators between sections
		JPanel pinnedWrapper = new JPanel(new BorderLayout());
		pinnedWrapper.setOpaque(false);
		pinnedWrapper.add(pinnedButtonsPanel, BorderLayout.CENTER);
		JSeparator pinnedSeparator = new JSeparator();
		pinnedSeparator.setBackground(DEBUG_BUTTON_BG_YELLOW);
		pinnedSeparator.setPreferredSize(new Dimension(0, 2));
		pinnedWrapper.add(pinnedSeparator, BorderLayout.SOUTH);
		navigationButtonsPanel.add(pinnedWrapper, BorderLayout.NORTH);

		navigationButtonsPanel.add(sortedButtonsPanel, BorderLayout.CENTER);

		JPanel hiddenWrapper = new JPanel(new BorderLayout());
		hiddenWrapper.setOpaque(false);
		JSeparator hiddenSeparator = new JSeparator();
		hiddenSeparator.setPreferredSize(new Dimension(0, 1));
		hiddenWrapper.add(hiddenSeparator, BorderLayout.NORTH);
		hiddenWrapper.add(hiddenButtonsPanel, BorderLayout.CENTER);
		navigationButtonsPanel.add(hiddenWrapper, BorderLayout.SOUTH);

		sidebarContentPanel.setLayout(cardLayout);
		sidebarContentPanel.setOpaque(false);
		sidebarContentPanel.add(new JPanel(), "__empty__");
		//JPanel sidebarPanel = new JPanel(new BorderLayout());
		//sidebarPanel.setOpaque(false);
		// Place navigation buttons on the right (like original RIGHT tabs) and panel content to the left
		//sidebarPanel.add(sidebarContentPanel, BorderLayout.CENTER);
		//sidebarPanel.add(navigationButtonsPanel, BorderLayout.EAST);
		// Wrap navigation buttons panel in a JLayer so we can paint the
		// selected orange stripe on top of its children reliably.
		LayerUI<JComponent> stripeLayerUI = new LayerUI<JComponent>()
		{
			@Override
			public void paint(java.awt.Graphics g, JComponent c)
			{
				// Paint the view first (JLayer handles that) then draw overlay
				super.paint(g, c);
				if (selectedNavigation == null)
				{
					return;
				}
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				try
				{
					g2.setColor(SELECTED_STRIPE_COLOR);
					JLayer<?> layer = (JLayer<?>) c;
					// Draw stripe aligned with hidden-zone button when selected
					if (hiddenButtons.contains(selectedNavigation))
					{
						java.awt.Component hzParent = hiddenZoneButton.getParent();
						if (hzParent != null && hiddenZoneButton.isVisible())
						{
							java.awt.Rectangle r = SwingUtilities.convertRectangle(hzParent, hiddenZoneButton.getBounds(), layer);
							g2.fillRect(0, r.y, 3, r.height);
						}
					}
					else
					{
						JButton sb = buttonMap.get(selectedNavigation);
						if (sb != null && sb.isVisible())
						{
							java.awt.Rectangle r = SwingUtilities.convertRectangle(sb.getParent(), sb.getBounds(), layer);
							g2.fillRect(0, r.y, 3, r.height);
						}
					}
				}
				finally
				{
					g2.dispose();
				}
			}
		};

		JLayer<JComponent> navLayer = new JLayer<>(navigationButtonsPanel, stripeLayerUI);
		add(sidebarContentPanel, BorderLayout.CENTER);
		add(navLayer, BorderLayout.EAST);
		//add(sidebarPanel, BorderLayout.CENTER);
		// Provide style hints similar to old tabs (used by FlatLaf theming)
		putClientProperty(FlatClientProperties.STYLE, "tabHeight:26");
		// Load persisted state if possible
		loadState();
		if (DEBUG)
		{
			LOGGER.debug("SortableJTabbedPane initialized (debug=true), configManager: {}", configManager);
			//navigationButtonsPanel.setBackground(DEBUG_NAV_BG_BLUE);
			//pinnedButtonsPanel.setBackground(DEBUG_PINNED_BG_GREEN);
			//sortedButtonsPanel.setBackground(DEBUG_SORTED_BG_ORANGE);
			footerHolder_DeleteMe.setBackground(DEBUG_HIDDEN_BG_RED);
			//hiddenButtonsPanel.setBackground(DEBUG_HIDDEN_BG_RED);
			//sidebarContentPanel.setBackground(DEBUG_CARDS_BG_GREY);
			//hiddenZoneButton.setBackground(DEBUG_BUTTON_BG_YELLOW);
			navigationButtonsPanel.setOpaque(true);
			pinnedButtonsPanel.setOpaque(true);
			sortedButtonsPanel.setOpaque(true);
			footerHolder_DeleteMe.setOpaque(true);
			hiddenButtonsPanel.setOpaque(true);
			sidebarContentPanel.setOpaque(true);
			hiddenZoneButton.setOpaque(true);
		}
	}

	public void setFooterComponent(JPanel panel)
	{
		footerHolder_DeleteMe.removeAll();
		if (panel != null)
		{
			footerHolder_DeleteMe.add(panel, BorderLayout.CENTER);
		}
		footerHolder_DeleteMe.revalidate();
		footerHolder_DeleteMe.repaint();
	}

	public void applySortableConfig(boolean pluginEnabled, boolean allowPinning, boolean allowHiding)
	{
		boolean previousDragAllowed = isDragAllowed();
		pinningEnabled = pluginEnabled && allowPinning;
		hidingEnabled = pluginEnabled && allowHiding;
		loadState();

		if (isDragging && previousDragAllowed && !isDragAllowed())
		{
			Point p = new Point(0, 0);
			try
			{
				PointerInfo pi = MouseInfo.getPointerInfo();
				if (pi != null)
				{
					p = pi.getLocation();
				}
			}
			catch (Exception ignore)
			{
			}
			finishDrag(p);
		}

		reassignAllNavigations();
		updateHiddenButton();
	}

	private void reassignAllNavigations()
	{
		pinnedButtons.clear();
		sortedButtons.clear();
		hiddenButtons.clear();

		List<NavigationButton> allButtons = new ArrayList<>(panelMap.keySet());
		for (NavigationButton nb : allButtons)
		{
			String tt = safeTooltip(nb);
			if (hidingEnabled && hiddenTooltips.contains(tt))
			{
				hiddenButtons.add(nb);
				continue;
			}
			if (pinningEnabled && pinnedOrderTooltips.contains(tt))
			{
				pinnedButtons.add(nb);
				continue;
			}
			sortedButtons.add(nb);
		}

		if (pinningEnabled)
		{
			reorderPinnedBySavedOrder();
		}
		Collections.sort(sortedButtons, NavigationButton.COMPARATOR);
		rebuildSortedPanel();
	}

	/**
	 * Add a new navigation button with associated plugin panel.
	 * @param navBtn navigation button
	 */
	public void addNavigation(NavigationButton navBtn)
	{
		if (navBtn == null || navBtn.getPanel() == null || buttonMap.containsKey(navBtn))
		{
			if (DEBUG)
			{
				LOGGER.debug("addNavigation: ignored navBtn={}", navBtn);
			}
			return;
		}

		// Add underlying panel to card layout
		PluginPanel pluginPanel = navBtn.getPanel();
		panelMap.put(navBtn, pluginPanel);
		sidebarContentPanel.add(pluginPanel.getWrappedPanel(), panelKey(navBtn));
		reassignAllNavigations();
		sidebarContentPanel.revalidate();
	}

	/**
	 * Remove a navigation button.
	 */
	public void removeNavigation(NavigationButton navBtn)
	{
		if (navBtn == null || !buttonMap.containsKey(navBtn))
		{
			if (DEBUG)
			{
				LOGGER.debug("removeNavigation: ignored navBtn={}", navBtn);
			}
			return;
		}
		JButton btn = buttonMap.remove(navBtn);
		pinnedButtons.remove(navBtn);
		sortedButtons.remove(navBtn);
		hiddenButtons.remove(navBtn);
		pinnedButtonsPanel.remove(btn);
		sortedButtonsPanel.remove(btn);
		PluginPanel pp = panelMap.remove(navBtn);
		if (pp != null)
		{
			sidebarContentPanel.remove(pp.getWrappedPanel());
		}
		if (selectedNavigation == navBtn)
		{
			setSelectedNavigation(null);
		}
		revalidate();
		repaint();
		saveState();
		if (DEBUG)
		{
			LOGGER.debug("removeNavigation: removed tooltip={}", safeTooltip(navBtn));
		}
	}

	/**
	 * Set currently selected navigation. May be null to deselect.
	 */
	public void setSelectedNavigation(NavigationButton navBtn)
	{
		// Do not change selection while a drag operation is in progress
		if (isDragging)
		{
			return;
		}
		if (navBtn != null && !buttonMap.containsKey(navBtn) && !panelMap.containsKey(navBtn))
		{
			if (DEBUG)
			{
				LOGGER.debug("setSelectedNavigation: ignored unknown navBtn={}", navBtn);
			}
			return;
		}

		NavigationButton old = selectedNavigation;
		selectedNavigation = navBtn;

		// Update button visual state (selection + left stripe)
		buttonMap.forEach((nb, b) ->
		{
			boolean sel = nb == navBtn;
			b.setSelected(sel);
			updateButtonStripe(b, sel);
		});

		// Ensure hidden-zone button stripe reflects whether the currently selected
		// navigation entry is one of the hidden items.
		updateHiddenButton();

		// Switch panel
		if (navBtn == null)
		{
			cardLayout.show(sidebarContentPanel, "__empty__");
		}
		else
		{
			cardLayout.show(sidebarContentPanel, panelKey(navBtn));
			SwingUtil.activate(navBtn.getPanel());
			if (old != null && old != navBtn)
			{
				SwingUtil.deactivate(old.getPanel());
			}
		}

		fireChangeEvent(old, navBtn);
		// Revalidate so parent layout can update based on new preferred size (collapse/expand)
		revalidate();
		Container p = getParent();
		if (p != null)
		{
			p.revalidate();
		}
		repaint();
		if (DEBUG)
		{
			LOGGER.debug("setSelectedNavigation: from={} to={}", safeTooltip(old), safeTooltip(navBtn));
		}
	}

	/**
	 * Get the navigation button at a given point relative to this component.
	 */
	public NavigationButton getEntryAtPoint(Point p)
	{
		for (Map.Entry<NavigationButton, JButton> e : buttonMap.entrySet())
		{
			Rectangle r = SwingUtilities.convertRectangle(e.getValue().getParent(), e.getValue().getBounds(), this);
			if (r.contains(p))
			{
				return e.getKey();
			}
		}
		return null;
	}

	// --- Pin / Unpin / Hide / Unhide API (drag integration later) ---

	public void pin(NavigationButton navBtn)
	{
		if (!pinningEnabled)
		{
			return;
		}
		if (navBtn == null || hiddenButtons.contains(navBtn) || pinnedButtons.contains(navBtn))
		{
			return;
		}
		sortedButtons.remove(navBtn);
		pinnedButtons.add(navBtn);
		String tt = safeTooltip(navBtn);
		if (!pinnedOrderTooltips.contains(tt))
		{
			pinnedOrderTooltips.add(tt);
		}
		reorderPinnedBySavedOrder();
		rebuildSortedPanel();
		saveState();
	}

	public void unpin(NavigationButton navBtn)
	{
		if (navBtn == null || !pinnedButtons.remove(navBtn))
		{
			return;
		}
		pinnedOrderTooltips.remove(safeTooltip(navBtn));
		sortedButtons.add(navBtn);
		Collections.sort(sortedButtons, NavigationButton.COMPARATOR);
		rebuildSortedPanel();
		saveState();
	}

	public boolean isPinned(NavigationButton navBtn)
	{
		return pinnedButtons.contains(navBtn);
	}

	public void hide(NavigationButton navBtn)
	{
		if (!hidingEnabled)
		{
			return;
		}
		if (navBtn == null || hiddenButtons.contains(navBtn))
		{
			return;
		}
		pinnedButtons.remove(navBtn);
		pinnedOrderTooltips.remove(safeTooltip(navBtn));
		sortedButtons.remove(navBtn);
		hiddenButtons.add(navBtn);
		hiddenTooltips.add(safeTooltip(navBtn));
		if (buttonMap.containsKey(navBtn))
		{
			rebuildSortedPanel();
		}
		if (selectedNavigation == navBtn)
		{
			setSelectedNavigation(null);
		}
		updateHiddenButton();
		revalidate();
		repaint();
		saveState();
	}

	public void unhide(NavigationButton navBtn)
	{
		if (navBtn == null || !hiddenButtons.remove(navBtn))
		{
			return;
		}
		hiddenTooltips.remove(safeTooltip(navBtn));
		// restore as sorted (user may re-pin later)
		sortedButtons.add(navBtn);
		Collections.sort(sortedButtons, NavigationButton.COMPARATOR);
		rebuildSortedPanel();
		updateHiddenButton();
		saveState();
	}

	public void addChangeListener(ChangeListener l)
	{
		if (l != null)
		{
			changeListeners.add(l);
		}
	}

	public void removeChangeListener(ChangeListener l)
	{
		changeListeners.remove(l);
	}

	private void fireChangeEvent(NavigationButton oldSel, NavigationButton newSel)
	{
		ChangeEvent ev = new ChangeEvent(this);
		for (ChangeListener l : new ArrayList<>(changeListeners))
		{
			l.stateChanged(ev);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		// When a panel is selected, keep the wider content width similar to previous implementation.
		// When no panel is selected (collapsed), return the navigation area's preferred width
		// so the sidebar can shrink to only show the buttons.
		if (selectedNavigation == null)
		{
			Dimension navPref = navigationButtonsPanel.getPreferredSize();
			int w = navPref == null ? 0 : navPref.width;
			if (w <= 0)
			{
				// fallback minimal width for just buttons
				w = 40;
			}
			return new Dimension(w, super.getPreferredSize().height);
		}

		return new Dimension(DEFAULT_WIDTH, super.getPreferredSize().height);
	}

	// --- Internal helpers ---

	private void rebuildSortedPanel()
	{
		if (DEBUG)
		{
			LOGGER.debug("rebuildSortedPanel: pinned={} sorted={} hidden={}", pinnedButtons.size(), sortedButtons.size(), hiddenButtons.size());
		}
		pinnedButtonsPanel.removeAll();
		// No rigid placeholder here; pinned area sizing is handled by getPreferredSize()
		sortedButtonsPanel.removeAll();
		buttonMap.clear();
		int visiblePinned = 0;
		for (NavigationButton nb : pinnedButtons)
		{
			if (!hiddenButtons.contains(nb))
			{
				JButton b = buildButton(nb);
				pinnedButtonsPanel.add(b);
				buttonMap.put(nb, b);
				visiblePinned++;
			}
		}
		for (NavigationButton nb : sortedButtons)
		{
			if (!hiddenButtons.contains(nb))
			{
				JButton b = buildButton(nb);
				sortedButtonsPanel.add(b);
				buttonMap.put(nb, b);
			}
		}

		// If there are no visible pinned buttons, show a small pin placeholder.
		// Keep the static placeholder visible even while dragging, unless the
		// insertion placeholder is currently visible *inside* the pinned area
		// (in which case the insertion placeholder takes its place).
		if (pinningEnabled && visiblePinned == 0)
		{
			boolean insertionInPinned = insertionPlaceholder != null && insertionPlaceholder.getParent() == pinnedButtonsPanel;
			if (!insertionInPinned)
			{
				pinnedButtonsPanel.add(createPinPlaceholder(false));
			}
		}
		else if (!pinningEnabled)
		{
			removeStaticPinPlaceholder();
		}

		// Ensure newly-built buttons reflect current selection (stripe + selected state)
		for (Map.Entry<NavigationButton, JButton> e : buttonMap.entrySet())
		{
			boolean sel = e.getKey() == selectedNavigation;
			e.getValue().setSelected(sel);
			updateButtonStripe(e.getValue(), sel);
		}
		updateHiddenButton();
		pinnedButtonsPanel.setVisible(pinningEnabled);
		java.awt.Component pinnedParent = pinnedButtonsPanel.getParent();
		if (pinnedParent != null)
		{
			pinnedParent.setVisible(pinningEnabled);
		}
		pinnedButtonsPanel.revalidate();
		sortedButtonsPanel.revalidate();
		repaint();
	}

	private JButton buildButton(NavigationButton navBtn)
	{
		if (DEBUG)
		{
			LOGGER.debug("buildButton: tooltip={}", safeTooltip(navBtn));
		}
		Icon icon = new ImageIcon(ImageUtil.resizeImage(navBtn.getIcon(), ICON_SIZE, ICON_SIZE));
		PaintedButton btn = new PaintedButton(icon);
		btn.setToolTipText(navBtn.getTooltip());
		btn.setHorizontalAlignment(JButton.LEFT);
		// FlatLaf style is left as a fallback but we fully control painting above.
		btn.putClientProperty(FlatClientProperties.STYLE, "iconTextGap:8; focusWidth:0; margin:0,0,0,0;");
		// initial border (stripe will be applied by setSelectedNavigation / rebuildSortedPanel)
		// increase vertical padding by 1px each side (+2px total height)
		// reduce right inset by 1px to make buttons 1px less wide (adjusted)
		// reduce right inset by 1px to offset the navigation panel's left border
		btn.setBorder(new javax.swing.border.EmptyBorder(5,8,5,6));
		// Action listener (keyboard or programmatic activation) — perform toggle/select
		btn.addActionListener((ActionEvent e) -> {
			if (!isDragging)
			{
				handleButtonClick(navBtn);
			}
		});

		// Drag vs click logic
		final Point[] pressPoint = {null};
		final boolean[] dragging = {false};
		btn.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				pressPoint[0] = e.getPoint();
				dragging[0] = false;
				if (DEBUG)
				{
					LOGGER.debug("mousePressed: nav={}, point={}", safeTooltip(navBtn), pressPoint[0]);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (pressPoint[0] == null)
				{
					return;
				}
				if (!isDragAllowed())
				{
					pressPoint[0] = null;
					dragging[0] = false;
					return;
				}
				int dx = e.getX() - pressPoint[0].x;
				int dy = e.getY() - pressPoint[0].y;
				double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
				// If a global drag is active, ignore this local release — finishDrag will run from the AWT listener
				if (isDragging)
				{
					pressPoint[0] = null;
					dragging[0] = false;
					return;
				}
				Point dropPoint = SwingUtilities.convertPoint(btn, e.getPoint(), SortableJTabbedPane.this);
				if (DEBUG)
				{
					LOGGER.debug("mouseReleased: nav={}, dist={}, dragging={}, dropPoint={}", safeTooltip(navBtn), dist, dragging[0], dropPoint);
				}
				if (dragging[0] || dist > DRAG_THRESHOLD)
				{
					// handle drop/reorder when a drag happened or pointer moved beyond threshold
					handleDrop(navBtn, dropPoint);
				}
				pressPoint[0] = null;
				dragging[0] = false;
			}
		});
		btn.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (!isDragAllowed())
				{
					return;
				}
				if (pressPoint[0] != null && !dragging[0])
				{
					int dx = e.getX() - pressPoint[0].x;
					int dy = e.getY() - pressPoint[0].y;
					double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
					if (dist > DRAG_THRESHOLD)
					{
						dragging[0] = true;
						// initiate global drag (ghost + placeholder)
						SortableJTabbedPane.this.startDrag(navBtn, e.getLocationOnScreen());
					}
				}
			}
		});

			// Hover handling via model/listener to update the custom-painted background
			btn.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (!isDragging)
					{
						btn.setHover(true);
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					btn.setHover(false);
				}
			});
		return btn;
	}

	private static String panelKey(NavigationButton nb)
	{
		return Integer.toHexString(System.identityHashCode(nb));
	}

	private void handleDrop(NavigationButton navBtn, Point dropPoint)
	{
		if (!isDragAllowed())
		{
			return;
		}
		if (DEBUG)
		{
			LOGGER.debug("handleDrop: nav={}, dropPoint={}", safeTooltip(navBtn), dropPoint);
		}
		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedButtonsPanel.getParent(), pinnedButtonsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedButtonsPanel.getParent(), sortedButtonsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenButtonsPanel.getParent(), hiddenButtonsPanel.getBounds(), this);

		boolean wasPinned = pinnedButtons.contains(navBtn);

		if (hiddenBounds.contains(dropPoint) && hidingEnabled)
		{
			if (DEBUG)
			{
				LOGGER.debug("handleDrop: dropping into hidden zone -> hide {}", safeTooltip(navBtn));
			}
			hide(navBtn);
			return;
		}

		if (pinnedBounds.contains(dropPoint) && pinningEnabled)
		{
			int targetIndex = computePinnedInsertionIndex(dropPoint.y);
			if (DEBUG)
			{
				LOGGER.debug("handleDrop: inserting into pinned at index {}", targetIndex);
			}
			if (!wasPinned)
			{
				// when pinning, the panel needs to be added to pinnedButtons; pin() will rebuild
				pin(navBtn); // adds to end then reorder
			}
			// reorder within pinned
			pinnedButtons.remove(navBtn);
			if (targetIndex < 0 || targetIndex > pinnedButtons.size())
			{
				pinnedButtons.add(navBtn);
			}
			else
			{
				pinnedButtons.add(targetIndex, navBtn);
			}
			rebuildSortedPanel();
			reorderPinnedBySavedOrderFromList();
			saveState();
			//setSelectedNavigation(navBtn);
			return;
		}

		if (sortedBounds.contains(dropPoint))
		{
			if (DEBUG)
			{
				LOGGER.debug("handleDrop: dropping into sorted area (wasPinned={})", wasPinned);
			}
			if (wasPinned)
			{
				unpin(navBtn);
			}
			//setSelectedNavigation(navBtn);
		}
	}

	private int computePinnedInsertionIndex(int y)
	{
		int idx = 0;
		for (NavigationButton nb : pinnedButtons)
		{
			// skip the button currently being dragged; treat it as removed for index calculations
			if (nb == draggingNavigation)
			{
				continue;
			}
			JButton b = buttonMap.get(nb);
			if (b == null)
			{
				continue;
			}
			if (!b.isVisible())
			{
				// skip invisible components; they shouldn't count towards insertion index
				continue;
			}
			Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), this);
			int midY = r.y + r.height / 2;
			if (y < midY)
			{
				return idx;
			}
			idx++;
		}
		if (DEBUG)
		{
			LOGGER.debug("computePinnedInsertionIndex: y={} -> idx={}", y, idx);
		}
		return idx; // append
	}

	/**
	 * Compute insertion index for a given panel based on Y coordinate (in this component's coords).
	 */
	private int computeInsertionIndexForPanel(JPanel panel, int y)
	{
		int idx = 0;
		for (Component c : panel.getComponents())
		{
			// skip invisible components (do not increment index for them)
			if (!c.isVisible())
			{
				continue;
			}
			Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), this);
			int midY = r.y + r.height / 2;
			if (y < midY)
			{
				return idx;
			}
			idx++;
		}
		return idx;
	}

	private void startDrag(NavigationButton navBtn, Point pressScreen)
	{
		if (navBtn == null || isDragging || !isDragAllowed())
		{
			return;
		}
		draggingNavigation = navBtn;
		// if dragging from hidden popup, unhide first so model/UI contains the button
		if (hiddenButtons.contains(navBtn))
		{
			unhide(navBtn);
		}

		// Leave the static pin placeholder in place here; updateDrag will remove
		// it only when the insertion placeholder is placed into the pinned area.
		pinnedButtonsPanel.revalidate();
		pinnedButtonsPanel.repaint();
		draggingButton = buttonMap.get(navBtn);
		dragOffset = new Point(8, 8);
		// remember source parent/index so we can restore on cancel
		if (draggingButton != null)
		{
			dragSourceParent = draggingButton.getParent();
			try
			{
				dragSourceIndex = dragSourceParent.getComponentZOrder(draggingButton);
			}
			catch (Exception ex)
			{
				dragSourceIndex = -1;
			}
		}

		// create placeholder sized like the button
		Dimension prefSize = draggingButton != null ? draggingButton.getPreferredSize() : new Dimension(ICON_SIZE + 12, 24);
		insertionPlaceholder = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				Dimension d = super.getPreferredSize();
				d.width = Short.MAX_VALUE;
				return d;
			}
		};
		// prefer to only constrain height; allow width to be governed by container to avoid expanding sidebar
		insertionPlaceholder.setPreferredSize(new Dimension(0, prefSize.height));
		insertionPlaceholder.setMinimumSize(new Dimension(0, prefSize.height));
		insertionPlaceholder.setBorder(new javax.swing.border.MatteBorder(1, 0, 1, 0, new Color(0x555555)));
		insertionPlaceholder.setOpaque(true);
		insertionPlaceholder.setBackground(new Color(0x333333));

		// Add a small icon to the placeholder so the drop target shows the dragged icon
		try
		{
			Icon phIcon = new ImageIcon(ImageUtil.resizeImage(navBtn.getIcon(), ICON_SIZE, ICON_SIZE));
			javax.swing.JLabel iconLabel = new javax.swing.JLabel(phIcon);
			iconLabel.setOpaque(false);
			// match the button border insets so placeholder aligns with buttons
			iconLabel.setBorder(new javax.swing.border.EmptyBorder(5, 8, 5, 7));
			insertionPlaceholder.setLayout(new BorderLayout());
			insertionPlaceholder.add(iconLabel, BorderLayout.WEST);
		}
		catch (Exception ignore)
		{
			// best-effort only
		}

		// insert placeholder where the button was (if visible in UI)
		if (draggingButton != null && dragSourceParent != null)
		{
			try
			{
				if (dragSourceIndex >= 0 && dragSourceIndex <= dragSourceParent.getComponentCount())
				{
					dragSourceParent.add(insertionPlaceholder, dragSourceIndex);
				}
				else
				{
					dragSourceParent.add(insertionPlaceholder);
				}
				// hide the original button while dragging so layout remains stable
				draggingButton.setVisible(false);
				dragSourceParent.revalidate();
				dragSourceParent.repaint();
			}
			catch (Exception ex)
			{
				// ignore and continue
			}
		}

		// create drag ghost window
		try
		{
			int w = prefSize.width;
			int h = prefSize.height;
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				// draw icon if available (navBtn.getIcon() may be an Image/BufferedImage)
				Object icoObj = navBtn.getIcon();
				if (icoObj instanceof java.awt.Image)
				{
					int ix = 8;
					int iy = (h - ICON_SIZE) / 2;
					g.drawImage((java.awt.Image) icoObj, ix, iy, ICON_SIZE, ICON_SIZE, null);
				}
				else if (icoObj instanceof Icon)
				{
					int ix = 8;
					int iy = (h - ICON_SIZE) / 2;
					((Icon) icoObj).paintIcon(null, g, ix, iy);
				}
			}
			finally
			{
				g.dispose();
			}

			javax.swing.JLabel ghostLabel = new javax.swing.JLabel(new ImageIcon(img));
			ghostLabel.setOpaque(false);
			// ensure releasing the mouse over the ghost finishes the drag
			ghostLabel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseReleased(MouseEvent e)
				{
					finishDrag(e.getLocationOnScreen());
				}
			});

			Window owner = SwingUtilities.getWindowAncestor(this);
			dragGhostWindow = new javax.swing.JWindow(owner);
			dragGhostWindow.getContentPane().add(ghostLabel);
			dragGhostWindow.pack();
			dragGhostWindow.setBackground(new Color(0, 0, 0, 0));
			dragGhostWindow.setAlwaysOnTop(true);
			// compute offset relative to original button if available
			if (draggingButton != null)
			{
				try
				{
					Point btnOnScreen = draggingButton.getLocationOnScreen();
					dragOffset = new Point(pressScreen.x - btnOnScreen.x, pressScreen.y - btnOnScreen.y);
				}
				catch (IllegalComponentStateException ex)
				{
					dragOffset = new Point(8, 8);
				}
			}
			else
			{
				dragOffset = new Point(ghostLabel.getWidth() / 2, ghostLabel.getHeight() / 2);
			}

			dragGhostWindow.setLocation(pressScreen.x - dragOffset.x - 10, pressScreen.y - dragOffset.y);
			dragGhostWindow.setVisible(true);
		}
		catch (Exception ex)
		{
			// ignore ghost creation failures
		}

		// global listener to follow mouse and finish on release
		globalDragListener = (AWTEvent ev) ->
		{
			if (!(ev instanceof MouseEvent))
			{
				return;
			}
			MouseEvent me = (MouseEvent) ev;
			int id = me.getID();
			if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED)
			{
				updateDrag(me.getLocationOnScreen());
			}
			else if (id == MouseEvent.MOUSE_RELEASED)
			{
				finishDrag(me.getLocationOnScreen());
			}
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(globalDragListener, AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
		isDragging = true;
		// Ensure UI reflects drag state (show hidden zone and expand pinned drop target)
		updateHiddenButton();
		pinnedButtonsPanel.revalidate();
		navigationButtonsPanel.revalidate();
		revalidate();
		repaint();
	}

	private void updateDrag(Point screenPoint)
	{
		if (!isDragging)
		{
			return;
		}
		if (!isDragAllowed())
		{
			return;
		}
		if (dragGhostWindow != null && screenPoint != null)
		{
			dragGhostWindow.setLocation(screenPoint.x - dragOffset.x -10, screenPoint.y - dragOffset.y);
		}

		// determine which area we're over and update placeholder position
		Point p = new Point(screenPoint);
		SwingUtilities.convertPointFromScreen(p, this);
		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedButtonsPanel.getParent(), pinnedButtonsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedButtonsPanel.getParent(), sortedButtonsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenButtonsPanel.getParent(), hiddenButtonsPanel.getBounds(), this);

		boolean overHidden = hidingEnabled && hiddenBounds.contains(p);
		boolean overPinned = pinningEnabled && pinnedBounds.contains(p);
		boolean overSorted = sortedBounds.contains(p);

		// Highlight hidden area when hovering it (no placeholder shown there)
		if (overHidden)
		{
			showHiddenHighlight(true);
			showSortedHighlight(false);
			// remove placeholder if present
			if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
			{
				try
				{
					insertionPlaceholder.getParent().remove(insertionPlaceholder);
				}
				catch (Exception ex)
				{
					// ignore
				}
				// ensure the ghost is visible again when placeholder is removed
				if (dragGhostWindow != null)
				{
					try
					{
						dragGhostWindow.setVisible(true);
					}
					catch (Exception ex)
					{
						// ignore
					}
				}

				// If there are no other visible pinned buttons, show the static pin placeholder
				addStaticPinPlaceholder();
			}
			currentTargetPanel = null;
			currentTargetIndex = -1;
			return;
		}
		else
		{
			showHiddenHighlight(false);
		}

		// If over pinned area, show insertion placeholder (reorder allowed)
		if (overPinned)
		{
			showSortedHighlight(false);
			int idx = computeInsertionIndexForPanel(pinnedButtonsPanel, p.y);
			if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
			{
				insertionPlaceholder.getParent().remove(insertionPlaceholder);
			}

			// Remove any static pin placeholder we added earlier so the insertion placeholder
			// can take its place while dragging
			removeStaticPinPlaceholder();
			if (idx < 0 || idx > pinnedButtonsPanel.getComponentCount())
			{
				pinnedButtonsPanel.add(insertionPlaceholder);
				currentTargetIndex = pinnedButtonsPanel.getComponentCount() - 1;
			}
			else
			{
				pinnedButtonsPanel.add(insertionPlaceholder, idx);
				currentTargetIndex = idx;
			}
			currentTargetPanel = pinnedButtonsPanel;
			pinnedButtonsPanel.revalidate();
			pinnedButtonsPanel.repaint();
			// hide the ghost while the insertion placeholder is shown
			if (dragGhostWindow != null)
			{
				try
				{
					dragGhostWindow.setVisible(false);
				}
				catch (Exception ex)
				{
					// ignore
				}
			}
			return;
		}

		// If over sorted area, highlight the area but do not show placeholder (sorted order enforced)
		if (overSorted)
		{
			showSortedHighlight(true);
			if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
			{
				try
				{
					insertionPlaceholder.getParent().remove(insertionPlaceholder);
				}
				catch (Exception ex)
				{
					// ignore
				}
				// ensure the ghost is visible when placeholder is removed
				if (dragGhostWindow != null)
				{
					try
					{
						dragGhostWindow.setVisible(true);
					}
					catch (Exception ex)
					{
						// ignore
					}
				}

				// If there are no other visible pinned buttons, show the static pin placeholder
				addStaticPinPlaceholder();
			}
			currentTargetPanel = sortedButtonsPanel;
			currentTargetIndex = -1;
			sortedButtonsPanel.revalidate();
			sortedButtonsPanel.repaint();
			return;
		}

		// Not over any target — remove placeholder and highlights
		if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
		{
			try
			{
				insertionPlaceholder.getParent().remove(insertionPlaceholder);
			}
			catch (Exception ex)
			{
				// ignore
			}
			if (dragGhostWindow != null)
			{
				try
				{
					dragGhostWindow.setVisible(true);
				}
				catch (Exception ex)
				{
					// ignore
				}
			}

			// If there are no other visible pinned buttons, show the static pin placeholder
			addStaticPinPlaceholder();
		}
		currentTargetPanel = null;
		currentTargetIndex = -1;
		showHiddenHighlight(false);
		showSortedHighlight(false);
		revalidate();
		repaint();
	}

	private void finishDrag(Point screenPoint)
	{
		if (!isDragging)
		{
			return;
		}
		// remove global listener
		try
		{
			if (globalDragListener != null)
			{
				Toolkit.getDefaultToolkit().removeAWTEventListener(globalDragListener);
			}
		}
		catch (Exception ex)
		{
			// ignore
		}
		globalDragListener = null;

		// hide ghost
		try
		{
			if (dragGhostWindow != null)
			{
				dragGhostWindow.setVisible(false);
				dragGhostWindow.dispose();
			}
		}
		catch (Exception ex)
		{
			// ignore
		}
		dragGhostWindow = null;

		// compute drop point in component coords
		Point p = new Point(screenPoint);
		SwingUtilities.convertPointFromScreen(p, this);

		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedButtonsPanel.getParent(), pinnedButtonsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedButtonsPanel.getParent(), sortedButtonsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenButtonsPanel.getParent(), hiddenButtonsPanel.getBounds(), this);

		boolean droppedInZone = pinnedBounds.contains(p) || sortedBounds.contains(p) || hiddenBounds.contains(p);

		// remove placeholder from UI
		if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
		{
			insertionPlaceholder.getParent().remove(insertionPlaceholder);
		}

		// restore the original button visibility
		if (draggingButton != null)
		{
			try
			{
				draggingButton.setVisible(true);
				if (dragSourceParent != null)
				{
					dragSourceParent.revalidate();
					dragSourceParent.repaint();
				}
			}
			catch (Exception ex)
			{
				// ignore
			}
		}

		// If dropped in a valid zone, delegate to existing handleDrop which updates model & rebuilds
		if (droppedInZone)
		{
			try
			{
				handleDrop(draggingNavigation, p);
			}
			catch (Exception ex)
			{
				LOGGER.debug("finishDrag: handleDrop failed", ex);
			}
		}

		// cleanup
		// remove any highlights
		showHiddenHighlight(false);
		showSortedHighlight(false);
		insertionPlaceholder = null;
		draggingNavigation = null;
		draggingButton = null;
		dragSourceParent = null;
		dragSourceIndex = -1;
		dragOffset = null;
		isDragging = false;
		currentTargetPanel = null;
		currentTargetIndex = -1;

		// Ensure UI collapses/hides zones that should not be visible after drag
		updateHiddenButton();
		pinnedButtonsPanel.revalidate();
		navigationButtonsPanel.revalidate();
		revalidate();
		repaint();
	}

	private void showHiddenHighlight(boolean show)
	{
		java.awt.Component parent = hiddenButtonsPanel.getParent();
		if (!(parent instanceof javax.swing.JComponent))
		{
			return;
		}
		javax.swing.JComponent parentComp = (javax.swing.JComponent) parent;
		if (!hidingEnabled)
		{
			parentComp.setBackground(BUTTON_BG);
			parentComp.setOpaque(true);
			hiddenZoneButton.setForcedBackground(null);
			hiddenZoneButton.setHover(false);
			parentComp.revalidate();
			parentComp.repaint();
			hiddenZoneButton.revalidate();
			hiddenZoneButton.repaint();
			return;
		}
		if (show)
		{
			parentComp.setOpaque(true);
			parentComp.setBackground(HIGHLIGHT_HIDDEN_COLOR);

			// Also tint the hidden-zone button itself so the cue is visible on the button
			hiddenZoneButton.setForcedBackground(HIGHLIGHT_HIDDEN_COLOR);
		}
		else
		{
			// restore to the consistent BUTTON_BG
			parentComp.setBackground(BUTTON_BG);
			parentComp.setOpaque(true);
			hiddenZoneButton.setForcedBackground(null);
			hiddenZoneButton.setHover(false);
		}
		parentComp.revalidate();
		parentComp.repaint();
		hiddenZoneButton.revalidate();
		hiddenZoneButton.repaint();
	}

	/**
	 * Add a static pin placeholder to the pinned buttons panel if there are no
	 * other visible pinned buttons (excluding the one being dragged).
	 */
	private JPanel createPinPlaceholder(boolean enforceHeight)
	{
		final int innerGap = 1;
		final int outerMargin = 3;
		final float stroke = 1f;
		final float[] dash = {2f, 2f};
		final int dashedBoxSize = ICON_SIZE + innerGap * 2;
		final int phHeight = dashedBoxSize + outerMargin * 2;

		JPanel placeholderPanel = new JPanel(new BorderLayout())
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				try
				{
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
					int w = getWidth();
					int h = getHeight();
					int x = (w - dashedBoxSize) / 2;
					int y = (h - dashedBoxSize) / 2;
					g2.drawRect(x, y, dashedBoxSize, dashedBoxSize);
					g2.dispose();
				}
				catch (Exception ex)
				{
					// ignore painting errors
				}
			}
		};
		placeholderPanel.setOpaque(false);

		javax.swing.JLabel pinLabel = new javax.swing.JLabel(pinPlaceholderIcon);
		pinLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		pinLabel.setToolTipText("Drag here to pin");
		pinLabel.setOpaque(false);
		placeholderPanel.add(pinLabel, BorderLayout.CENTER);
		placeholderPanel.putClientProperty("pinPlaceholder", Boolean.TRUE);

		if (enforceHeight)
		{
			placeholderPanel.setPreferredSize(new Dimension(0, phHeight));
			placeholderPanel.setMinimumSize(new Dimension(0, phHeight));
			placeholderPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, phHeight));
		}

		placeholderPanel.setBorder(new javax.swing.border.EmptyBorder(outerMargin, outerMargin, outerMargin, outerMargin));
		return placeholderPanel;
	}

	private void addStaticPinPlaceholder()
	{
		if (!pinningEnabled)
		{
			return;
		}
		// don't add if already present
		for (Component c : pinnedButtonsPanel.getComponents())
		{
			if (c instanceof javax.swing.JComponent && Boolean.TRUE.equals(((javax.swing.JComponent) c).getClientProperty("pinPlaceholder")))
			{
				return;
			}
		}

		// count visible pinned buttons excluding the one being dragged
		int visiblePinned = 0;
		for (NavigationButton nb : pinnedButtons)
		{
			if (hiddenButtons.contains(nb))
			{
				continue;
			}
			if (nb == draggingNavigation)
			{
				continue;
			}
			visiblePinned++;
		}
		if (visiblePinned > 0)
		{
			return;
		}

		JPanel placeholderPanel = createPinPlaceholder(true);
		pinnedButtonsPanel.add(placeholderPanel);
		pinnedButtonsPanel.revalidate();
		pinnedButtonsPanel.repaint();
	}

	private void removeStaticPinPlaceholder()
	{
		for (Component c : pinnedButtonsPanel.getComponents())
		{
			if (c instanceof javax.swing.JComponent && Boolean.TRUE.equals(((javax.swing.JComponent) c).getClientProperty("pinPlaceholder")))
			{
				try
				{
					pinnedButtonsPanel.remove(c);
				}
				catch (Exception ex)
				{
				}
				break;
			}
		}
		pinnedButtonsPanel.revalidate();
		pinnedButtonsPanel.repaint();
	}

	private void showSortedHighlight(boolean show)
	{
		if (show)
		{
			sortedButtonsPanel.setOpaque(true);
			sortedButtonsPanel.setBackground(HIGHLIGHT_SORTED_COLOR);

			// Tint each button in the sorted area so the highlight is visible
			for (Component c : sortedButtonsPanel.getComponents())
			{
				if (c instanceof JButton)
				{
					JButton b = (JButton) c;
					if (b instanceof PaintedButton)
					{
						((PaintedButton) b).setForcedBackground(HIGHLIGHT_SORTED_COLOR);
					}
					else
					{
						b.setOpaque(true);
						b.setBackground(HIGHLIGHT_SORTED_COLOR);
					}
					b.revalidate();
					b.repaint();
				}
			}
			sortedButtonsPanel.revalidate();
			sortedButtonsPanel.repaint();
		}
		else
		{
			// restore to consistent BUTTON_BG
			sortedButtonsPanel.setBackground(BUTTON_BG);
			sortedButtonsPanel.setOpaque(true);
			for (Component c : sortedButtonsPanel.getComponents())
			{
				if (c instanceof JButton)
				{
					JButton b = (JButton) c;
					if (b instanceof PaintedButton)
					{
						((PaintedButton) b).setForcedBackground(null);
					}
					else
					{
						b.setOpaque(true);
						b.setBackground(BUTTON_BG);
					}
					b.revalidate();
					b.repaint();
				}
			}
			sortedButtonsPanel.revalidate();
			sortedButtonsPanel.repaint();
		}
	}

	/**
	 * Update button border to show a left stripe when selected.
	 */
	private void updateButtonStripe(JButton b, boolean selected)
	{
		if (b == null)
		{
			return;
		}
		// Keep a consistent inset so widths remain stable whether selected or not.
		// The visual orange stripe is painted on the navigation panel (on top),
		// so buttons themselves should not change background when selected.
		b.setBorder(new javax.swing.border.EmptyBorder(5, 8, 5, 6));
	}

	/**
	 * Handle a user click on a navigation button: toggle if already selected.
	 */
	private void handleButtonClick(NavigationButton navBtn)
	{
		if (navBtn == selectedNavigation)
		{
			setSelectedNavigation(null);
		}
		else
		{
			setSelectedNavigation(navBtn);
		}
	}

	private void reorderPinnedBySavedOrderFromList()
	{
		// Rewrite saved order based on current pinnedButtons sequence
		pinnedOrderTooltips.clear();
		for (NavigationButton nb : pinnedButtons)
		{
			pinnedOrderTooltips.add(safeTooltip(nb));
		}
		if (DEBUG)
		{
			LOGGER.debug("reorderPinnedBySavedOrderFromList: newOrder={}", pinnedOrderTooltips);
		}
	}

	private void reorderPinnedBySavedOrder()
	{
		// sort pinnedButtons according to pinnedOrderTooltips list order
		pinnedButtons.sort((a, b) -> Integer.compare(pinnedOrderTooltips.indexOf(safeTooltip(a)), pinnedOrderTooltips.indexOf(safeTooltip(b))));
		if (DEBUG)
		{
			LOGGER.debug("reorderPinnedBySavedOrder: order={}", pinnedOrderTooltips);
		}
	}

	private void updateHiddenButton()
	{
		if (!hidingEnabled)
		{
			hiddenZoneButton.setVisible(false);
			java.awt.Component parent = hiddenButtonsPanel.getParent();
			if (parent != null)
			{
				parent.setVisible(false);
			}
			hiddenButtonsPanel.setVisible(false);
			return;
		}
		int count = hiddenButtons.size();
		boolean visible = count > 0 || isDragging;

		hiddenZoneButton.setText(null);
		hiddenZoneButton.setToolTipText(count + " hidden");
		hiddenZoneButton.setVisible(visible);
		hiddenZoneButton.setForeground(new Color(255,255,255));

		// Show the left selection stripe on the hidden-zone button when the
		// currently selected navigation is one of the hidden items. Also set
		// the button's selected state so FlatLaf styles apply consistently.
		boolean selectedHidden = selectedNavigation != null && hiddenButtons.contains(selectedNavigation);
		hiddenZoneButton.setSelected(selectedHidden);
		updateButtonStripe(hiddenZoneButton, selectedHidden);

		// Also toggle visibility of the hidden wrapper (parent) so it doesn't occupy
		// space when there are no hidden items and we're not dragging.
		java.awt.Component parent = hiddenButtonsPanel.getParent();
		if (parent != null)
		{
			parent.setVisible(visible);
		}
		hiddenButtonsPanel.setVisible(visible);

		if (DEBUG)
		{
			LOGGER.debug("updateHiddenButton: count={}, visible={}, selectedHidden={}", count, visible, selectedHidden);
		}
	}

	private void showHiddenPopup()
	{
		if (!hidingEnabled)
		{
			return;
		}
		if (hiddenButtons.isEmpty())
		{
			return;
		}
		if (DEBUG)
		{
			LOGGER.debug("showHiddenPopup: items={}", hiddenButtons.size());
		}

		Window owner = SwingUtilities.getWindowAncestor(this);
		final JDialog popup = owner == null ? new JDialog() : new JDialog(owner);
		popup.setUndecorated(true);
		popup.setModal(false);
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// light gray border around popup
		panel.setBorder(new javax.swing.border.LineBorder(new Color(0xCCCCCC), 1));

		// Order hidden items to match sidebar ordering:
		// 1) pinned order (from saved `pinnedOrderTooltips`), 2) the same sorted order (NavigationButton.COMPARATOR)
		List<NavigationButton> orderedHidden = new ArrayList<>();

		// Add hidden items that are in the saved pinned order first
		for (String tt : pinnedOrderTooltips)
		{
			for (NavigationButton nb : hiddenButtons)
			{
				if (safeTooltip(nb).equals(tt) && !orderedHidden.contains(nb))
				{
					orderedHidden.add(nb);
				}
			}
		}

		// Add the remaining hidden items in the default sorted order
		List<NavigationButton> remaining = new ArrayList<>(hiddenButtons);
		remaining.removeAll(orderedHidden);
		remaining.sort(NavigationButton.COMPARATOR);
		orderedHidden.addAll(remaining);

		for (NavigationButton nb : orderedHidden)
		{
			Icon icon = new ImageIcon(ImageUtil.resizeImage(nb.getIcon(), ICON_SIZE, ICON_SIZE));
			PaintedButton item = new PaintedButton(icon);
			item.setToolTipText(nb.getTooltip());
			item.setHorizontalAlignment(JButton.CENTER);
			// make button compact
			item.putClientProperty(FlatClientProperties.STYLE, "iconTextGap:8; focusWidth:0; margin:0,0,0,0;");
			item.setBorder(new javax.swing.border.EmptyBorder(4,4,4,4));

			// Hover handling for popup items updates the custom-painted hover state
			item.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (!isDragging)
					{
						item.setHover(true);
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					item.setHover(false);
				}
			});
			Dimension pref = item.getPreferredSize();
			Dimension fixed = new Dimension(ICON_SIZE + 12, pref.height);
			item.setPreferredSize(fixed);
			item.setMaximumSize(fixed);
			// show selected stripe if this panel is currently active
			if (nb == selectedNavigation)
			{
				item.setBorder(new javax.swing.border.CompoundBorder(
					new javax.swing.border.MatteBorder(0, 3, 0, 0, SELECTED_STRIPE_COLOR),
					new javax.swing.border.EmptyBorder(4, 4, 4, 4)
				));
			}
			else
			{
				item.setBorder(new javax.swing.border.EmptyBorder(4,4,4,4));
			}

			// Click should activate the panel without un-hiding it
			// Use the shared toggle handler so clicking the already-active
			// hidden item will deactivate (collapse) the panel like regular buttons.
			item.addActionListener(e -> {
				if (!isDragging)
				{
					handleButtonClick(nb);
					popup.setVisible(false);
					popup.dispose();
				}
			});

			// Drag handling using screen coordinates so dragging out of the popup works
			final Point[] pressScreen = {null};
			final boolean[] dragging = {false};
			item.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					pressScreen[0] = e.getLocationOnScreen();
					dragging[0] = false;
					if (DEBUG)
					{
						LOGGER.debug("hiddenPopup mousePressed: nav={}, screenPoint={}", safeTooltip(nb), pressScreen[0]);
					}
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (pressScreen[0] == null)
					{
						return;
					}
					if (dragging[0])
					{
						// already handled in drag
						pressScreen[0] = null;
						dragging[0] = false;
						return;
					}
					// Click activation is handled by the button's ActionListener.
					// Here we only need to clear press/drag state to avoid
					// double-handling (ActionListener + mouseReleased).
					pressScreen[0] = null;
					dragging[0] = false;
				}
			});
			item.addMouseMotionListener(new MouseMotionAdapter()
			{
				@Override
				public void mouseDragged(MouseEvent e)
				{
					if (pressScreen[0] != null && !dragging[0])
					{
						int dx = e.getXOnScreen() - pressScreen[0].x;
						int dy = e.getYOnScreen() - pressScreen[0].y;
						double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
						if (dist > DRAG_THRESHOLD)
						{
							dragging[0] = true;
							// start the global drag (ghost + placeholder) and close the popup
							SortableJTabbedPane.this.startDrag(nb, pressScreen[0]);
							popup.setVisible(false);
							popup.dispose();
						}
					}
				}
			});

			panel.add(item);
		}

		popup.getContentPane().add(panel);
		popup.pack();

		// position the popup near the hidden button (so it appears over the sidebar)
		try
		{
			Point btnOnScreen = hiddenZoneButton.getLocationOnScreen();
			int x = btnOnScreen.x - popup.getWidth();
			// prefer showing the popup above the hidden button
			int y = btnOnScreen.y - popup.getHeight();
			// fallback to below the button if not enough space above
			if (y < 0)
			{
				y = btnOnScreen.y + hiddenZoneButton.getHeight();
			}
			if (x < 0)
			{
				x = btnOnScreen.x + hiddenZoneButton.getWidth();
			}
			popup.setLocation(x, y);
		}
		catch (IllegalComponentStateException ex)
		{
			// component not showing on screen; fallback to positioning relative to this component
			Point loc = hiddenZoneButton.getLocation();
			SwingUtilities.convertPointToScreen(loc, hiddenZoneButton);
			popup.setLocation(loc);
		}

		// Make popup focusable and try to bring it to front so it remains visible during drag
		popup.setFocusableWindowState(true);
		popup.setAlwaysOnTop(true);
		popup.setVisible(true);

		// hide popup on focus loss (behaves like a popup)
		popup.addWindowFocusListener(new WindowAdapter()
		{
			@Override
			public void windowLostFocus(WindowEvent e)
			{
				popup.setVisible(false);
				popup.dispose();
			}
		});

		try
		{
			popup.toFront();
			popup.requestFocus();
		}
		catch (Exception ignore)
		{
		}
	}

	private void loadState()
	{
		if (configManager == null)
		{
			return;
		}
		pinnedOrderTooltips.clear();
		hiddenTooltips.clear();
		String pinnedCsv = configManager.getConfiguration(CONFIG_GROUP, CONFIG_PINNED);
		if ((pinnedCsv == null || pinnedCsv.isEmpty()))
		{
			// Fallback for legacy storage under the runelite group
			pinnedCsv = configManager.getConfiguration("runelite", "clientSidebarPinned");
		}
		if (pinnedCsv != null && !pinnedCsv.isEmpty())
		{
			for (String tok : pinnedCsv.split(","))
			{
				if (!tok.isEmpty())
				{
					// Attempt to decode stored token (new encoded format). If decoding
					// fails, treat token as legacy raw tooltip and keep it as-is.
					pinnedOrderTooltips.add(decodeFromStorage(tok));
				}
			}
		}
		String hiddenCsv = configManager.getConfiguration(CONFIG_GROUP, CONFIG_HIDDEN);
		if ((hiddenCsv == null || hiddenCsv.isEmpty()))
		{
			hiddenCsv = configManager.getConfiguration("runelite", "clientSidebarHidden");
		}
		if (hiddenCsv != null && !hiddenCsv.isEmpty())
		{
			for (String tok : hiddenCsv.split(","))
			{
				if (!tok.isEmpty())
				{
					hiddenTooltips.add(decodeFromStorage(tok));
				}
			}
		}
		if (DEBUG)
		{
			LOGGER.debug("loadState: pinnedCsv={} hiddenCsv={}", pinnedCsv, hiddenCsv);
		}
	}

	private void saveState()
	{
		if (configManager == null)
		{
			return;
		}
		// Encode tooltips before persisting so commas and other special
		// characters don't break the CSV parsing on load.
		String pinnedCsv = pinnedOrderTooltips.stream()
			.map(SortableJTabbedPane::encodeForStorage)
			.collect(Collectors.joining(","));
		String hiddenCsv = hiddenTooltips.stream()
			.map(SortableJTabbedPane::encodeForStorage)
			.collect(Collectors.joining(","));
		if (pinnedCsv.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_PINNED);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_PINNED, pinnedCsv);
		}
		if (hiddenCsv.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_HIDDEN);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_HIDDEN, hiddenCsv);
		}
		if (DEBUG)
		{
			LOGGER.debug("saveState: pinnedCsv={} hiddenCsv={}", pinnedCsv, hiddenCsv);
		}
	}

	private static String encodeForStorage(String s)
	{
		if (s == null)
		{
			return "";
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String decodeFromStorage(String tok)
	{
		if (tok == null || tok.isEmpty())
		{
			return tok;
		}
		try
		{
			byte[] decoded = Base64.getUrlDecoder().decode(tok);
			return new String(decoded, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex)
		{
			// Not a valid base64 token — treat as legacy raw tooltip
			return tok;
		}
	}

	private static String safeTooltip(NavigationButton nb)
	{
		if (nb == null)
		{
			return "<null>";
		}
		String tt = nb.getTooltip();
		return tt == null ? "<untitled>" : tt;
	}

}
