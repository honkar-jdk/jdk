/*
 * Copyright (c) 2006, 2022, Oracle and/or its affiliates. All rights reserved.
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


import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import test.java.awt.regtesthelpers.Util;

/*
 * @test
 * @key headful
 * @bug 6396785
 * @summary Action key pressed on a button should be swallowed.
 * @library  /java/awt/regtesthelpers
 * @build  Util
 * @run  main/othervm ButtonActionKeyTest
 */

public class ButtonActionKeyTest {
    static Robot robot;
    static JFrame frame = new JFrame("Frame");
    static JButton button = new JButton("button");
    static JTextField text = new JTextField("text");
    static AtomicBoolean gotEvent = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        try {
            robot = new Robot();
            SwingUtilities.invokeAndWait(ButtonActionKeyTest::createAndShowGUI);
            robot.setAutoDelay(300);
            robot.waitForIdle();

            Util.clickOnComp(button, robot);
            robot.waitForIdle();

            SwingUtilities.invokeAndWait(()-> {
                if (!button.isFocusOwner()) {
                    throw new RuntimeException("Test error:" +
                            " Button didn't gain focus.");
                }
            });

            robot.keyPress(KeyEvent.VK_A);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_A);
            robot.waitForIdle();

            if (Util.waitForCondition(gotEvent, 1000)) {
                throw new RuntimeException("An action key" +
                        " went into the text field!");
            }
            System.out.println("Test passed.");
        }
        finally {
            if (frame != null) {
                SwingUtilities.invokeAndWait(()-> frame.dispose());
            }
        }
    }

    private static void createAndShowGUI() {
        frame.setLayout(new FlowLayout());
        frame.add(button);
        frame.add(text);
        frame.pack();

        button.getInputMap().put(KeyStroke.getKeyStroke("A"), "GO!");

        button.getActionMap().put("GO!", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                System.out.println("Action performed!");
                text.requestFocusInWindow();
            }
        });

        text.addKeyListener(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == 'a') {
                    System.out.println(e.toString());
                    synchronized (gotEvent) {
                        gotEvent.set(true);
                        gotEvent.notifyAll();
                    }
                }
            }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
