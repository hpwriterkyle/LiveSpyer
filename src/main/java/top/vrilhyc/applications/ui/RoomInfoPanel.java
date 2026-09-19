package top.vrilhyc.applications.ui;

import top.vrilhyc.applications.model.*;
import top.vrilhyc.applications.platform.LivePlatform;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.util.concurrent.*;

/** EDT-owned presentation with one cancellable public-metadata request at a time. */
final class RoomInfoPanel extends JPanel implements AutoCloseable {
    private final JLabel duration = new JLabel("已播时长：加载中…");
    private final JLabel heading = new JLabel("在线列表");
    private final JTextArea names = new JTextArea(3, 18);
    private final JLabel hint = new JLabel("正在读取公开榜单…");
    private final JButton expand = UiTheme.button("展开", false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final Timer clock, refresh;
    private Future<?> pending;
    private RoomDetails details;
    private OnlineViewers viewers;
    private boolean expanded, closed;
    private int expandedRows = 8;
    private final LivePlatform platform;
    private final String input;

    RoomInfoPanel(LivePlatform platform, String input) {
        super(new BorderLayout(0, 5));
        this.platform = platform; this.input = input;
        setOpaque(false);
        duration.setForeground(UiTheme.MUTED); duration.putClientProperty("html.disable", true);
        var title = UiTheme.transparent(new BorderLayout());
        heading.putClientProperty("html.disable", true); title.add(heading); title.add(expand, BorderLayout.EAST);
        add(title, BorderLayout.NORTH);
        names.setEditable(false); names.setOpaque(false); names.setForeground(UiTheme.INK);
        names.setLineWrap(false); names.setFont(names.getFont().deriveFont(12f));
        var scroll = new JScrollPane(names); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);
        hint.setFont(hint.getFont().deriveFont(11f)); hint.setForeground(UiTheme.MUTED);
        hint.putClientProperty("html.disable", true); add(hint, BorderLayout.SOUTH);
        setToolTipText("平台公开在线榜单，最多显示 50 人，不代表全部观看者；每 30 秒更新");
        expand.addActionListener(e -> { expanded = !expanded; render(); });
        clock = new Timer(1000, e -> updateDuration());
        refresh = new Timer(30_000, e -> refresh());
        clock.start(); refresh.start(); refresh();
    }
    JLabel durationLabel() { return duration; }
    void availableHeight(int height) {
        int rows = Math.clamp((height - 430) / Math.max(1, names.getFontMetrics(names.getFont()).getHeight()), 3, 8);
        if (rows != expandedRows) { expandedRows = rows; render(); }
    }
    private void refresh() {
        if (closed || (pending != null && !pending.isDone())) return;
        pending = worker.submit(() -> {
            try {
                RoomDetails next = platform.roomDetails(input);
                SwingUtilities.invokeLater(() -> { if (!closed) { details = next; updateDuration(); } });
                try {
                    OnlineViewers online = platform.onlineViewers(next);
                    SwingUtilities.invokeLater(() -> { if (!closed) { viewers = online; render(); } });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> { if (!closed) { hint.setText("在线列表暂不可用 · 30 秒后重试"); hint.setToolTipText(UiErrors.message(e)); } });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { if (!closed) {
                    details = null; duration.setText("已播时长：暂不可用"); hint.setText("房间信息暂不可用 · 30 秒后重试");
                    hint.setToolTipText(UiErrors.message(e));
                } });
            }
        });
    }
    private void updateDuration() {
        if (details == null) return;
        duration.setText(!details.room().live() ? "主播未开播" : details.startedAt() == null
                ? "已播时长：平台未提供" : "已播 " + elapsed(details.startedAt(), Instant.now()));
        duration.setToolTipText("直播间：" + details.room().title() + " · 根据平台开播时间计算");
    }
    static String elapsed(Instant start, Instant now) {
        long seconds = Math.max(0, Duration.between(start, now).getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private void render() {
        names.setRows(expanded ? expandedRows : 3);
        expand.setText(expanded ? "收起" : "展开");
        if (viewers != null) {
            heading.setText("在线列表" + (viewers.count() >= 0 ? " · " + viewers.count() : ""));
            StringBuilder text = new StringBuilder();
            viewers.viewers().stream().limit(expanded ? 50 : 3).forEach(v -> text.append(v.rank()).append("  ")
                    .append(v.name().replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " "))
                    .append(v.gloryLevel() > 0 ? " · 荣耀 " + v.gloryLevel() : "").append('\n'));
            names.setText(text.toString().stripTrailing()); names.setCaretPosition(0);
            hint.setText(viewers.viewers().isEmpty() ? "暂无公开名单" : "公开榜单 · 非全部观看者 · 30 秒更新");
            hint.setToolTipText(null);
            expand.setEnabled(viewers.viewers().size() > 3);
        }
        revalidate(); repaint();
    }
    @Override public void close() {
        closed = true; clock.stop(); refresh.stop();
        if (pending != null) pending.cancel(true);
        worker.shutdownNow();
    }
}
