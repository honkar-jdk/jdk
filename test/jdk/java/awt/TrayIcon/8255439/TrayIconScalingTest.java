/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.font.TextLayout;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.ArrayList;
import java.util.stream.IntStream;

/*
 * @test
 * @bug 8255439
 * @key headful
 * @summary
 * @run main TrayIconScalingTest
 */

public class TrayIconScalingTest {

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            createAndShowGUI();
        });
    }

    private static void createAndShowGUI() {
//        System.setProperty("sun.java2d.uiScale", "1.75");
//        System.out.println(System.getProperty("sun.java2d.uiScale"));

        //Check to see if  SystemTray supported on the machine
        if (!SystemTray.isSupported()) {
            System.out.println("SystemTray is not supported");
            return;
        }
        //System.out.println(getScaleFactor());
        SystemTray tray = SystemTray.getSystemTray();
        Dimension dim = tray.getTrayIconSize();

        ArrayList<Image> images = new ArrayList<>();

        for (int size = 16; size <= 32; size++) {
            createIcon(size, images);
        }

        MultiResolutionImage multiResolutionImage =
                new BaseMultiResolutionImage(images.toArray(new Image[0]));

//        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
//        Graphics2D g = image.createGraphics();
//        //g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
//        g.setColor(Color.WHITE);
//        g.fillRect(0, 0, 20, 20);
//        g.dispose();

        TrayIcon icon = new TrayIcon((Image) multiResolutionImage);
        PopupMenu popup = new PopupMenu();
        MenuItem exitItem = new MenuItem("Exit");
        popup.add(exitItem);

        icon.setPopupMenu(popup);
        try {
            tray.add(icon);
        } catch (AWTException e) {
            throw new RuntimeException("Error while adding icon to system tray");
        }
        //System.out.println("Dimension from system tray:"+ dim.toString());
        //System.out.println("Icon Size:"+ icon.getSize().toString());

        exitItem.addActionListener(e -> {
            tray.remove(icon);
            System.exit(0);
        });

    }
    private static void createIcon(int size, ArrayList<Image> imageArrayList) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setFont(new Font("Dialog", Font.BOLD, 12));
        g.setColor(Color.BLACK);

        TextLayout layout = new TextLayout(String.valueOf(size), g.getFont(), g.getFontRenderContext());
        int height = (int) layout.getBounds().getHeight();
        int width = (int) layout.getBounds().getWidth();
        layout.draw(g, (size - width) / 2f - 1, (size + height) / 2f);
        imageArrayList.add(image);
        g.dispose();
    }
}
