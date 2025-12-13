package net.runelite.client.ui;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.LayerUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.formdev.flatlaf.FlatClientProperties;
import lombok.Getter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.sortablebuttons.SortableButtonsConfig;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;

/**
 * A replacement for the previous JTabbedPane-based sidebar which renders
 * navigation buttons (pinned and sorted) as a single vertical list and
 * manages plugin panels via a CardLayout.
 */
public class SortableJTabbedPane extends JPanel
{
	// Spacing / sizing
	// ToDo: remove remaining magic values in the code and replace with constants
	private static final int ICON_SIZE = 16;
	private static final int MIN_PINNED_HEIGHT = 24;
	private static final int DEFAULT_WIDTH = 274; // approximate previous sidebar panel width (reduced by 26px)
	private static final int DRAG_THRESHOLD = 8; // pixels (euclidean)
	
	// Colors
	// ToDo: verify the correct colors are being used
	// ToDo: ensure no hardcoded colors are used unless defined here, see if there is a colorscheme constant we can use instead
	private static final Color SELECTED_STRIPE_COLOR = new Color(0xFF7700);
    static final Color BUTTON_BG = new Color(0x1e1e1e);
	private static final Color HIGHLIGHT_HIDDEN_COLOR = new Color(0x444444);
	private static final Color HIGHLIGHT_SORTED_COLOR = new Color(0x3A3A3A);
	
	// Parameter keys for config persistence
	private final ConfigManager configManager;
	private static final String CONFIG_GROUP = SortableButtonsConfig.GROUP;
	private static final String CONFIG_PINNED = SortableButtonsConfig.PINNED_ORDER_KEY;
	private static final String CONFIG_HIDDEN = SortableButtonsConfig.HIDDEN_BUTTONS_KEY;

	// Logging / debug
	// ToDo: implement logging consistent with other classes in the project
	private static final boolean DEBUG = Boolean.getBoolean("runelite.debug.ui");
	private static final Logger LOGGER = LoggerFactory.getLogger(SortableJTabbedPane.class);
	
	// Feature flags controlled by the Sortable Buttons plugin config
	private boolean pinningEnabled = true;
	private boolean hidingEnabled = true;

	// Panel for the plugin content area
    private final JPanel sidebarContentPanel = new JPanel();
	
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel footerHolder_DeleteMe = new JPanel();
	
	// Button Panels
	private final JPanel navigationButtonsPanel = new JPanel();
	private final JPanel sortedPluginsPanel = new JPanel();
	private final JPanel hiddenPluginsPanel = new JPanel();
	private final JPanel pinnedPluginsPanel = new JPanel()
	{
		// ToDo: can't we just set the preferred size directly instead of overriding this?
		@Override
		public Dimension getPreferredSize()
		{
			// When pinning is disabled, collapse pinned area to zero height
			if (!pinningEnabled)
			{
				return new Dimension(0, 0);
			}

			Dimension size = super.getPreferredSize();
			if (size == null)
			{
				size = new Dimension(0, MIN_PINNED_HEIGHT);
			}
			else if (size.height < MIN_PINNED_HEIGHT)
			{
				size.height = MIN_PINNED_HEIGHT;
			}

			return size;
		}
	};

	// Data structures
	// ToDo: verify all of these are needed, some seem redundant
	private final List<NavigationButton> pinnedButtons = new ArrayList<>();
	private final List<NavigationButton> sortedButtons = new ArrayList<>();
	private final Map<NavigationButton, JButton> buttonMap = new LinkedHashMap<>();
	private final Map<NavigationButton, PluginPanel> panelMap = new LinkedHashMap<>();
	private final List<ChangeListener> changeListeners = new ArrayList<>();
	private final List<String> pinnedButtonTooltips = new ArrayList<>();
	private final Set<String> hiddenButtonTooltips = new LinkedHashSet<>();
	private final List<NavigationButton> hiddenButtons = new ArrayList<>();

	// Button to show the list of hidden buttons in a popup
	private PaintedButton btnShowHiddenButtonsPopup;

	// ToDo: why is this a private getter? 
	@Getter
	private NavigationButton selectedNavigation;

	// Window used to display the button being dragged
	private javax.swing.JWindow dragGhostWindow;

	// Placeholder panel shown while a button is being dragged to indicate the insertion point
	private JPanel insertionPlaceholder;

	// Currently-dragged button
	private JButton buttonBeingDragged;

	// Whether a button drag operation is in progress
	boolean isButtonBeingDragged()
	{
		return buttonBeingDragged != null;
	}

	// Whether drag-and-drop reordering is currently allowed
	private boolean isDragAllowed()
	{
		return pinningEnabled || hidingEnabled;
	}

	// Parent container of the button being dragged (used to restore on cancel)
	private Container originOfButtonBeingDragged;

	// Dragging state (ghost + placeholder)
	// ToDo: Figure out what these variables are used for
	private AWTEventListener globalDragListener;
	private NavigationButton draggingNavigation;
	private int dragSourceIndex = -1;
	private Point dragOffset;

