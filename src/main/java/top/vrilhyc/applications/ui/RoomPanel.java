package top.vrilhyc.applications.ui;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.*;
import top.vrilhyc.applications.player.BrowserPlayer;
import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

final class RoomPanel extends JPanel implements AutoCloseable {
    private final JLabel playback = new JLabel("尚未播放");
    private final JLabel interaction = new JLabel("互动：disconnected");
    private final JTextField message = new JTextField();
    private final JButton send = new JButton("发送弹幕");
    private final RoomSession session;
    private boolean closed;
    private int generation, retries;
    private Timer retryTimer;
    RoomPanel(LivePlatform platform, String roomId, Supplier<AccountSession> account, Runnable fullscreen) {
        super(new BorderLayout(8,8));
        setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        var player = new BrowserPlayer(canvas,
                text -> SwingUtilities.invokeLater(() -> { if (!closed) playback.setText(text); }),
                () -> SwingUtilities.invokeLater(this::retryPlayback));
        session = new RoomSession(platform, roomId, player, account,
                state -> SwingUtilities.invokeLater(() -> {
                    if (!closed) interaction.setText("互动：" + state.name().toLowerCase());
                }));
        var status = new JPanel(new BorderLayout());
        status.add(playback, BorderLayout.WEST); status.add(interaction, BorderLayout.EAST);
        add(status, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        var controls = new JPanel(new BorderLayout(8,8));
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        JButton play = new JButton("播放 / 重新取流");
        play.addActionListener(e -> { retries = 0; play(); });
        JButton stop = new JButton("停止");
        stop.addActionListener(e -> {
            generation++; cancelRetry();
            session.stop().whenComplete((v, error) -> SwingUtilities.invokeLater(() -> {
                if (!closed) playback.setText(error == null ? "已停止" : UiErrors.message(error));
            }));
        });
        JCheckBox mute = new JCheckBox("静音");
        mute.addActionListener(e -> session.muted(mute.isSelected()));
        JSlider volume = new JSlider(0,100,30);
        volume.setPreferredSize(new Dimension(130,26));
        volume.addChangeListener(e -> { if (!volume.getValueIsAdjusting()) session.volume(volume.getValue()); });
        JButton full = new JButton("全屏");
        full.addActionListener(e -> fullscreen.run());
        buttons.add(play); buttons.add(stop); buttons.add(new JLabel("音量")); buttons.add(volume); buttons.add(mute); buttons.add(full);
        controls.add(buttons, BorderLayout.NORTH);
        var chat = new JPanel(new BorderLayout(8,0));
        message.putClientProperty("JTextField.placeholderText", "输入弹幕；发送时连接，发送后自动断开");
        chat.add(message, BorderLayout.CENTER); chat.add(send, BorderLayout.EAST);
        send.addActionListener(e -> send()); message.addActionListener(e -> send());
        if (!platform.id().equals("direct")) controls.add(chat, BorderLayout.SOUTH);
        add(controls, BorderLayout.SOUTH);
    }
    void play() {
        int ticket = ++generation;
        cancelRetry();
        playback.setText("正在解析并加载直播流…");
        session.play().whenComplete((room, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || generation != ticket) return;
            playback.setText(error == null ? room.title() + " · 已加载直播流" : UiErrors.message(error));
        }));
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
    @Override public void close() { closed = true; generation++; cancelRetry(); session.close(); }
}
