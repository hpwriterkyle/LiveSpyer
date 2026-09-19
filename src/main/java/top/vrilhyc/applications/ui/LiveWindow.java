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
    private final JPanel pages = UiTheme.transparent(new CardLayout());
    private final JLabel roomCount = UiTheme.label("尚未添加直播间",12,UiTheme.MUTED);
    private final Map<String,RoomPanel> rooms = new LinkedHashMap<>();
    private final Map<String,AccountSession> accounts = new ConcurrentHashMap<>();
    private final JComboBox<LivePlatform> platform;
    private final JLabel accountLabel = new JLabel("未登录");
    private final JTextField roomId = new JTextField(15);
    private int directCount;
    private final JPanel top=UiTheme.transparent(new BorderLayout(0,10));
    private final JPanel brand=UiTheme.transparent(new BorderLayout(12,0));
    private final JPanel footer=UiTheme.transparent(new BorderLayout());
    private final JButton accountMenu=UiTheme.button("账号",false);
    private boolean focusMode, focusBeforeFullscreen;
    public LiveWindow(PlatformRegistry registry) {
        super("LiveSpyer · 多直播间");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var desktop=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1440,desktop.width-40),Math.min(900,desktop.height-40));
        setMinimumSize(new Dimension(1000,700)); setLocationRelativeTo(null);
        setIconImage(UiTheme.logo(128));
        var root=UiTheme.ambient(new BorderLayout(0,10));root.setBorder(BorderFactory.createEmptyBorder(12,16,10,16));setContentPane(root);
        platform = new JComboBox<>(registry.all().toArray(LivePlatform[]::new));
        platform.addActionListener(e -> refreshAccount());
        var identity=UiTheme.transparent(new BorderLayout(12,0));
        identity.add(new JLabel(new ImageIcon(UiTheme.logo(44))),BorderLayout.WEST);
        var words=UiTheme.transparent(new GridLayout(2,1,0,1));
        words.add(UiTheme.heading("LiveSpyer",23));words.add(UiTheme.label("你的直播观看空间",11,UiTheme.MUTED));identity.add(words);
        brand.add(identity,BorderLayout.WEST);
        var toolbar = UiTheme.card(new BorderLayout(12,0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
        roomId.putClientProperty("JTextField.placeholderText","输入房间号或直播间链接，按 Enter 添加");
        roomId.getAccessibleContext().setAccessibleName("直播间房间号或链接");
        JButton add = UiTheme.button("＋ 添加直播间",true);
        add.addActionListener(e -> addRoom()); roomId.addActionListener(e -> addRoom());
        JButton paste = UiTheme.button("粘贴 m3u8",false);
        paste.addActionListener(e -> addDirectStream());
        JButton login = UiTheme.button("扫码登录",false);
        login.addActionListener(e -> {
            LivePlatform selected = selected();
            new QrLoginDialog(this, selected.qrLogin(), session -> {
                accounts.put(selected.id(), session);
                rooms.values().forEach(RoomPanel::disconnectInteraction);
                refreshAccount();
            }).setVisible(true);
        });
        JButton logout = UiTheme.button("退出登录",false);
        logout.addActionListener(e -> {
            accounts.remove(selected().id());
            rooms.values().forEach(RoomPanel::disconnectInteraction);
            refreshAccount();
        });
        accountMenu.addActionListener(e -> {
            var menu=new JPopupMenu();
            var signIn=new JMenuItem("扫码登录");signIn.addActionListener(event -> login.doClick());
            var signOut=new JMenuItem("退出登录");signOut.addActionListener(event -> logout.doClick());
            menu.add(signIn);menu.add(signOut);menu.show(accountMenu,0,accountMenu.getHeight());
        });
        accountMenu.setVisible(false);
        UiTheme.chip(accountLabel,UiTheme.SOFT_PINK,UiTheme.PINK);
        var account=UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT,8,0));
        account.add(accountLabel);account.add(login);account.add(logout);brand.add(account,BorderLayout.EAST);
        platform.setPreferredSize(new Dimension(133,40));
        toolbar.add(platform,BorderLayout.WEST);toolbar.add(roomId,BorderLayout.CENTER);
        var actions=UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT,8,0));actions.add(add);actions.add(paste);actions.add(accountMenu);toolbar.add(actions,BorderLayout.EAST);
        top.add(brand,BorderLayout.NORTH);top.add(toolbar,BorderLayout.CENTER);add(top,BorderLayout.NORTH);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setOpaque(false);
        pages.add(new WelcomePanel(()->roomId.requestFocusInWindow(),this::addDirectStream),"welcome");pages.add(tabs,"rooms");
        add(pages,BorderLayout.CENTER);
        footer.add(roomCount,BorderLayout.WEST);
        footer.add(UiTheme.label("播放与账号互动独立",11,UiTheme.MUTED),BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> viewAction("escape"), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> viewAction("fullscreen"), KeyStroke.getKeyStroke(KeyEvent.VK_F11,0), JComponent.WHEN_IN_FOCUSED_WINDOW);
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
        JPanel fields = UiTheme.card(new GridLayout(0,1,0,10));
        fields.add(new JLabel("粘贴完整 m3u8 地址（包含原有签名参数）：")); fields.add(address);
        fields.add(new JLabel("来源页面 Referer（可选，非 Bilibili 地址可修改或清空）：")); fields.add(referer);
        fields.add(new JLabel("直链模式只播放视频；地址过期后需要重新复制。"));
        if (JOptionPane.showConfirmDialog(this,fields,"m3u8 直链播放",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            var direct = new DirectStreamPlatform(address.getText(),referer.getText());
            String title = "直链 " + (++directCount);
            var panel = new RoomPanel(direct,address.getText(),() -> GUEST,this::viewAction);
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
                    () -> accounts.getOrDefault(selected.id(), GUEST), this::viewAction);
            addTab(key,selected.displayName() + " · " + normalized,room);
            roomId.setText("");
        } catch (RuntimeException e) { JOptionPane.showMessageDialog(this, UiErrors.message(e), "无法添加", JOptionPane.WARNING_MESSAGE); }
    }
    private void addTab(String key, String title, RoomPanel room) {
            rooms.put(key,room); tabs.addTab(title,room);
            var header = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
            header.setOpaque(false); header.add(new JLabel(title));
            JButton close = UiTheme.button("×",false);
            close.setMargin(new Insets(0,5,0,5));
            close.setToolTipText("关闭直播间");
            close.addActionListener(e -> { room.close(); rooms.remove(key); tabs.remove(room);refreshRooms(); });
            header.add(close); tabs.setTabComponentAt(tabs.indexOfComponent(room),header);
            tabs.setSelectedComponent(room);
            refreshRooms();
            room.focusMode(focusMode);
            SwingUtilities.invokeLater(room::play);
    }
    private void refreshRooms() {
        if(rooms.isEmpty()) { leaveFullscreen(); setFocusMode(false); }
        brand.setVisible(rooms.isEmpty());accountMenu.setVisible(!rooms.isEmpty());
        ((CardLayout)pages.getLayout()).show(pages,rooms.isEmpty()?"welcome":"rooms");
        roomCount.setText(rooms.isEmpty()?"尚未添加直播间":rooms.size()+" 个直播间 · 可独立控制播放");
    }
    private static final AccountSession GUEST = AccountSession.guest();
    private void viewAction(String action) {
        switch(action) {
            case "focus" -> setFocusMode(!focusMode);
            case "fullscreen" -> { if(!rooms.isEmpty()) toggleFullscreen(); }
            case "escape" -> {
                if(getGraphicsConfiguration().getDevice().getFullScreenWindow()==this) leaveFullscreen();
                else setFocusMode(false);
            }
            default -> { }
        }
    }
    private void setFocusMode(boolean value) {
        focusMode=value && !rooms.isEmpty();top.setVisible(!focusMode);footer.setVisible(!focusMode);
        getContentPane().setPreferredSize(null);
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(focusMode?6:12,focusMode?8:16,focusMode?6:10,focusMode?8:16));
        rooms.values().forEach(room -> room.focusMode(focusMode));
        getContentPane().revalidate();getContentPane().repaint();
    }
    private void toggleFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        if(device.getFullScreenWindow()==this) leaveFullscreen();
        else { focusBeforeFullscreen=focusMode;setFocusMode(true);device.setFullScreenWindow(this); }
    }
    private void leaveFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        if (device.getFullScreenWindow() == this) { device.setFullScreenWindow(null);setFocusMode(focusBeforeFullscreen); }
    }
}
