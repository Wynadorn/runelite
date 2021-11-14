/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2019, Wynadorn <https://github.com/Wynadorn>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.camera;

import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.SettingID;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

@PluginDescriptor(
	name = "Camera",
	description = "Expands zoom limit, provides vertical camera, and remaps mouse input keys",
	tags = {"zoom", "limit", "vertical", "click", "mouse"},
	enabledByDefault = false
)
public class CameraPlugin extends Plugin implements KeyListener, MouseListener
{
	private static final int DEFAULT_ZOOM_INCREMENT = 25;
	private static final int DEFAULT_OUTER_ZOOM_LIMIT = 128;
	static final int DEFAULT_INNER_ZOOM_LIMIT = 896;

	private boolean controlDown;
	// flags used to store the mousedown states
	private boolean rightClick;
	private boolean middleClick;
	/**
	 * Whether or not the current menu has any non-ignored menu entries
	 */
	private boolean menuHasEntries;
	private int savedCameraYaw;
	private boolean simulateLeftClick;
	private boolean simulatingMiddleClick;
	private Point clickDragInitialPoint;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CameraConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private TooltipManager tooltipManager;

	private Tooltip sliderTooltip;

	@Provides
	CameraConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CameraConfig.class);
	}

	@Override
	protected void startUp()
	{
		rightClick = false;
		middleClick = false;
		menuHasEntries = false;
		copyConfigs();
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(this);
		clientThread.invoke(() ->
		{
			Widget sideSlider = client.getWidget(WidgetInfo.SETTINGS_SIDE_CAMERA_ZOOM_SLIDER_TRACK);
			if (sideSlider != null)
			{
				addZoomTooltip(sideSlider);
			}

			Widget settingsInit = client.getWidget(WidgetInfo.SETTINGS_INIT);
			if (settingsInit != null)
			{
				client.createScriptEvent(settingsInit.getOnLoadListener())
					.setSource(settingsInit)
					.run();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		client.setCameraPitchRelaxerEnabled(false);
		client.setInvertYaw(false);
		client.setInvertPitch(false);
		keyManager.unregisterKeyListener(this);
		mouseManager.unregisterMouseListener(this);
		controlDown = false;

		clientThread.invoke(() ->
		{
			Widget sideSlider = client.getWidget(WidgetInfo.SETTINGS_SIDE_CAMERA_ZOOM_SLIDER_TRACK);
			if (sideSlider != null)
			{
				sideSlider.setOnMouseRepeatListener((Object[]) null);
			}

			Widget settingsInit = client.getWidget(WidgetInfo.SETTINGS_INIT);
			if (settingsInit != null)
			{
				client.createScriptEvent(settingsInit.getOnLoadListener())
					.setSource(settingsInit)
					.run();
			}
		});
	}

	void copyConfigs()
	{
		client.setCameraPitchRelaxerEnabled(config.relaxCameraPitch());
		client.setInvertYaw(config.invertYaw());
		client.setInvertPitch(config.invertPitch());
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (client.getIndexScripts().isOverlayOutdated())
		{
			// if any cache overlay fails to load then assume at least one of the zoom scripts is outdated
			// and prevent zoom extending entirely.
			return;
		}

		int[] intStack = client.getIntStack();
		int intStackSize = client.getIntStackSize();

		if (!controlDown && "scrollWheelZoom".equals(event.getEventName()) && config.controlFunction() == ControlFunction.CONTROL_TO_ZOOM)
		{
			intStack[intStackSize - 1] = 1;
		}

		if ("innerZoomLimit".equals(event.getEventName()) && config.innerLimit())
		{
			intStack[intStackSize - 1] = CameraConfig.INNER_ZOOM_LIMIT;
			return;
		}

		if ("outerZoomLimit".equals(event.getEventName()))
		{
			int outerLimit = Ints.constrainToRange(config.outerLimit(), CameraConfig.OUTER_LIMIT_MIN, CameraConfig.OUTER_LIMIT_MAX);
			int outerZoomLimit = DEFAULT_OUTER_ZOOM_LIMIT - outerLimit;
			intStack[intStackSize - 1] = outerZoomLimit;
			return;
		}

		if ("scrollWheelZoomIncrement".equals(event.getEventName()) && config.zoomIncrement() != DEFAULT_ZOOM_INCREMENT)
		{
			intStack[intStackSize - 1] = config.zoomIncrement();
			return;
		}

		if ("lookPreservePitch".equals(event.getEventName()) && config.compassLookPreservePitch())
		{
			intStack[intStackSize - 1] = client.getCameraPitch();
			return;
		}

		if (config.innerLimit())
		{
			// This lets the options panel's slider have an exponential rate
			final double exponent = 2.d;
			switch (event.getEventName())
			{
				case "zoomLinToExp":
				{
					double range = intStack[intStackSize - 1];
					double value = intStack[intStackSize - 2];
					value = Math.pow(value / range, exponent) * range;
					intStack[intStackSize - 2] = (int) value;
					break;
				}
				case "zoomExpToLin":
				{
					double range = intStack[intStackSize - 1];
					double value = intStack[intStackSize - 2];
					value = Math.pow(value / range, 1.d / exponent) * range;
					intStack[intStackSize - 2] = (int) value;
					break;
				}
			}
		}
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			controlDown = false;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged ev)
	{
		copyConfigs();
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_CONTROL)
		{
			controlDown = true;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_CONTROL)
		{
			controlDown = false;

			if (config.controlFunction() == ControlFunction.CONTROL_TO_RESET)
			{
				final int zoomValue = Ints.constrainToRange(config.ctrlZoomValue(), CameraConfig.OUTER_LIMIT_MIN, CameraConfig.INNER_ZOOM_LIMIT);
				clientThread.invokeLater(() -> client.runScript(ScriptID.CAMERA_DO_ZOOM, zoomValue, zoomValue));
			}
		}
	}

	/**
	 * Checks if the menu has any non-ignored entries
	 */
	private boolean hasMenuEntries(MenuEntry[] menuEntries)
	{
		for (MenuEntry menuEntry : menuEntries)
		{
			MenuAction action = MenuAction.of(menuEntry.getType());
			switch (action)
			{
				case CANCEL:
				case WALK:
					break;
				case EXAMINE_OBJECT:
				case EXAMINE_NPC:
				case EXAMINE_ITEM_GROUND:
				case EXAMINE_ITEM:
				case CC_OP_LOW_PRIORITY:
					if (config.ignoreExamine())
					{
						break;
					}
				default:
					return true;
			}
		}
		return false;
	}

	/**
	 * Checks if the menu has any options, because menu entries are built each
	 * tick and the MouseListener runs on the awt thread
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		menuHasEntries = hasMenuEntries(client.getMenuEntries());
		sliderTooltip = null;
	}

	@Subscribe
	private void onScriptPreFired(ScriptPreFired ev)
	{
		switch (ev.getScriptId())
		{
			case ScriptID.SETTINGS_SLIDER_CHOOSE_ONOP:
			{
				int arg = client.getIntStackSize() - 7;
				int[] is = client.getIntStack();

				if (is[arg] == SettingID.CAMERA_ZOOM)
				{
					addZoomTooltip(client.getScriptActiveWidget());
				}
				break;
			}
			case ScriptID.ZOOM_SLIDER_ONDRAG:
			case ScriptID.SETTINGS_ZOOM_SLIDER_ONDRAG:
				sliderTooltip = makeSliderTooltip();
				break;
		}
	}

	@Subscribe
	private void onWidgetLoaded(WidgetLoaded ev)
	{
		if (ev.getGroupId() == WidgetID.SETTINGS_SIDE_GROUP_ID)
		{
			addZoomTooltip(client.getWidget(WidgetInfo.SETTINGS_SIDE_CAMERA_ZOOM_SLIDER_TRACK));
		}
	}

	private void addZoomTooltip(Widget w)
	{
		w.setOnMouseRepeatListener((JavaScriptCallback) ev -> sliderTooltip = makeSliderTooltip());
	}

	private Tooltip makeSliderTooltip()
	{
		int value = client.getVar(VarClientInt.CAMERA_ZOOM_RESIZABLE_VIEWPORT);
		int max = config.innerLimit() ? config.INNER_ZOOM_LIMIT : CameraPlugin.DEFAULT_INNER_ZOOM_LIMIT;
		return new Tooltip("Camera Zoom: " + value + " / " + max);
	}

	@Subscribe
	private void onBeforeRender(BeforeRender ev)
	{
		if (sliderTooltip != null)
		{
			tooltipManager.add(sliderTooltip);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		switch (gameStateChanged.getGameState())
		{
			case HOPPING:
				savedCameraYaw = client.getMapAngle();
				break;
			case LOGGED_IN:
				if (savedCameraYaw != 0 && config.preserveYaw())
				{
					client.setCameraYawTarget(savedCameraYaw);
				}
				savedCameraYaw = 0;
				break;
		}
	}

	private boolean isPointOnClickableWidget(Point point)
	{
		return isPointOnClickableWidget(point, client.getWidgetRoots());
	}

	private boolean isPointOnClickableWidget(Point point, Widget[] widgets)
	{
		if (widgets == null)
		{
			return false;
		}

		for (Widget w : widgets)
		{
			if (w.getNoClickThrough() && w.contains(point))
			{
				return true;
			}

			if (
				isPointOnClickableWidget(point, w.getChildren()) ||
				isPointOnClickableWidget(point, w.getStaticChildren()) ||
				isPointOnClickableWidget(point, w.getNestedChildren())
			)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The event that is triggered when a mouse button is pressed
	 * In this method the right click is changed to a middle-click to enable rotating the camera
	 * <p>
	 * This method also provides the config option to enable the middle-mouse button to always open the right click menu
	 */
	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		if (SwingUtilities.isRightMouseButton(mouseEvent) && config.rightClickMovesCamera())
		{
			boolean oneButton = client.getVar(VarPlayer.MOUSE_BUTTONS) == 1;
			// Only move the camera if there is nothing at the menu, or if
			// in one-button mode. In one-button mode, left and right click always do the same thing,
			// so always treat it as the menu is empty
			if (!menuHasEntries || oneButton)
			{
				// Set the rightClick flag to true so we can release the button in mouseReleased() later
				rightClick = true;
				// Change the mousePressed() MouseEvent to the middle mouse button
				mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
					mouseEvent.getID(),
					mouseEvent.getWhen(),
					mouseEvent.getModifiersEx(),
					mouseEvent.getX(),
					mouseEvent.getY(),
					mouseEvent.getClickCount(),
					mouseEvent.isPopupTrigger(),
					MouseEvent.BUTTON2);
			}
		}
		else if (SwingUtilities.isMiddleMouseButton((mouseEvent)) && config.middleClickMenu())
		{
			// Set the middleClick flag to true so we can release it later in mouseReleased()
			middleClick = true;
			// Chance the middle mouse button MouseEvent to a right-click
			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
				mouseEvent.getID(),
				mouseEvent.getWhen(),
				mouseEvent.getModifiersEx(),
				mouseEvent.getX(),
				mouseEvent.getY(),
				mouseEvent.getClickCount(),
				mouseEvent.isPopupTrigger(),
				MouseEvent.BUTTON3);
		}
		else if (SwingUtilities.isLeftMouseButton(mouseEvent) && config.rotateOnDrag())
		{
			if (simulateLeftClick)
			{
				// Let the simulated left click pass through
				simulateLeftClick = false;
			}
			else
			{
				Point point = new Point(mouseEvent.getX(), mouseEvent.getY());

				leftClickDrag:
				{
					if (client.getGameState() != GameState.LOGGED_IN)
					{
						break leftClickDrag;
					}

					// Slightly more time-sensitive checks for prayer orbs etc
					Widget orb = client.getWidget(WidgetInfo.MINIMAP_QUICK_HEALTH_ORB);
					if (orb != null && orb.contains(point))
					{
						break leftClickDrag;
					}

					orb = client.getWidget(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
					if (orb != null && orb.contains(point))
					{
						break leftClickDrag;
					}

					orb = client.getWidget(WidgetInfo.MINIMAP_TOGGLE_RUN_ORB);
					if (orb != null && orb.contains(point))
					{
						break leftClickDrag;
					}

					orb = client.getWidget(WidgetInfo.MINIMAP_TOGGLE_SPEC_ORB);
					if (orb != null && orb.contains(point))
					{
						break leftClickDrag;
					}

					mouseEvent.consume();
					final MouseEvent consumedEvent = mouseEvent;
					clientThread.invokeLater(() ->
					{
						// Schedule a simulated click on release, if not dragging
						simulateLeftClick = true;
						clickDragInitialPoint = point;

						if (isPointOnClickableWidget(point))
						{
							// Simulate a normal click since we had to consume the event prior to this check
							Component source = (Component) consumedEvent.getSource();
							source.dispatchEvent(new MouseEvent(
								source,
								consumedEvent.getID(),
								consumedEvent.getWhen(),
								consumedEvent.getModifiersEx(),
								consumedEvent.getX(),
								consumedEvent.getY(),
								consumedEvent.getClickCount(),
								consumedEvent.isPopupTrigger(),
								consumedEvent.getButton()));
						}
					});
				}
			}
		}

		return mouseEvent;
	}

	/**
	 * Correct the MouseEvent to release the correct button
	 */
	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		if (rightClick)
		{
			rightClick = false;
			// Change the MouseEvent to button 2 so the middle mouse button will be released
			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
				mouseEvent.getID(),
				mouseEvent.getWhen(),
				mouseEvent.getModifiersEx(),
				mouseEvent.getX(),
				mouseEvent.getY(),
				mouseEvent.getClickCount(),
				mouseEvent.isPopupTrigger(),
				MouseEvent.BUTTON2);

		}
		if (middleClick)
		{
			middleClick = false;
			// Change the MouseEvent ot button 3 so the right mouse button will be released
			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
				mouseEvent.getID(),
				mouseEvent.getWhen(),
				mouseEvent.getModifiersEx(),
				mouseEvent.getX(),
				mouseEvent.getY(),
				mouseEvent.getClickCount(),
				mouseEvent.isPopupTrigger(),
				MouseEvent.BUTTON3);
		}

		if (mouseEvent.getButton() == MouseEvent.BUTTON1)
		{
			// If dragging occurred, release the simulated middle click
			if (simulatingMiddleClick)
			{
				Component source = (Component) mouseEvent.getSource();
				source.dispatchEvent(
					new MouseEvent(
						source,
						MouseEvent.MOUSE_RELEASED,
						mouseEvent.getWhen(),
						mouseEvent.getModifiersEx(),
						mouseEvent.getX(),
						mouseEvent.getY(),
						mouseEvent.getClickCount(),
						mouseEvent.isPopupTrigger(),
						MouseEvent.BUTTON2));
				simulatingMiddleClick = false;
			}
			// If no dragging occurred, and we should simulate a left click
			else if (simulateLeftClick)
			{
				// Dispatch a left click press before continuing to process the release event
				Component source = (Component) mouseEvent.getSource();
				source.dispatchEvent(
					new MouseEvent(
						source,
						MouseEvent.MOUSE_PRESSED,
						mouseEvent.getWhen(),
						mouseEvent.getModifiersEx(),
						mouseEvent.getX(),
						mouseEvent.getY(),
						mouseEvent.getClickCount(),
						mouseEvent.isPopupTrigger(),
						MouseEvent.BUTTON1));
			}
			// Reset to initial state
			simulateLeftClick = false;
		}
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		if (simulateLeftClick && config.rotateOnDrag() && SwingUtilities.isLeftMouseButton(mouseEvent) &&
			// Require a minimum drag distance threshold to begin dragging instead of clicking
			(simulatingMiddleClick || clickDragInitialPoint.distanceTo(new Point(mouseEvent.getX(), mouseEvent.getY())) >= config.rotateOnDragThreshold()))
		{
			Component source = (Component) mouseEvent.getSource();

			if (!simulatingMiddleClick)
			{
				// Dispatch a middle click event
				source.dispatchEvent(
					new MouseEvent(
						source,
						MouseEvent.MOUSE_PRESSED,
						mouseEvent.getWhen(),
						mouseEvent.getModifiersEx(),
						mouseEvent.getX(),
						mouseEvent.getY(),
						mouseEvent.getClickCount(),
						mouseEvent.isPopupTrigger(),
						MouseEvent.BUTTON2));
				simulatingMiddleClick = true;
			}

			return new MouseEvent(
				source,
				mouseEvent.getID(),
				mouseEvent.getWhen(),
				mouseEvent.getModifiersEx(),
				mouseEvent.getX(),
				mouseEvent.getY(),
				mouseEvent.getClickCount(),
				mouseEvent.isPopupTrigger(),
				MouseEvent.BUTTON2);
		}

		return mouseEvent;
	}

	/*
	 * These methods are unused but required to be present in a MouseListener implementation
	 */
	// region Unused MouseListener methods
	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}
	// endregion
}