	public SortableJTabbedPane(ConfigManager configManager)
	{
		this.configManager = configManager;
		
		InitializeUIComponents();

		// Load persisted state if possible
		loadState();

		if (DEBUG)
		{
			LOGGER.debug("SortableJTabbedPane initialized (debug=true), configManager: {}", configManager);
		}
	}

	private void InitializeUIComponents()
	{
		// Initialize main panel
		setLayout(new BorderLayout());
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Initialize the navigation buttons panel
		navigationButtonsPanel.setLayout(new BorderLayout());
		navigationButtonsPanel.setOpaque(true);
		navigationButtonsPanel.setBackground(BUTTON_BG);
		navigationButtonsPanel.setBorder(new javax.swing.border.MatteBorder(0, 1, 0, 0, new Color(0x171717)));

		// Initialize the pinned buttons panel and add to navigation panel
		JPanel pinnedPluginsPanelWrapper = CreatePinnedPluginsPanel();
		navigationButtonsPanel.add(pinnedPluginsPanelWrapper, BorderLayout.NORTH);

		// Initialize the sorted buttons panel
		sortedPluginsPanel.setBackground(BUTTON_BG);
		sortedPluginsPanel.setLayout(new BoxLayout(sortedPluginsPanel, BoxLayout.Y_AXIS));
		sortedPluginsPanel.setOpaque(true);
		sortedPluginsPanel.setBackground(BUTTON_BG);
		navigationButtonsPanel.add(sortedPluginsPanel, BorderLayout.CENTER);

		// ToDo: dont delete this, it is required when custom window chrome is disabled
		// We still need to account for these additional buttons in our layout
		footerHolder_DeleteMe.setLayout(new BorderLayout());
		footerHolder_DeleteMe.setOpaque(false);

		// Initialize the hidden buttons panel
		JPanel hiddenPluginsPanel = CreateHiddenPluginsPanel();
		navigationButtonsPanel.add(hiddenPluginsPanel, BorderLayout.SOUTH);

		// Initialize the plugin content panel
		sidebarContentPanel.setLayout(cardLayout);
		sidebarContentPanel.setOpaque(false);
		sidebarContentPanel.add(new JPanel(), "__empty__");
		add(sidebarContentPanel, BorderLayout.CENTER);

		// ToDo: would be cleaner to add this to a dedicated button component which draws the stripe when the button is active
		LayerUI<JComponent> activePluginButtonIndicator = new LayerUI<JComponent>()
		{
			@Override
			public void paint(Graphics graphic, JComponent component)
			{
				// Paint the view first, then draw overlay
				super.paint(graphic, component);

				JButton activeButton = GetButtonOfActivePanel();

				// If a button is active, draw the left-side orange stripe
				if (activeButton != null)
				{
					Graphics activePanelIndicatorGraphic = graphic.create();
					try
					{
						activePanelIndicatorGraphic.setColor(SELECTED_STRIPE_COLOR);					
						Rectangle bounds = SwingUtilities.convertRectangle(activeButton, btnShowHiddenButtonsPopup.getBounds(), component);
						activePanelIndicatorGraphic.fillRect(0, bounds.y, 3, bounds.height);
					}
					finally
					{
						activePanelIndicatorGraphic.dispose();
					}
				}
			}
		};

		JLayer<JComponent> navigationButtonsWrapper = new JLayer<>(navigationButtonsPanel, activePluginButtonIndicator);
		add(navigationButtonsWrapper, BorderLayout.EAST);

		// Provide style hints similar to old tabs (used by FlatLaf theming)
		// ToDo: verify if these are still needed with custom painting
		putClientProperty(FlatClientProperties.STYLE, "tabHeight:26");
	}

	private JButton GetButtonOfActivePanel()
	{
		if (selectedNavigation == null)
			return null;

		// If any of the hidden plugins is selected, return the hidden-zone button
		if (hiddenButtons.contains(selectedNavigation))
			return btnShowHiddenButtonsPopup;
		else
			return buttonMap.get(selectedNavigation);
	}

	private JPanel CreatePinnedPluginsPanel()
	{
		// Initialize a wrapper panel for pinned buttons
		JPanel pinnedPluginsPanelWrapper = new JPanel(new BorderLayout());
		pinnedPluginsPanelWrapper.setOpaque(true);

		// Initialize the panel which holds the pinned buttons
		pinnedPluginsPanel.setLayout(new BoxLayout(pinnedPluginsPanel, BoxLayout.Y_AXIS));
		pinnedPluginsPanel.setOpaque(true);
		pinnedPluginsPanel.setBackground(BUTTON_BG);
		pinnedPluginsPanel.setMinimumSize(new Dimension(0, 0));
		pinnedPluginsPanelWrapper.add(pinnedPluginsPanel, BorderLayout.CENTER);
		
		// Add separator below pinned buttons area
		JSeparator separator = new JSeparator();
		separator.setPreferredSize(new Dimension(0, 2));
		pinnedPluginsPanelWrapper.add(separator, BorderLayout.SOUTH);

		return pinnedPluginsPanelWrapper;
	}

