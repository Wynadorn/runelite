/*
 * Copyright (c) 2025, RuneLite
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
package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses custom snap area configuration strings into CustomSnapArea objects.
 */
@Slf4j
public class CustomSnapAreaParser
{
	private static final Dimension SNAP_CORNER_SIZE = new Dimension(80, 80);
	private static final int BORDER = 5;
	private static final int BORDER_TOP = BORDER + 15;

	/**
	 * Parses custom snap areas from a multiline configuration string.
	 * Format: Each line is "id|origin|x|y"
	 *
	 * @param configText       the configuration string
	 * @param viewportBounds   the bounds of the viewport
	 * @param chatboxBounds    the bounds of the chatbox
	 * @param resizeable       whether the client is in resizable mode
	 * @param chatboxHidden    whether the chatbox is hidden
	 * @return a list of parsed custom snap areas
	 */
	public static List<CustomSnapArea> parse(String configText, Rectangle viewportBounds,
		Rectangle chatboxBounds, boolean resizeable, boolean chatboxHidden, Dimension realDimensions)
	{
		List<CustomSnapArea> areas = new ArrayList<>();

		if (configText == null || configText.isEmpty())
		{
			return areas;
		}

		String[] lines = configText.split("\n");
		for (String line : lines)
		{
			line = line.trim();
			if (line.isEmpty())
			{
				continue;
			}

			String[] tokens = line.split("\\|");
			if (tokens.length != 4)
			{
				log.warn("Malformed custom snap area line (expected 4 tokens): {}", line);
				continue;
			}

			try
			{
				String id = tokens[0].trim();
				String originStr = tokens[1].trim();
				int x = Integer.parseInt(tokens[2].trim());
				int y = Integer.parseInt(tokens[3].trim());

				SnapOrigin origin = SnapOrigin.valueOf(originStr);
				Point basePoint = getOriginPoint(origin, viewportBounds, chatboxBounds, resizeable, chatboxHidden, realDimensions);

				Rectangle bounds = new Rectangle(
					basePoint.x + x,
					basePoint.y + y,
					SNAP_CORNER_SIZE.width,
					SNAP_CORNER_SIZE.height);

				areas.add(new CustomSnapArea(id, origin, x, y, bounds));
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Invalid custom snap area line: {}", line, e);
			}
			catch (Exception e)
			{
				log.warn("Error parsing custom snap area line: {}", line, e);
			}
		}

		return areas;
	}

	/**
	 * Gets the base point for a given origin.
	 */
	private static Point getOriginPoint(SnapOrigin origin, Rectangle viewportBounds,
		Rectangle chatboxBounds, boolean resizeable, boolean chatboxHidden, Dimension realDimensions)
	{
		switch (origin)
		{
			case VIEWPORT_TOP_LEFT:
				return new Point(
					viewportBounds.x + BORDER,
					viewportBounds.y + BORDER_TOP);

			case VIEWPORT_TOP_RIGHT:
				return new Point(
					viewportBounds.x + viewportBounds.width - BORDER,
					viewportBounds.y + BORDER);

			case VIEWPORT_BOTTOM_LEFT:
				return new Point(
					viewportBounds.x + BORDER,
					viewportBounds.y + viewportBounds.height - BORDER);

			case VIEWPORT_BOTTOM_RIGHT:
				return new Point(
					viewportBounds.x + viewportBounds.width - BORDER,
					viewportBounds.y + viewportBounds.height - BORDER);

			case VIEWPORT_CENTER:
				return new Point(
					viewportBounds.x + viewportBounds.width / 2,
					viewportBounds.y + viewportBounds.height / 2);

			case CANVAS_TOP_LEFT:
				return new Point(0, 0);

			case CANVAS_TOP_RIGHT:
				return new Point(
					(int)realDimensions.getWidth(),
					0);

			case CHATBOX_RIGHT:
				return resizeable ? new Point(
					viewportBounds.x + chatboxBounds.width - BORDER,
					viewportBounds.y + viewportBounds.height - BORDER) : new Point(
					viewportBounds.x + viewportBounds.width - BORDER,
					viewportBounds.y + viewportBounds.height - BORDER);

			default:
				return new Point(0, 0);
		}
	}
}
