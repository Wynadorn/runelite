/*
 * Copyright (c) 2018, DennisDeV <https://github.com/DevDennis>
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
package net.runelite.client.plugins.tabletmode;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.WidgetLoaded;
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

@PluginDescriptor(
	name = "Tablet Mode",
	description = "Adds several features to support touch-screen input",
	tags = {"touch", "touchscreen", "tablet", "mobile", "touch screen", "touch-screen", "swipe"},
	enabledByDefault = false
)
public class TabletModePlugin extends Plugin implements MouseListener
{
	static final String CONFIG_GROUP = "tabletMode";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TabletModeConfig config;

	@Inject
	private MouseManager mouseManager;

	@Provides
	TabletModeConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TabletModeConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		mouseManager.registerMouseListener(this);
//		if (client.getGameState() == GameState.LOGGED_IN)
//		{
//			clientThread.invokeLater(() ->
//			{
//				if (!config.onShiftOnly())
//				{
//					setDragDelay();
//				}
//			});
//		}
//
//		keyManager.registerKeyListener(this);
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(this);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CONFIG_GROUP))
		{

		}
	}

	@Subscribe
	public void onFocusChanged(FocusChanged focusChanged)
	{
		if (!focusChanged.isFocused())
		{

		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
//		if ((widgetLoaded.getGroupId() == WidgetID.BANK_GROUP_ID)
//		{
//
//		}
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	MouseEvent x_temp;

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		x_temp = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
					mouseEvent.getID(),
					mouseEvent.getWhen(),
					mouseEvent.getModifiersEx(),
					mouseEvent.getX(),
					mouseEvent.getY(),
					mouseEvent.getClickCount(),
					mouseEvent.isPopupTrigger(),
					mouseEvent.getButton());
		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		return x_temp;
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

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

//	/**
//	 * The event that is triggered when a mouse button is pressed
//	 * In this method the right click is changed to a middle-click to enable rotating the camera
//	 * <p>
//	 * This method also provides the config option to enable the middle-mouse button to always open the right click menu
//	 */
//	@Override
//	public MouseEvent mousePressed(MouseEvent mouseEvent)
//	{
//		if (SwingUtilities.isRightMouseButton(mouseEvent) && config.rightClickMovesCamera())
//		{
//			boolean oneButton = client.getVar(VarPlayer.MOUSE_BUTTONS) == 1;
//			// Only move the camera if there is nothing at the menu, or if
//			// in one-button mode. In one-button mode, left and right click always do the same thing,
//			// so always treat it as the menu is empty
//			if (!menuHasEntries || oneButton)
//			{
//				// Set the rightClick flag to true so we can release the button in mouseReleased() later
//				rightClick = true;
//				// Change the mousePressed() MouseEvent to the middle mouse button
//				mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
//					mouseEvent.getID(),
//					mouseEvent.getWhen(),
//					mouseEvent.getModifiersEx(),
//					mouseEvent.getX(),
//					mouseEvent.getY(),
//					mouseEvent.getClickCount(),
//					mouseEvent.isPopupTrigger(),
//					MouseEvent.BUTTON2);
//			}
//		}
//		else if (SwingUtilities.isMiddleMouseButton((mouseEvent)) && config.middleClickMenu())
//		{
//			// Set the middleClick flag to true so we can release it later in mouseReleased()
//			middleClick = true;
//			// Chance the middle mouse button MouseEvent to a right-click
//			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
//				mouseEvent.getID(),
//				mouseEvent.getWhen(),
//				mouseEvent.getModifiersEx(),
//				mouseEvent.getX(),
//				mouseEvent.getY(),
//				mouseEvent.getClickCount(),
//				mouseEvent.isPopupTrigger(),
//				MouseEvent.BUTTON3);
//		}
//		return mouseEvent;
//	}
//
//	/**
//	 * Correct the MouseEvent to release the correct button
//	 */
//	@Override
//	public MouseEvent mouseReleased(MouseEvent mouseEvent)
//	{
//		if (rightClick)
//		{
//			rightClick = false;
//			// Change the MouseEvent to button 2 so the middle mouse button will be released
//			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
//				mouseEvent.getID(),
//				mouseEvent.getWhen(),
//				mouseEvent.getModifiersEx(),
//				mouseEvent.getX(),
//				mouseEvent.getY(),
//				mouseEvent.getClickCount(),
//				mouseEvent.isPopupTrigger(),
//				MouseEvent.BUTTON2);
//
//		}
//		if (middleClick)
//		{
//			middleClick = false;
//			// Change the MouseEvent ot button 3 so the right mouse button will be released
//			mouseEvent = new MouseEvent((java.awt.Component) mouseEvent.getSource(),
//				mouseEvent.getID(),
//				mouseEvent.getWhen(),
//				mouseEvent.getModifiersEx(),
//				mouseEvent.getX(),
//				mouseEvent.getY(),
//				mouseEvent.getClickCount(),
//				mouseEvent.isPopupTrigger(),
//				MouseEvent.BUTTON3);
//		}
//		return mouseEvent;
//	}
}
