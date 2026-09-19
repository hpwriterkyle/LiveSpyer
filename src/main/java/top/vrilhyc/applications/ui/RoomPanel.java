package top.vrilhyc.applications.ui;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.*;
import top.vrilhyc.applications.player.BrowserPlayer;
import top.vrilhyc.applications.model.DanmakuMessage;
import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashSet;

final class RoomPanel extends JPanel implements AutoCloseable {
    private final JLabel playback = new JLabel("尚未播放");
    private final JLabel interaction = new JLabel("互动：disconnected");
    private final JTextField message = new JTextField();
    private final JButton send = UiTheme.button("发送弹幕",true);
    private final RoomSession session;
    private boolean closed;
    private int generation, retries;
    private Timer retryTimer;
    private final LivePlatform platform;
    private final BrowserPlayer player;
    private final RoomInfoPanel roomInfo;
    private final JCheckBox showDanmaku = new JCheckBox("显示弹幕与礼物",true);
    private final JCheckBox autoRefresh = new JCheckBox("自动刷新弹幕会话",true);
    private final JTextArea danmakuStatus = new JTextArea("游客弹幕：等待直播间加载");
    private String receptionStatus = "游客弹幕：等待直播间加载";
    private boolean maskedNamesReceived;
    private final JTextArea danmakuLog = new JTextArea(3,20);
    private record Received(int epoch, DanmakuMessage message) {}
    private final ArrayBlockingQueue<Received> incoming = new ArrayBlockingQueue<>(200);
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final AtomicInteger feedEpoch = new AtomicInteger();
    private final Timer drain;
    private DanmakuSubscription subscription;
    private JSplitPane split;
    private boolean focused, sidebarExpanded=true;
    private int sidebarWidth=280;
    private final JButton focusButton=UiTheme.button("专注观看",false);
    private final JButton sidebarButton=UiTheme.button("收起消息",false);
    RoomPanel(LivePlatform platform, String roomId, Supplier<AccountSession> account, Consumer<String> viewAction) {
        super(new BorderLayout(0,12));
        this.platform=platform;
        roomInfo = platform.supportsRoomDetails() ? new RoomInfoPanel(platform, roomId) : null;
        if (roomInfo != null) addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { roomInfo.availableHeight(getHeight()); }
        });
        setOpaque(false);setBorder(BorderFactory.createEmptyBorder(12,0,0,0));
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        player = new BrowserPlayer(canvas,
                text -> SwingUtilities.invokeLater(() -> { if (!closed) playback.setText(text); }),
                () -> SwingUtilities.invokeLater(this::retryPlayback),
                action -> SwingUtilities.invokeLater(() -> { if(!closed) viewAction.accept(action); }));
        session = new RoomSession(platform, roomId, player, account,
                state -> SwingUtilities.invokeLater(() -> {
                    if (!closed) interaction.setText("互动：" + state.name().toLowerCase());
                }));
        var status = UiTheme.transparent(new BorderLayout(12,0));
        playback.putClientProperty("html.disable",true);playback.setForeground(UiTheme.MUTED);playback.setFont(playback.getFont().deriveFont(12f));
        UiTheme.chip(interaction,UiTheme.SOFT_BLUE,UiTheme.BLUE);
        var viewButtons=UiTheme.transparent(new FlowLayout(FlowLayout.RIGHT,5,0));
        viewButtons.add(interaction);viewButtons.add(sidebarButton);viewButtons.add(focusButton);
        sidebarButton.setVisible(!platform.id().equals("direct"));
        sidebarButton.addActionListener(e -> { sidebarExpanded=!sidebarExpanded;updateSidebar(); });
        focusButton.addActionListener(e -> viewAction.accept("focus"));
        focusButton.setToolTipText("扩大画布并隐藏工具区与消息栏；双击视频也可切换，Esc 返回");
        var labels = UiTheme.transparent(new GridLayout(0,1,0,2)); labels.add(playback);
        if (roomInfo != null) labels.add(roomInfo.durationLabel());
        status.add(labels, BorderLayout.CENTER); status.add(viewButtons, BorderLayout.EAST);
        add(status, BorderLayout.NORTH);
        playback.setToolTipText("距流边缘为播放列表估算值，不含主播编码和 CDN 延迟，也不等于与网页的差值");
        var viewing=UiTheme.transparent(new BorderLayout(0,10));
        var videoFrame=UiTheme.card(new BorderLayout());videoFrame.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        videoFrame.add(canvas);viewing.add(videoFrame,BorderLayout.CENTER);
        var controls = UiTheme.card(new BorderLayout(8,8));
        controls.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        var buttons = UiTheme.transparent(new FlowLayout(FlowLayout.LEFT,3,0));
        JButton play = UiTheme.button("重新取流",false);
        play.addActionListener(e -> { retries = 0; play(); });
        JButton live = UiTheme.button("回到直播",true);
        live.addActionListener(e -> player.goLive());
        JButton stop = UiTheme.button("停止",false);
        stop.addActionListener(e -> {
            generation++; cancelRetry();
            session.stop().whenComplete((v, error) -> SwingUtilities.invokeLater(() -> {
                if (!closed) playback.setText(error == null ? "已停止" : UiErrors.message(error));
            }));
        });
        JCheckBox mute = new JCheckBox("静音");
        mute.addActionListener(e -> session.muted(mute.isSelected()));
        JSlider volume = new JSlider(0,100,30);
        volume.setPreferredSize(new Dimension(88,30));volume.setOpaque(false);
        volume.getAccessibleContext().setAccessibleName("播放音量");
        volume.addChangeListener(e -> { if (!volume.getValueIsAdjusting()) session.volume(volume.getValue()); });
        JButton full = UiTheme.button("全屏",false);
        full.addActionListener(e -> viewAction.accept("fullscreen"));full.setToolTipText("F11 全屏 / 还原窗口，Esc 退出");
        buttons.add(play); buttons.add(live); buttons.add(stop); buttons.add(new JLabel("音量")); buttons.add(volume); buttons.add(mute); buttons.add(full);
        controls.add(buttons, BorderLayout.NORTH);
        JCheckBox audio = new JCheckBox("无画面 · 仅音频流"); audio.setOpaque(false);
        JLabel audioFeedback = UiTheme.label("",11,UiTheme.MUTED);
        audioFeedback.putClientProperty("html.disable",true);
        audio.setToolTipText("只读取音轨，保留声音和弹幕；切换会短暂重新缓冲。不支持的直播源会提示失败并保留原模式");
        audio.addActionListener(e -> {
            boolean desired = audio.isSelected(); audio.setEnabled(false); cancelRetry();
            audioFeedback.setText("正在切换…"); audioFeedback.setToolTipText(null);
            playback.setText(desired ? "正在准备仅音频流…" : "正在恢复直播画面…");
            session.audioOnly(desired).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return;
                audio.setEnabled(true); audio.setSelected(session.audioOnly());
                if (error != null) {
                    audioFeedback.setText("切换失败，已保留原模式"); audioFeedback.setToolTipText(UiErrors.message(error));
                    playback.setText(UiErrors.message(error));
                } else { audioFeedback.setText(""); playback.setText(desired ? "仅音频模式 · 保留声音与弹幕" : "已恢复画面"); }
            }));
        });
        var audioOptions = UiTheme.transparent(new FlowLayout(FlowLayout.LEFT,0,0));
        audioOptions.add(audio); audioOptions.add(audioFeedback); controls.add(audioOptions, BorderLayout.SOUTH);
        viewing.add(controls,BorderLayout.SOUTH);
        var chat = UiTheme.transparent(new BorderLayout(0,8));
        message.putClientProperty("JTextField.placeholderText", "说点什么…");
        message.getAccessibleContext().setAccessibleName("弹幕内容");
        message.setToolTipText("发送时连接，发送完成后自动断开账号互动连接");
        chat.add(message, BorderLayout.CENTER); chat.add(send, BorderLayout.SOUTH);
        send.addActionListener(e -> send()); message.addActionListener(e -> send());
        if (!platform.id().equals("direct")) {
            var reception=UiTheme.card(new BorderLayout(0,6));
            reception.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
            reception.setPreferredSize(new Dimension(280,400));reception.setMinimumSize(new Dimension(240,200));
            var receptionHeader=UiTheme.transparent(new BorderLayout(0,6));
            receptionHeader.add(UiTheme.heading("实时消息",17),BorderLayout.NORTH);
            var feedOptions=UiTheme.transparent(new GridLayout(2,1,0,2));
            showDanmaku.setOpaque(false);autoRefresh.setOpaque(false);
            feedOptions.add(showDanmaku); feedOptions.add(autoRefresh);
            receptionHeader.add(feedOptions,BorderLayout.CENTER);
            danmakuStatus.setEditable(false);danmakuStatus.setOpaque(false);danmakuStatus.setLineWrap(true);danmakuStatus.setWrapStyleWord(true);
            danmakuStatus.setRows(2);danmakuStatus.setForeground(UiTheme.MUTED);danmakuStatus.setFont(playback.getFont());
            receptionHeader.add(danmakuStatus,BorderLayout.SOUTH);
            autoRefresh.setToolTipText("每 15 秒刷新游客会话，收到打码昵称立即刷新；连续 3 次无效后暂停。切换此选项可重新尝试，不保证恢复全名");
            autoRefresh.addActionListener(e -> { if(subscription!=null) subscription.autoRefresh(autoRefresh.isSelected()); });
            var sidebarHeader = UiTheme.transparent(new BorderLayout(0,6));
            if (roomInfo != null) sidebarHeader.add(roomInfo, BorderLayout.NORTH);
            sidebarHeader.add(receptionHeader, BorderLayout.CENTER);
            reception.add(sidebarHeader,BorderLayout.NORTH);
            danmakuLog.setEditable(false); danmakuLog.setLineWrap(true); danmakuLog.setWrapStyleWord(true);
            danmakuLog.setBackground(new Color(0xF7F8FD));danmakuLog.setForeground(UiTheme.INK);
            danmakuLog.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
            var scroll=new JScrollPane(danmakuLog);scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            reception.add(scroll,BorderLayout.CENTER);reception.add(chat,BorderLayout.SOUTH);
            split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,viewing,reception);
            viewing.setMinimumSize(new Dimension(610,240));split.setOpaque(false);split.setBorder(BorderFactory.createEmptyBorder());
            split.setDividerSize(12);split.setResizeWeight(1);split.setContinuousLayout(true);
            add(split,BorderLayout.CENTER);
            showDanmaku.setToolTipText("显示平台提供的昵称、荣耀等级和礼物；未提供的等级不显示，打码昵称无法保证还原。独立游客连接可能计为在线");
            showDanmaku.addActionListener(e -> {
                player.danmakuVisible(showDanmaku.isSelected());
                if(showDanmaku.isSelected()) startDanmaku();
                else { stopDanmaku(); setDanmakuStatus("游客弹幕：已关闭"); }
            });
        } else add(viewing,BorderLayout.CENTER);
        drain=new Timer(100,e -> drainDanmaku()); drain.start();
    }
    void focusMode(boolean value) {
        focused=value;focusButton.setText(value ? "退出专注" : "专注观看");
        sidebarButton.setEnabled(!value);updateSidebar();
    }
    private void updateSidebar() {
        if(split==null) return;
        boolean show=sidebarExpanded && !focused;
        var panel=split.getRightComponent();
        if(!show && panel.isVisible() && panel.getWidth()>0) sidebarWidth=panel.getWidth();
        panel.setVisible(show);split.setDividerSize(show?12:0);
        sidebarButton.setText(sidebarExpanded?"收起消息":"展开消息");
        if(show) SwingUtilities.invokeLater(() -> {
            if(!closed && sidebarExpanded && !focused) split.setDividerLocation(Math.max(0,split.getWidth()-sidebarWidth-12));
        });
        else split.setDividerLocation(1.0);
        split.revalidate();split.repaint();
    }
    void play() {
        int ticket = ++generation;
        cancelRetry();
        playback.setText("正在解析并加载直播流…");
        session.play().whenComplete((room, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || generation != ticket) return;
            playback.setText(error == null ? room.title() + " · 已加载直播流" : UiErrors.message(error));
            if(error==null) startDanmaku();
        }));
    }
    private void startDanmaku() {
        if(closed || platform.id().equals("direct") || !showDanmaku.isSelected() || subscription!=null || session.room()==null) return;
        int ticket=feedEpoch.incrementAndGet();
        maskedNamesReceived=false;
        try {
            subscription=platform.subscribeDanmaku(session.room(), item -> {
                if(feedEpoch.get()!=ticket) return;
                var received=new Received(ticket,item);
                if(!incoming.offer(received)) { incoming.poll(); incoming.offer(received); }
            }, text -> SwingUtilities.invokeLater(() -> { if(!closed && feedEpoch.get()==ticket) setDanmakuStatus(text); }));
            subscription.autoRefresh(autoRefresh.isSelected()); subscription.start();
        } catch(RuntimeException e) { setDanmakuStatus("游客弹幕："+UiErrors.message(e)); }
    }
    private void setDanmakuStatus(String text) {
        receptionStatus=text;
        danmakuStatus.setText(text+(maskedNamesReceived ? " · 收到平台打码昵称" : ""));
        danmakuStatus.setToolTipText(maskedNamesReceived
                ? "平台向游客下发了星号昵称和隐藏的 UID，无法可靠还原全名；回到直播只调整视频播放位置"
                : "游客接收与账号互动独立；回到直播不改变昵称或弹幕连接");
    }
    private void stopDanmaku() {
        feedEpoch.incrementAndGet();
        if(subscription!=null) { subscription.close(); subscription=null; }
        incoming.clear();
        maskedNamesReceived=false;
    }
    private void drainDanmaku() {
        if(closed || !showDanmaku.isSelected()) return;
        for(int i=0;i<40;i++) {
            var received=incoming.poll(); if(received==null) break;
            if(received.epoch()!=feedEpoch.get()) continue;
            var item=received.message();
            if(item.maskedSender() && !maskedNamesReceived) {
                maskedNamesReceived=true; setDanmakuStatus(receptionStatus);
            }
            if(!seen.add(item.id())) continue;
            if(seen.size()>1000) seen.remove(seen.getFirst());
            danmakuLog.append(item.displayText()+"\n");
            if(danmakuLog.getDocument().getLength()>16000) {
                try { danmakuLog.getDocument().remove(0,danmakuLog.getDocument().getLength()-12000); }
                catch(javax.swing.text.BadLocationException ignored) { }
            }
            danmakuLog.setCaretPosition(danmakuLog.getDocument().getLength());
            player.showDanmaku(item);
        }
    }
    private void retryPlayback() {
        if (closed) return;
        if (retries >= 2) { playback.setText("播放中断，请点击重新取流"); return; }
        retries++;
        int ticket = generation;
        playback.setText("播放中断，正在重新取流（" + retries + "/2）");
        retryTimer = new Timer(retries * 2000, e -> { if (!closed && generation == ticket) play(); });
        retryTimer.setRepeats(false); retryTimer.start();
    }
    private void cancelRetry() { if (retryTimer != null) { retryTimer.stop(); retryTimer = null; } }
    private void send() {
        if (!send.isEnabled()) return;
        String text = message.getText();
        send.setEnabled(false);
        session.send(text).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            send.setEnabled(true);
            if (error == null) {
                if (message.getText().equals(text)) message.setText("");
                send.setToolTipText("弹幕已发送，互动连接已断开");
            } else JOptionPane.showMessageDialog(this, UiErrors.message(error), "发送失败", JOptionPane.WARNING_MESSAGE);
        }));
    }
    void disconnectInteraction() { session.disconnectInteraction(); }
    @Override public void close() { closed = true; generation++; cancelRetry(); stopDanmaku(); drain.stop(); if(roomInfo != null) roomInfo.close(); session.close(); }
}
