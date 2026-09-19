package top.vrilhyc.applications.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import top.vrilhyc.applications.auth.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class QrLoginDialog extends JDialog {
    private final QrLogin login;
    private final Consumer<AccountSession> success;
    private final JLabel image = new JLabel("正在获取二维码…", SwingConstants.CENTER);
    private final JLabel status = new JLabel("请使用哔哩哔哩 App 扫码", SwingConstants.CENTER);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private Future<?> task;
    private int generation;
    QrLoginDialog(JFrame owner, QrLogin login, Consumer<AccountSession> success) {
        super(owner, "扫码登录", true);
        this.login = login; this.success = success;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root=UiTheme.ambient(new BorderLayout(0,18));
        root.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));setContentPane(root);
        var intro=UiTheme.transparent(new GridLayout(2,1,0,8));
        intro.add(UiTheme.heading("连接你的 Bilibili 账号",20));
        intro.add(UiTheme.label("使用哔哩哔哩 App 扫码，并在手机上确认",12,UiTheme.MUTED));
        add(intro,BorderLayout.NORTH);
        image.setPreferredSize(new Dimension(320,320));
        var qr=UiTheme.card(new BorderLayout());qr.add(image);add(qr, BorderLayout.CENTER);
        var bottom = UiTheme.transparent(new BorderLayout(8,14));
        status.setForeground(UiTheme.BLUE);
        bottom.add(status, BorderLayout.NORTH);
        JButton refresh = UiTheme.button("刷新二维码",true);
        refresh.addActionListener(e -> refresh());
        bottom.add(refresh, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                generation++;
                if (task != null) task.cancel(true);
                worker.shutdownNow();
            }
        });
        pack(); setLocationRelativeTo(owner); refresh();
    }
    private void refresh() {
        int ticket = ++generation;
        if (task != null) task.cancel(true);
        image.setIcon(null); image.setText("正在获取二维码…");
        status.setText("请使用哔哩哔哩 App 扫码");
        task = worker.submit(() -> {
            try {
                QrLogin.Challenge challenge = login.generate();
                var bitmap = MatrixToImageWriter.toBufferedImage(new MultiFormatWriter()
                        .encode(challenge.imageContent().toString(), BarcodeFormat.QR_CODE, 300, 300));
                update(ticket, () -> { image.setText(""); image.setIcon(new ImageIcon(bitmap)); });
                while (!Thread.currentThread().isInterrupted() && Instant.now().isBefore(challenge.expiresAt())) {
                    Thread.sleep(2200);
                    QrLogin.Result result = login.poll(challenge);
                    switch (result.status()) {
                        case WAITING_SCAN -> update(ticket, () -> status.setText("等待扫码"));
                        case WAITING_CONFIRMATION -> update(ticket, () -> status.setText("已扫码，请在手机上确认"));
                        case EXPIRED -> { update(ticket, () -> status.setText("二维码已过期，请刷新")); return; }
                        case SUCCESS -> {
                            update(ticket, () -> { success.accept(result.session()); dispose(); });
                            return;
                        }
                    }
                }
                update(ticket, () -> status.setText("二维码已过期，请刷新"));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { update(ticket, () -> status.setText(UiErrors.message(e))); }
        });
    }
    private void update(int ticket, Runnable action) {
        SwingUtilities.invokeLater(() -> { if (isDisplayable() && generation == ticket) action.run(); });
    }
}
