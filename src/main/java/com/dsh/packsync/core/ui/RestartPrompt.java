package com.dsh.packsync.core.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * 启动期同步完成后的"请重启"提示窗口。
 *
 * <p><b>为什么用 AWT/Swing 而不是 Minecraft 的界面</b>：这段代码运行在
 * 启动期 locator 阶段 —— 那时 Minecraft 客户端还没起来，MC 的渲染栈根本不存在。
 * 同类模组用的是同一个办法。
 *
 * <p>为什么必须有它：启动期同步发生在 mod 加载**之前**，此时替mods/ 里的文件
 * 已经换过了，但游戏进程仍持着旧的类。**玩家不重启就等于没生效**。
 * 只在日志里打一行字远远不够 —— 玩家不会去看日志。
 *
 * <p>无图形环境（服务器/headless）下自动跳过，不阻塞也 不抛异常。
 */
public final class RestartPrompt {

    /** 玩家是否选择了"立即关闭游戏"。 */
    private static final AtomicBoolean RESTART_REQUESTED = new AtomicBoolean(false);

    private RestartPrompt() {
    }

    /** 是否可以在当前环境弹窗。 */
    public static boolean isAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 弹出提示并**等待**玩家选择。
     *
     * @param message 要显示的内容（例如"整合包已更新，共 12 个文件"）
     * @return true 表示玩家选择立即关闭游戏
     */
    public static boolean ask(String message) {
        if (!isAvailable()) {
            System.out.println("[PackSync] （无图形环境）请手动重启游戏以应用更新：" + message);
            return false;
        }
        try {
            SwingUtilities.invokeAndWait(() -> showWindow(message));
        } catch (Throwable t) {
            System.err.println("[PackSync] 重启提示窗口显示失败（忽略）：" + t);
            return false;
        }
        return RESTART_REQUESTED.get();
    }

    private static void showWindow(String message) {
        JFrame frame = new JFrame("PackSync");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(new Color(22, 27, 34));
        root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JLabel title = new JLabel("整合包已就绪，请重启游戏", JLabel.CENTER);
        title.setForeground(new Color(0x5BD75B));
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        JLabel detail = new JLabel("<html><div style='text-align:center'>" + escape(message)
                + "<br><br>不重启的话，刚才同步的文件不会生效。</div></html>", JLabel.CENTER);
        detail.setForeground(Color.WHITE);
        detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        JPanel buttons = new JPanel();
        buttons.setBackground(new Color(22, 27, 34));

        JButton later = new JButton("稍后自己重启");
        later.addActionListener(e -> {
            RESTART_REQUESTED.set(false);
            frame.dispose();
        });

        JButton now = new JButton("现在关闭游戏");
        now.addActionListener(e -> {
            RESTART_REQUESTED.set(true);
            frame.dispose();
        });

        buttons.add(now);
        buttons.add(later);

        root.add(title, BorderLayout.NORTH);
        root.add(detail, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(380, frame.getHeight()));

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation((screen.width - frame.getWidth()) / 2,
                (screen.height - frame.getHeight()) / 2);
        frame.setVisible(true);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
