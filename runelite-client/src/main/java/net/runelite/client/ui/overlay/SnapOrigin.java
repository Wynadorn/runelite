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

/**
 * Defines the origin point for custom snap areas.
 */
public enum SnapOrigin
{
	/**
	 * Top-left corner of the viewport
	 */
	VIEWPORT_TOP_LEFT,
	/**
	 * Top-right corner of the viewport
	 */
	VIEWPORT_TOP_RIGHT,
	/**
	 * Bottom-left corner of the viewport
	 */
	VIEWPORT_BOTTOM_LEFT,
	/**
	 * Bottom-right corner of the viewport
	 */
	VIEWPORT_BOTTOM_RIGHT,
	/**
	 * Center of the viewport
	 */
	VIEWPORT_CENTER,
	/**
	 * Top-left corner of the canvas (full client window)
	 */
	CANVAS_TOP_LEFT,
	/**
	 * Top-right corner of the canvas (full client window)
	 */
	CANVAS_TOP_RIGHT,
	/**
	 * Right side of the chatbox (in resizable mode)
	 */
	CHATBOX_RIGHT
}
