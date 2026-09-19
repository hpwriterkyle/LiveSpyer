package top.vrilhyc.applications;

import com.formdev.flatlaf.FlatDarkLaf;
import top.vrilhyc.applications.platform.PlatformRegistry;
import top.vrilhyc.applications.platform.bilibili.BilibiliPlatform;
import top.vrilhyc.applications.ui.LiveWindow;
import javax.swing.SwingUtilities;

public final class Main {
    public static void main(String[] args) {
        FlatDarkLaf.setup();
        var platforms = new PlatformRegistry();
        platforms.register(new BilibiliPlatform());
        platforms.loadExtensions();
        SwingUtilities.invokeLater(() -> new LiveWindow(platforms).setVisible(true));
    }
}
