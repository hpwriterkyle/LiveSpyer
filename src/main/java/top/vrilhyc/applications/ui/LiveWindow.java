package top.vrilhyc.applications.ui;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LiveWindow extends JFrame {
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String,RoomPanel> rooms = new LinkedHashMap<>();
    private final Map<String,AccountSession> accounts = new ConcurrentHashMap<>();
    private final JComboBox<LivePlatform> platform;
    private final JLabel accountLabel = new JLabel("未登录");
    private final JTextField roomId = new JTextField(15);
    private int directCount;
    public LiveWindow(PlatformRegistry registry) {
        super("LiveSpyer · 多直播间");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1120,760); setMinimumSize(new Dimension(850,550)); setLocationRelativeTo(null);
        platform = new JComboBox<>(registry.all().toArray(LivePlatform[]::new));
        platform.addActionListener(e -> refreshAccount());
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT,10,12));
        JButton add = new JButton("添加直播间");
        add.addActionListener(e -> addRoom()); roomId.addActionListener(e -> addRoom());
        JButton paste = new JButton("粘贴 m3u8");
        paste.addActionListener(e -> addDirectStream());
        JButton login = new JButton("扫码登录");
        login.addActionListener(e -> {
            LivePlatform selected = selected();
            new QrLoginDialog(this, selected.qrLogin(), session -> {
                accounts.put(selected.id(), session);
                rooms.values().forEach(RoomPanel::disconnectInteraction);
                refreshAccount();
            }).setVisible(true);
        });
        JButton logout = new JButton("退出登录");
        logout.addActionListener(e -> {
            accounts.remove(selected().id());
            rooms.values().forEach(RoomPanel::disconnectInteraction);
            refreshAccount();
        });
        toolbar.add(platform); toolbar.add(new JLabel("房间号")); toolbar.add(roomId);
        toolbar.add(add); toolbar.add(paste); toolbar.add(login); toolbar.add(logout); toolbar.add(accountLabel);
        add(toolbar, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        var hint = new JLabel("  播放与互动连接独立 · 取流后断开互动连接 · 弹幕发送后自动断开", SwingConstants.LEFT);
        hint.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        add(hint, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> leaveFullscreen(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                leaveFullscreen(); rooms.values().forEach(RoomPanel::close); accounts.clear();
            }
        });
    }
    private LivePlatform selected() { return (LivePlatform)platform.getSelectedItem(); }
    private void addDirectStream() {
        JTextField address = new JTextField(45);
        JTextField referer = new JTextField("https://live.bilibili.com/",45);
        JPanel fields = new JPanel(new GridLayout(0,1,0,8));
        fields.add(new JLabel("粘贴完整 m3u8 地址（包含原有签名参数）：")); fields.add(address);
        fields.add(new JLabel("来源页面 Referer（可选，非 Bilibili 地址可修改或清空）：")); fields.add(referer);
        fields.add(new JLabel("直链模式只播放视频；地址过期后需要重新复制。"));
        if (JOptionPane.showConfirmDialog(this,fields,"m3u8 直链播放",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            var direct = new DirectStreamPlatform(address.getText(),referer.getText());
            String title = "直链 " + (++directCount);
            var panel = new RoomPanel(direct,address.getText(),() -> GUEST,this::toggleFullscreen);
            addTab("direct:" + UUID.randomUUID(),title,panel);
        } catch (RuntimeException e) { JOptionPane.showMessageDialog(this,UiErrors.message(e),"无法播放",JOptionPane.WARNING_MESSAGE); }
    }
    private void refreshAccount() { accountLabel.setText(accounts.getOrDefault(selected().id(), AccountSession.guest()).displayName()); }
    private void addRoom() {
        try {
            LivePlatform selected = selected();
            String normalized = selected.normalizeRoomId(roomId.getText()), key = selected.id() + ":" + normalized;
            if (rooms.containsKey(key)) { tabs.setSelectedComponent(rooms.get(key)); return; }
            var room = new RoomPanel(selected, normalized,
                    () -> accounts.getOrDefault(selected.id(), GUEST), this::toggleFullscreen);
            addTab(key,selected.displayName() + " · " + normalized,room);
            roomId.setText("");
        } catch (RuntimeException e) { JOptionPane.showMessageDialog(this, UiErrors.message(e), "无法添加", JOptionPane.WARNING_MESSAGE); }
    }
    private void addTab(String key, String title, RoomPanel room) {
            rooms.put(key,room); tabs.addTab(title,room);
            var header = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
            header.setOpaque(false); header.add(new JLabel(title));
            JButton close = new JButton("×");
            close.setMargin(new Insets(0,5,0,5));
            close.addActionListener(e -> { room.close(); rooms.remove(key); tabs.remove(room); });
            header.add(close); tabs.setTabComponentAt(tabs.indexOfComponent(room),header);
            tabs.setSelectedComponent(room);
            SwingUtilities.invokeLater(room::play);
    }
    private static final AccountSession GUEST = AccountSession.guest();
    private void toggleFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        device.setFullScreenWindow(device.getFullScreenWindow() == this ? null : this);
    }
    private void leaveFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        if (device.getFullScreenWindow() == this) device.setFullScreenWindow(null);
    }
}
