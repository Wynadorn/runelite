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
import java.awt.Rectangle;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CustomSnapAreaParserTest
{
	private Rectangle viewportBounds;
	private Rectangle chatboxBounds;
	private Dimension realDimensions;
	private static final int BORDER = 5;
	private static final int BORDER_TOP = BORDER + 15;
	private static final int SNAP_CORNER_WIDTH = 80;
	private static final int SNAP_CORNER_HEIGHT = 80;

	@Before
	public void setUp()
	{
		viewportBounds = new Rectangle(0, 0, 800, 500);
		chatboxBounds = new Rectangle(0, 350, 500, 150);
		realDimensions = new Dimension(1024, 768);
	}

	@Test
	public void testParseValidSingleArea()
	{
		String config = "top_left|VIEWPORT_TOP_LEFT|10|20";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(1, areas.size());
		CustomSnapArea area = areas.get(0);
		assertEquals("top_left", area.getId());
		assertEquals(SnapOrigin.VIEWPORT_TOP_LEFT, area.getOrigin());
		assertEquals(10, area.getX());
		assertEquals(20, area.getY());
		assertNotNull(area.getBounds());
	}

	@Test
	public void testParseMultipleAreas()
	{
		String config = "top_left|VIEWPORT_TOP_LEFT|10|20\n" +
			"bottom_right|VIEWPORT_BOTTOM_RIGHT|-10|-10";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(2, areas.size());
		assertEquals("top_left", areas.get(0).getId());
		assertEquals("bottom_right", areas.get(1).getId());
	}

	@Test
	public void testParseMalformedLine()
	{
		String config = "invalid_line_missing_pipes\n" +
			"valid|VIEWPORT_TOP_LEFT|0|0";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		// Malformed line should be skipped
		assertEquals(1, areas.size());
		assertEquals("valid", areas.get(0).getId());
	}

	@Test
	public void testParseInvalidOrigin()
	{
		String config = "invalid_origin|INVALID_ORIGIN|0|0\n" +
			"valid|VIEWPORT_TOP_LEFT|0|0";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		// Invalid origin should be skipped
		assertEquals(1, areas.size());
		assertEquals("valid", areas.get(0).getId());
	}

	@Test
	public void testParseInvalidCoordinates()
	{
		String config = "invalid_coords|VIEWPORT_TOP_LEFT|notanumber|0\n" +
			"valid|VIEWPORT_TOP_LEFT|0|0";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		// Invalid coordinates should be skipped
		assertEquals(1, areas.size());
		assertEquals("valid", areas.get(0).getId());
	}

	@Test
	public void testParseEmptyConfig()
	{
		String config = "";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(0, areas.size());
	}

	@Test
	public void testParseNullConfig()
	{
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(null, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(0, areas.size());
	}

	@Test
	public void testParseBlankLines()
	{
		String config = "top_left|VIEWPORT_TOP_LEFT|10|20\n\n\n" +
			"bottom_right|VIEWPORT_BOTTOM_RIGHT|-10|-10";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		// Blank lines should be skipped
		assertEquals(2, areas.size());
	}

	@Test
	public void testParseWhitespaceHandling()
	{
		String config = "  top_left  |  VIEWPORT_TOP_LEFT  |  10  |  20  ";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(1, areas.size());
		assertEquals("top_left", areas.get(0).getId());
		assertEquals(10, areas.get(0).getX());
		assertEquals(20, areas.get(0).getY());
	}

	@Test
	public void testParseAllOrigins()
	{
		String[] origins = {
			"VIEWPORT_TOP_LEFT",
			"VIEWPORT_TOP_RIGHT",
			"VIEWPORT_BOTTOM_LEFT",
			"VIEWPORT_BOTTOM_RIGHT",
			"VIEWPORT_CENTER",
			"CANVAS_TOP_LEFT",
			"CANVAS_TOP_RIGHT",
			"CHATBOX_RIGHT"
		};

		for (String origin : origins)
		{
			String config = "test|" + origin + "|0|0";
			List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);
			assertEquals("Failed for origin: " + origin, 1, areas.size());
		}
	}

	@Test
	public void testParseNegativeCoordinates()
	{
		String config = "test|VIEWPORT_BOTTOM_RIGHT|-50|-30";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		assertEquals(1, areas.size());
		assertEquals(-50, areas.get(0).getX());
		assertEquals(-30, areas.get(0).getY());
	}

	@Test
	public void testBoundsCalculation()
	{
		String config = "test|VIEWPORT_TOP_LEFT|10|20";
		List<CustomSnapArea> areas = CustomSnapAreaParser.parse(config, viewportBounds, chatboxBounds, true, false, realDimensions);

		CustomSnapArea area = areas.get(0);
		Rectangle bounds = area.getBounds();

		// Viewport top left is at (5, 20) + (10, 20) offset = (15, 40)
		assertEquals(BORDER + 10, bounds.x);
		assertEquals(BORDER_TOP + 20, bounds.y);
		assertEquals(SNAP_CORNER_WIDTH, bounds.width);
		assertEquals(SNAP_CORNER_HEIGHT, bounds.height);
	}
}
