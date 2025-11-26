package net.runelite.client.ui.components;

import lombok.Value;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SortableButton extends JButton
{
    private NavigationButton _navigationButton;
    
    int _pluginPrioriy = -1;
    int _userPriority = -1;
    int _currentIndex = -1;
    boolean _isHidden = false;
    
    public static final Comparator<? super SortableButton> COMPARATOR = Comparator.comparing(SortableButton::GetUserPriority)
                                                                                    .thenComparing(SortableButton::GetPluginPriority)
                                                                                    .thenComparing(SortableButton::GetToolTip);
    
    public int GetUserPriority()
    {
        return _userPriority;
    }
    
    public int GetPluginPriority()
    {
        return _pluginPrioriy;
    }
    
    public String GetToolTip()
    {
        return _navigationButton.getTooltip();
    }
    
    public SortableButton(NavigationButton nb, boolean resize)
    {
        _navigationButton = nb;
        _pluginPrioriy = _navigationButton.getPriority();
        
        // Set button defaults
        Icon icon = new ImageIcon(resize ? ImageUtil.resizeImage(nb.getIcon(), 16, 16) : nb.getIcon());
        setIcon(icon);
        SwingUtil.removeButtonDecorations(this);
        setToolTipText(nb.getTooltip());
        setFocusable(false);
        setPreferredSize(new Dimension(23, 23));
        setAlignmentX(.5f);
        setAlignmentY(.5f);
        
        // Add the left-click listener
        addActionListener(l ->
                          {
                              if (nb.getOnClick() != null)
                              {
                                  nb.getOnClick().run();
                              }
                          });
        
        // Add the right-click / context menu listener
        InitContextMenu();
    }
    
    private void InitContextMenu() {
        // Get default menu items
        List<JMenuItem> menuItems = GetDefaultMenuItems();
        
        // Get plugin specific menu items
        if (_navigationButton.getPopup() != null)
        {
            _navigationButton.getPopup().forEach((name, cb) ->
                                  {
                                      var menuItem = new JMenuItem(name);
                                      menuItem.addActionListener(e -> cb.run());
                                      menuItems.add(menuItem);
                                  });
            
        }
        
        // Create the actual menu and set it
        var menu = new JPopupMenu();
        menuItems.forEach((item) -> {
            menu.add(item);
        });
        setComponentPopupMenu(menu);
    }
    
    private List<JMenuItem> GetDefaultMenuItems()
    {
        return new ArrayList<JMenuItem>();
    }
}