	private JPanel CreateHiddenPluginsPanel() {
		// Initialize a wrapper panel for hidden buttons
		JPanel hiddenPluginsPanelWrapper = new JPanel(new BorderLayout());
		hiddenPluginsPanelWrapper.setOpaque(true);

		// Add separator at the top of hidden plugins panel
		JSeparator separator = new JSeparator();
		separator.setPreferredSize(new Dimension(0, 1));
		hiddenPluginsPanelWrapper.add(separator, BorderLayout.NORTH);

		// Initialize the panel which holds the button to show the popup for hidden plugins
		hiddenPluginsPanel.setLayout(new BorderLayout());
		hiddenPluginsPanel.setOpaque(true);
		hiddenPluginsPanel.setBackground(BUTTON_BG);
		hiddenPluginsPanel.setMinimumSize(new Dimension(0, MIN_PINNED_HEIGHT));
		hiddenPluginsPanel.setPreferredSize(new Dimension(0, MIN_PINNED_HEIGHT));
		hiddenPluginsPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, MIN_PINNED_HEIGHT));
		hiddenPluginsPanel.add(CreateHiddenPluginsPopupButton(), BorderLayout.CENTER);
		hiddenPluginsPanelWrapper.add(hiddenPluginsPanel, BorderLayout.CENTER);

		return hiddenPluginsPanelWrapper;
	}

	private PaintedButton CreateHiddenPluginsPopupButton() {
		// Initialize the button which shows the popup for hidden buttons
		PaintedButton hiddenPluginsPopupButton = new PaintedButton(this);
		hiddenPluginsPopupButton.setVisible(false);
		hiddenPluginsPopupButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0; iconTextGap:8;");
		hiddenPluginsPopupButton.setBorder(new javax.swing.border.EmptyBorder(3,8,3,8));
		hiddenPluginsPopupButton.addActionListener(e -> showHiddenPopup());
		
		// ToDo: clean this mess up, loading can be done more cleanly and the variable names dont make sense
		BufferedImage bi = ImageUtil.loadImageResource(LootTrackerPlugin.class, "invisible_icon.png");
		ImageIcon sharedHiddenIcon = null;
		if (bi != null)
		{
			sharedHiddenIcon = new ImageIcon(ImageUtil.resizeImage(bi, ICON_SIZE, ICON_SIZE));
			hiddenPluginsPopupButton.setIcon(sharedHiddenIcon);
		}
		else
		{
			// fallback: use text if the image resource cannot be found
			hiddenPluginsPopupButton.setText("X");
		}

		// ToDo: figure our if this is needed, I'd expect this to be native to swing buttons
		// hiddenZoneButton uses custom painting; update hover state instead of
		// manipulating background so LAF cannot override visuals.
		hiddenPluginsPopupButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!isButtonBeingDragged() && !hiddenPluginsPopupButton.isSelected())
				{
					hiddenPluginsPopupButton.setHover(true);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hiddenPluginsPopupButton.setHover(false);
			}
		});

		return hiddenPluginsPopupButton;
	}

	// ToDo: This needs to be implemented in some way, we need to be able to show the additional buttons when custom window chrome is disabled
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

	// ToDo: Inspect how this is used and interacts with loadState()
	public void applySortableConfig(boolean pluginEnabled, boolean allowPinning, boolean allowHiding)
	{
		boolean previousDragAllowed = isDragAllowed();
		pinningEnabled = pluginEnabled && allowPinning;
		hidingEnabled = pluginEnabled && allowHiding;
		loadState();

		if (isButtonBeingDragged() && previousDragAllowed && !isDragAllowed())
		{
			Point mouseLocation = new Point(0, 0);
			try
			{
				PointerInfo pointerInfo = MouseInfo.getPointerInfo();
				if (pointerInfo != null)
				{
					mouseLocation = pointerInfo.getLocation();
				}
			}
			catch (Exception ignore)
			{
			}
			// ToDo: whis is this handled here?
			finishDrag(mouseLocation);
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
		for (NavigationButton navigationButton : allButtons)
		{
			String buttonTooltip = serializeTooltip(navigationButton);
			if (hidingEnabled && hiddenButtonTooltips.contains(buttonTooltip))
			{
				hiddenButtons.add(navigationButton);
				continue;
			}
			if (pinningEnabled && pinnedButtonTooltips.contains(buttonTooltip))
			{
				pinnedButtons.add(navigationButton);
				continue;
			}
			sortedButtons.add(navigationButton);
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
		pinnedPluginsPanel.remove(btn);
		sortedPluginsPanel.remove(btn);
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
			LOGGER.debug("removeNavigation: removed tooltip={}", serializeTooltip(navBtn));
		}
	}

	/**
	 * Set currently selected navigation. May be null to deselect.
	 */
	public void setSelectedNavigation(NavigationButton navBtn)
	{
		// Do not change selection while a drag operation is in progress
		if (isButtonBeingDragged())
		{
			// Todo: this should not be reachable; set selection only happens on button click
			return;
		}
		if (navBtn != null && (!buttonMap.containsKey(navBtn) || !panelMap.containsKey(navBtn)))
		{
			// ToDo: this shoudn't happen, but if it does we should just attempt to register the button instead
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
		Container parent = getParent();
		if (parent != null)
		{
			parent.revalidate();
		}
		repaint();
		if (DEBUG)
		{
			LOGGER.debug("setSelectedNavigation: from={} to={}", serializeTooltip(old), serializeTooltip(navBtn));
		}
	}

	/**
	 * Get the navigation button at a given point relative to this component.
	 */
	public NavigationButton getEntryAtPoint(Point point)
	{
		for (Map.Entry<NavigationButton, JButton> entry : buttonMap.entrySet())
		{
			Rectangle bounds = SwingUtilities.convertRectangle(entry.getValue().getParent(), entry.getValue().getBounds(), this);
			if (bounds.contains(point))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	// --- Pin / Unpin / Hide / Unhide

	public void pin(NavigationButton navBtn)
	{
		if (!pinningEnabled)
		{
			return;
		}
		if (navBtn == null || hiddenButtons.contains(navBtn) || pinnedButtons.contains(navBtn))
		{
			// ToDo: return if hidden buttons contains this entry? that's incorrect, it should be possible move a hidden button to pinned
			return;
		}
		sortedButtons.remove(navBtn);
		pinnedButtons.add(navBtn);
		String tooltip = serializeTooltip(navBtn);
		if (!pinnedButtonTooltips.contains(tooltip))
		{
			pinnedButtonTooltips.add(tooltip);
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
		pinnedButtonTooltips.remove(serializeTooltip(navBtn));
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
		pinnedButtonTooltips.remove(serializeTooltip(navBtn));
		sortedButtons.remove(navBtn);
		hiddenButtons.add(navBtn);
		hiddenButtonTooltips.add(serializeTooltip(navBtn));
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
		hiddenButtonTooltips.remove(serializeTooltip(navBtn));
		// restore as sorted (user may re-pin later)
		sortedButtons.add(navBtn);
		Collections.sort(sortedButtons, NavigationButton.COMPARATOR);
		rebuildSortedPanel();
		updateHiddenButton();
		saveState();
	}

	public void addChangeListener(ChangeListener listener)
	{
		if (listener != null)
		{
			changeListeners.add(listener);
		}
	}

	public void removeChangeListener(ChangeListener listener)
	{
		changeListeners.remove(listener);
	}

	private void fireChangeEvent(NavigationButton previousSelection, NavigationButton newSelection)
	{
		ChangeEvent event = new ChangeEvent(this);
		for (ChangeListener listener : new ArrayList<>(changeListeners))
		{
			listener.stateChanged(event);
		}
	}

	// ToDo: this can probably be handled automatically without overriding the preferred size
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
		pinnedPluginsPanel.removeAll();
		// No rigid placeholder here; pinned area sizing is handled by getPreferredSize()
		sortedPluginsPanel.removeAll();
		buttonMap.clear();
		int visiblePinned = 0;
		for (NavigationButton nb : pinnedButtons)
		{
			if (!hiddenButtons.contains(nb))
			{
				JButton b = buildButton(nb);
				pinnedPluginsPanel.add(b);
				buttonMap.put(nb, b);
				visiblePinned++;
			}
		}
		for (NavigationButton nb : sortedButtons)
		{
			if (!hiddenButtons.contains(nb))
			{
				JButton b = buildButton(nb);
				sortedPluginsPanel.add(b);
				buttonMap.put(nb, b);
			}
		}

		// If there are no visible pinned buttons, show a small pin placeholder.
		// Keep the static placeholder visible even while dragging, unless the
		// insertion placeholder is currently visible *inside* the pinned area
		// (in which case the insertion placeholder takes its place).
		if (pinningEnabled && visiblePinned == 0)
		{
			boolean insertionInPinned = insertionPlaceholder != null && insertionPlaceholder.getParent() == pinnedPluginsPanel;
			if (!insertionInPinned)
			{
				pinnedPluginsPanel.add(createPinPlaceholder(false));
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
		pinnedPluginsPanel.setVisible(pinningEnabled);
		java.awt.Component pinnedParent = pinnedPluginsPanel.getParent();
		if (pinnedParent != null)
		{
			pinnedParent.setVisible(pinningEnabled);
		}
		pinnedPluginsPanel.revalidate();
		sortedPluginsPanel.revalidate();
		repaint();
	}

	// ToDo: a lot of this code can most likely be moved to the PaintedButton class itself
	private JButton buildButton(NavigationButton navBtn)
	{
		if (DEBUG)
		{
			LOGGER.debug("buildButton: tooltip={}", serializeTooltip(navBtn));
		}
		Icon icon = new ImageIcon(ImageUtil.resizeImage(navBtn.getIcon(), ICON_SIZE, ICON_SIZE));
		PaintedButton btn = new PaintedButton(this, icon);
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
			if (!isButtonBeingDragged())
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
					LOGGER.debug("mousePressed: nav={}, point={}", serializeTooltip(navBtn), pressPoint[0]);
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
				if (isButtonBeingDragged())
				{
					pressPoint[0] = null;
					dragging[0] = false;
					return;
				}
				Point dropPoint = SwingUtilities.convertPoint(btn, e.getPoint(), SortableJTabbedPane.this);
				if (DEBUG)
				{
					LOGGER.debug("mouseReleased: nav={}, dist={}, dragging={}, dropPoint={}", serializeTooltip(navBtn), dist, dragging[0], dropPoint);
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
					if (!isButtonBeingDragged())
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

	private static String panelKey(NavigationButton navigationButton)
	{
		return Integer.toHexString(System.identityHashCode(navigationButton));
	}

	private void handleDrop(NavigationButton navBtn, Point dropPoint)
	{
		if (!isDragAllowed())
		{
			return;
		}
		if (DEBUG)
		{
			LOGGER.debug("handleDrop: nav={}, dropPoint={}", serializeTooltip(navBtn), dropPoint);
		}
		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedPluginsPanel.getParent(), pinnedPluginsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedPluginsPanel.getParent(), sortedPluginsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenPluginsPanel.getParent(), hiddenPluginsPanel.getBounds(), this);

		boolean wasPinned = pinnedButtons.contains(navBtn);

		if (hiddenBounds.contains(dropPoint) && hidingEnabled)
		{
			if (DEBUG)
			{
				LOGGER.debug("handleDrop: dropping into hidden zone -> hide {}", serializeTooltip(navBtn));
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
		if (navBtn == null || isButtonBeingDragged() || !isDragAllowed())
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
		pinnedPluginsPanel.revalidate();
		pinnedPluginsPanel.repaint();
		buttonBeingDragged = buttonMap.get(navBtn);
		dragOffset = new Point(8, 8);
		// remember source parent/index so we can restore on cancel
		if (buttonBeingDragged != null)
		{
			originOfButtonBeingDragged = buttonBeingDragged.getParent();
			try
			{
				dragSourceIndex = originOfButtonBeingDragged.getComponentZOrder(buttonBeingDragged);
			}
			catch (Exception ex)
			{
				dragSourceIndex = -1;
			}
		}

		// create placeholder sized like the button
		Dimension prefSize = buttonBeingDragged != null ? buttonBeingDragged.getPreferredSize() : new Dimension(ICON_SIZE + 12, 24);
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
		if (buttonBeingDragged != null && originOfButtonBeingDragged != null)
		{
			try
			{
				if (dragSourceIndex >= 0 && dragSourceIndex <= originOfButtonBeingDragged.getComponentCount())
				{
					originOfButtonBeingDragged.add(insertionPlaceholder, dragSourceIndex);
				}
				else
				{
					originOfButtonBeingDragged.add(insertionPlaceholder);
				}
				// hide the original button while dragging so layout remains stable
				buttonBeingDragged.setVisible(false);
				originOfButtonBeingDragged.revalidate();
				originOfButtonBeingDragged.repaint();
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
			if (buttonBeingDragged != null)
			{
				try
				{
					Point btnOnScreen = buttonBeingDragged.getLocationOnScreen();
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
		// Ensure UI reflects drag state (show hidden zone and expand pinned drop target)
		updateHiddenButton();
		pinnedPluginsPanel.revalidate();
		navigationButtonsPanel.revalidate();
		revalidate();
		repaint();
	}

	private void updateDrag(Point screenPoint)
	{
		if (!isButtonBeingDragged())
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
		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedPluginsPanel.getParent(), pinnedPluginsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedPluginsPanel.getParent(), sortedPluginsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenPluginsPanel.getParent(), hiddenPluginsPanel.getBounds(), this);

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
			int idx = computeInsertionIndexForPanel(pinnedPluginsPanel, p.y);
			if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
			{
				insertionPlaceholder.getParent().remove(insertionPlaceholder);
			}

			// Remove any static pin placeholder we added earlier so the insertion placeholder
			// can take its place while dragging
			removeStaticPinPlaceholder();
			if (idx < 0 || idx > pinnedPluginsPanel.getComponentCount())
			{
				pinnedPluginsPanel.add(insertionPlaceholder);
			}
			else
			{
				pinnedPluginsPanel.add(insertionPlaceholder, idx);
			}
			pinnedPluginsPanel.revalidate();
			pinnedPluginsPanel.repaint();
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
			sortedPluginsPanel.revalidate();
			sortedPluginsPanel.repaint();
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
		showHiddenHighlight(false);
		showSortedHighlight(false);
		revalidate();
		repaint();
	}

	private void finishDrag(Point screenPoint)
	{
		if (!isButtonBeingDragged())
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

		Rectangle pinnedBounds = SwingUtilities.convertRectangle(pinnedPluginsPanel.getParent(), pinnedPluginsPanel.getBounds(), this);
		Rectangle sortedBounds = SwingUtilities.convertRectangle(sortedPluginsPanel.getParent(), sortedPluginsPanel.getBounds(), this);
		Rectangle hiddenBounds = SwingUtilities.convertRectangle(hiddenPluginsPanel.getParent(), hiddenPluginsPanel.getBounds(), this);

		boolean droppedInZone = pinnedBounds.contains(p) || sortedBounds.contains(p) || hiddenBounds.contains(p);

		// remove placeholder from UI
		if (insertionPlaceholder != null && insertionPlaceholder.getParent() != null)
		{
			insertionPlaceholder.getParent().remove(insertionPlaceholder);
		}

		// restore the original button visibility
		if (buttonBeingDragged != null)
		{
			try
			{
				buttonBeingDragged.setVisible(true);
				if (originOfButtonBeingDragged != null)
				{
					originOfButtonBeingDragged.revalidate();
					originOfButtonBeingDragged.repaint();
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
		buttonBeingDragged = null;
		originOfButtonBeingDragged = null;
		dragSourceIndex = -1;
		dragOffset = null;

		// Ensure UI collapses/hides zones that should not be visible after drag
		updateHiddenButton();
		pinnedPluginsPanel.revalidate();
		navigationButtonsPanel.revalidate();
		revalidate();
		repaint();
	}

	private void showHiddenHighlight(boolean show)
	{
		java.awt.Component parent = hiddenPluginsPanel.getParent();
		if (!(parent instanceof javax.swing.JComponent))
		{
			return;
		}
		javax.swing.JComponent parentComp = (javax.swing.JComponent) parent;
		if (!hidingEnabled)
		{
			parentComp.setBackground(BUTTON_BG);
			parentComp.setOpaque(true);
			btnShowHiddenButtonsPopup.setForcedBackground(null);
			btnShowHiddenButtonsPopup.setHover(false);
			parentComp.revalidate();
			parentComp.repaint();
			btnShowHiddenButtonsPopup.revalidate();
			btnShowHiddenButtonsPopup.repaint();
			return;
		}
		if (show)
		{
			parentComp.setOpaque(true);
			parentComp.setBackground(HIGHLIGHT_HIDDEN_COLOR);

			// Also tint the hidden-zone button itself so the cue is visible on the button
			btnShowHiddenButtonsPopup.setForcedBackground(HIGHLIGHT_HIDDEN_COLOR);
		}
		else
		{
			// restore to the consistent BUTTON_BG
			parentComp.setBackground(BUTTON_BG);
			parentComp.setOpaque(true);
			btnShowHiddenButtonsPopup.setForcedBackground(null);
			btnShowHiddenButtonsPopup.setHover(false);
		}
		parentComp.revalidate();
		parentComp.repaint();
		btnShowHiddenButtonsPopup.revalidate();
		btnShowHiddenButtonsPopup.repaint();
	}

	// ToDo: this should probably be part of the `CreatePinnedPluginsPanel` method
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
			protected void paintComponent(Graphics graphics)
			{
				super.paintComponent(graphics);
				try
				{
					Graphics2D g2 = (Graphics2D) graphics.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
					int w = getWidth();
					int h = getHeight();
					int x = (w - dashedBoxSize) / 2;
					int y = (h - dashedBoxSize) / 2;
					g2.drawRect(x, y, dashedBoxSize, dashedBoxSize);
					// ToDo: dispose should be in try-finally
					g2.dispose();
				}
				catch (Exception ex)
				{
					// ignore painting errors
				}
			}
		};
		placeholderPanel.setOpaque(false);

		// ToDo: refactor image loading, this implementation is not ideal
		Icon pinPlaceholderIcon;
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
		else
		{
			// fallback: use an empty transparent image so the icon is always defined
			pinPlaceholderIcon = new ImageIcon(new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB));
		}

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

	// ToDo: why is this a separate method from createPinPlaceholder?
	private void addStaticPinPlaceholder()
	{
		if (!pinningEnabled)
		{
			return;
		}
		// don't add if already present
		for (Component c : pinnedPluginsPanel.getComponents())
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
		pinnedPluginsPanel.add(placeholderPanel);
		pinnedPluginsPanel.revalidate();
		pinnedPluginsPanel.repaint();
	}

	// ToDo: this doesn't seem logical, we most likely just want to hide the placeholder
	private void removeStaticPinPlaceholder()
	{
		for (Component component : pinnedPluginsPanel.getComponents())
		{
			// ToDo: 🤮
			if (component instanceof javax.swing.JComponent && Boolean.TRUE.equals(((javax.swing.JComponent) component).getClientProperty("pinPlaceholder")))
			{
				try
				{
					pinnedPluginsPanel.remove(component);
				}
				catch (Exception ex)
				{
				}
				break;
			}
		}
		pinnedPluginsPanel.revalidate();
		pinnedPluginsPanel.repaint();
	}

	// ToDo: we can probably have a single method for showing drag and drop highlights
	private void showSortedHighlight(boolean showHighlight)
	{
		if (showHighlight)
		{
			sortedPluginsPanel.setOpaque(true);
			sortedPluginsPanel.setBackground(HIGHLIGHT_SORTED_COLOR);

			// Tint each button in the sorted area so the highlight is visible
			for (Component component : sortedPluginsPanel.getComponents())
			{
				if (component instanceof JButton)
				{
					JButton button = (JButton) component;
					if (button instanceof PaintedButton)
					{
						((PaintedButton) button).setForcedBackground(HIGHLIGHT_SORTED_COLOR);
					}
					else
					{
						button.setOpaque(true);
						button.setBackground(HIGHLIGHT_SORTED_COLOR);
					}
					button.revalidate();
					button.repaint();
				}
			}
			sortedPluginsPanel.revalidate();
			sortedPluginsPanel.repaint();
		}
		else
		{
			// restore to consistent BUTTON_BG
			sortedPluginsPanel.setBackground(BUTTON_BG);
			sortedPluginsPanel.setOpaque(true);
			for (Component component : sortedPluginsPanel.getComponents())
			{
				if (component instanceof JButton)
				{
					JButton button = (JButton) component;
					if (button instanceof PaintedButton)
					{
						((PaintedButton) button).setForcedBackground(null);
					}
					else
					{
						button.setOpaque(true);
						button.setBackground(BUTTON_BG);
					}
					button.revalidate();
					button.repaint();
				}
			}
			sortedPluginsPanel.revalidate();
			sortedPluginsPanel.repaint();
		}
	}

	//ToDo: what is this? The active-button indicator is already done in a different method, so what is this used for?
	/**
	 * Update button border to show a left stripe when selected.
	 */
	private void updateButtonStripe(JButton button, boolean selected)
	{
		if (button == null)
		{
			return;
		}
		// Keep a consistent inset so widths remain stable whether selected or not.
		// The visual orange stripe is painted on the navigation panel (on top),
		// so buttons themselves should not change background when selected.
		button.setBorder(new javax.swing.border.EmptyBorder(5, 8, 5, 6));
	}

	// ToDo: what is this for, seems like a dumb wrapper around setSelectedNavigation?
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
		pinnedButtonTooltips.clear();
		for (NavigationButton navigationButton : pinnedButtons)
		{
			pinnedButtonTooltips.add(serializeTooltip(navigationButton));
		}
		if (DEBUG)
		{
			LOGGER.debug("reorderPinnedBySavedOrderFromList: newOrder={}", pinnedButtonTooltips);
		}
	}

	// ToDo: maybe we can just put these in an ordered list instead of sorting each time?
	private void reorderPinnedBySavedOrder()
	{
		// sort pinnedButtons according to pinnedOrderTooltips list order
		pinnedButtons.sort((a, b) -> Integer.compare(pinnedButtonTooltips.indexOf(serializeTooltip(a)), pinnedButtonTooltips.indexOf(serializeTooltip(b))));
		if (DEBUG)
		{
			LOGGER.debug("reorderPinnedBySavedOrder: order={}", pinnedButtonTooltips);
		}
	}

	private void updateHiddenButton()
	{
		if (!hidingEnabled)
		{
			btnShowHiddenButtonsPopup.setVisible(false);
			java.awt.Component parent = hiddenPluginsPanel.getParent();
			if (parent != null)
			{
				parent.setVisible(false);
			}
			hiddenPluginsPanel.setVisible(false);
			return;
		}
		int count = hiddenButtons.size();
		boolean visible = count > 0 || isButtonBeingDragged();

		btnShowHiddenButtonsPopup.setText(null);
		btnShowHiddenButtonsPopup.setToolTipText(count + " hidden");
		btnShowHiddenButtonsPopup.setVisible(visible);
		btnShowHiddenButtonsPopup.setForeground(new Color(255,255,255));

		// Show the left selection stripe on the hidden-zone button when the
		// currently selected navigation is one of the hidden items. Also set
		// the button's selected state so FlatLaf styles apply consistently.
		boolean selectedHidden = selectedNavigation != null && hiddenButtons.contains(selectedNavigation);
		btnShowHiddenButtonsPopup.setSelected(selectedHidden);
		updateButtonStripe(btnShowHiddenButtonsPopup, selectedHidden);

		// Also toggle visibility of the hidden wrapper (parent) so it doesn't occupy
		// space when there are no hidden items and we're not dragging.
		java.awt.Component parent = hiddenPluginsPanel.getParent();
		if (parent != null)
		{
			parent.setVisible(visible);
		}
		hiddenPluginsPanel.setVisible(visible);

		if (DEBUG)
		{
			LOGGER.debug("updateHiddenButton: count={}, visible={}, selectedHidden={}", count, visible, selectedHidden);
		}
	}

	// ToDo: seems to contain a lot of duplicated code which is also used for non-hidden buttons
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
		for (String tooltip : pinnedButtonTooltips)
		{
			for (NavigationButton navButton : hiddenButtons)
			{
				if (serializeTooltip(navButton).equals(tooltip) && !orderedHidden.contains(navButton))
				{
					orderedHidden.add(navButton);
				}
			}
		}

		// Add the remaining hidden items in the default sorted order
		List<NavigationButton> remaining = new ArrayList<>(hiddenButtons);
		remaining.removeAll(orderedHidden);
		remaining.sort(NavigationButton.COMPARATOR);
		orderedHidden.addAll(remaining);

		for (NavigationButton navButton : orderedHidden)
		{
			// This is pretty ugly, PaintedButton should probably be a wrapper around NavigationButton
			Icon icon = new ImageIcon(ImageUtil.resizeImage(navButton.getIcon(), ICON_SIZE, ICON_SIZE));
			PaintedButton item = new PaintedButton(this, icon);
			item.setToolTipText(navButton.getTooltip());
			item.setHorizontalAlignment(JButton.CENTER);
			item.putClientProperty(FlatClientProperties.STYLE, "iconTextGap:8; focusWidth:0; margin:0,0,0,0;");
			item.setBorder(new javax.swing.border.EmptyBorder(4,4,4,4));

			// Hover handling for popup items updates the custom-painted hover state
			item.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (!isButtonBeingDragged())
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
			Dimension preferredSize = item.getPreferredSize();
			Dimension maxSize = new Dimension(ICON_SIZE + 12, preferredSize.height);
			item.setPreferredSize(maxSize);
			item.setMaximumSize(maxSize);

			// show selected stripe if this panel is currently active
			// ToDo: this should also be included in the PaintedButton class if possible (like stated above)
			if (navButton == selectedNavigation)
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
				if (!isButtonBeingDragged())
				{
					handleButtonClick(navButton);
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
						LOGGER.debug("hiddenPopup mousePressed: nav={}, screenPoint={}", serializeTooltip(navButton), pressScreen[0]);
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
				public void mouseDragged(MouseEvent event)
				{
					// ToDo: why is this is an array?
					if (pressScreen[0] != null && !dragging[0])
					{
						int dX = event.getXOnScreen() - pressScreen[0].x;
						int dY = event.getYOnScreen() - pressScreen[0].y;
						double distance = Math.sqrt((double) dX * dX + (double) dY * dY);
						if (distance > DRAG_THRESHOLD)
						{
							// ToDo: why is this is an array?
							dragging[0] = true;
							// start the global drag (ghost + placeholder) and close the popup
							SortableJTabbedPane.this.startDrag(navButton, pressScreen[0]);
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
			Point btnOnScreen = btnShowHiddenButtonsPopup.getLocationOnScreen();
			int x = btnOnScreen.x - popup.getWidth();
			// prefer showing the popup above the hidden button
			int y = btnOnScreen.y - popup.getHeight();
			// fallback to below the button if not enough space above
			if (y < 0)
			{
				y = btnOnScreen.y + btnShowHiddenButtonsPopup.getHeight();
			}
			if (x < 0)
			{
				x = btnOnScreen.x + btnShowHiddenButtonsPopup.getWidth();
			}
			popup.setLocation(x, y);
		}
		catch (IllegalComponentStateException ex)
		{
			// component not showing on screen; fallback to positioning relative to this component
			Point loc = btnShowHiddenButtonsPopup.getLocation();
			SwingUtilities.convertPointToScreen(loc, btnShowHiddenButtonsPopup);
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
		pinnedButtonTooltips.clear();
		hiddenButtonTooltips.clear();
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
					pinnedButtonTooltips.add(decodeFromStorage(tok));
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
					hiddenButtonTooltips.add(decodeFromStorage(tok));
				}
			}
		}
		if (DEBUG)
		{
			LOGGER.debug("loadState: pinnedCsv={} hiddenCsv={}", pinnedCsv, hiddenCsv);
		}
	}

	// ToDo: find out if we can use a better identifier than tooltip strings
	private void saveState()
	{
		if (configManager == null)
		{
			return;
		}
		// Encode tooltips before persisting so commas and other special
		// characters don't break the CSV parsing on load.
		String pinnedCsv = pinnedButtonTooltips.stream()
			.map(SortableJTabbedPane::encodeForStorage)
			.collect(Collectors.joining(","));
		String hiddenCsv = hiddenButtonTooltips.stream()
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

	// ToDo: is this needed? Our data has already been serialized anyway
	private static String encodeForStorage(String s)
	{
		if (s == null)
		{
			return "";
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	// ToDo: is this needed? Our data has already been serialized anyway
	private static String decodeFromStorage(String tok)
	{
		// ToDo: wtf is tok
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

	//ToDo: this method doesn't seem safe, what if tooltip changes or is not unique?
	private static String serializeTooltip(NavigationButton navigationButton)
	{
		if (navigationButton == null)
		{
			return "<null>";
		}
		String tooltipString = navigationButton.getTooltip();
		return tooltipString == null ? "<untitled>" : tooltipString;
	}
}
