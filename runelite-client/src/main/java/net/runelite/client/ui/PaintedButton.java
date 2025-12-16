package net.runelite.client.ui;

import java.awt.Color;
import java.awt.Dimension;

import javax.swing.Icon;
import javax.swing.JButton;

// Button implementation that paints its own background so the Look-and-Feel
// cannot change selection/hover visuals. Hover shows `#111111`; otherwise
// the background remains `BUTTON_BG`. Selection should only be indicated
// by the orange stripe painted by the navigation panel.
class PaintedButton extends JButton
{
	private final SortableJTabbedPane sortableJTabbedPane;
	private boolean hover = false;
	private Color forcedBackground = null;

	PaintedButton(SortableJTabbedPane sortableJTabbedPane)
	{
		super();
		this.sortableJTabbedPane = sortableJTabbedPane;
		setContentAreaFilled(false);
		setOpaque(false);
	}

	PaintedButton(SortableJTabbedPane sortableJTabbedPane, Icon i)
	{
		super(i);
		this.sortableJTabbedPane = sortableJTabbedPane;
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
			Color background = ColorScheme.DARKER_GRAY_COLOR;
			if (forcedBackground != null)
			{
				background = forcedBackground;
			}
			else if (hover && !isSelected() && !this.sortableJTabbedPane.isButtonBeingDragged())
			{
				background = ColorScheme.BORDER_COLOR;
			}
			g2.setColor(background);
			g2.fillRect(0, 0, getWidth(), getHeight());
		}
		finally
		{
			g2.dispose();
		}
		super.paintComponent(g);
	}
}