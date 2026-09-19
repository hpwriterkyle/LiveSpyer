package top.vrilhyc.applications.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/** Vector welcome artwork, with no network images or continuously running animation. */
final class WelcomePanel extends JPanel {
    WelcomePanel(Runnable focusRoom,Runnable paste) {
        super(new GridBagLayout());setOpaque(false);
        var content=UiTheme.transparent(new GridBagLayout());var c=new GridBagConstraints();
        c.gridx=0;c.gridy=0;c.insets=new Insets(0,0,22,0);
        content.add(new Artwork(),c);
        c.gridy++;c.insets=new Insets(0,0,10,0);content.add(UiTheme.heading("把喜欢的直播，放在一起。",29),c);
        c.gridy++;content.add(UiTheme.label("添加一个房间，开启你的观看空间",14,UiTheme.MUTED),c);
        var actions=UiTheme.transparent(new FlowLayout(FlowLayout.CENTER,12,0));
        var add=UiTheme.button("添加第一个房间",true);add.addActionListener(e->focusRoom.run());
        var direct=UiTheme.button("使用直播链接",false);direct.addActionListener(e->paste.run());
        actions.add(add);actions.add(direct);c.gridy++;c.insets=new Insets(15,0,28,0);content.add(actions,c);
        c.gridy++;c.insets=new Insets(0,0,0,0);
        content.add(UiTheme.label("多房间观看     /     弹幕与礼物     /     独立音量控制",12,UiTheme.MUTED),c);
        add(content);
    }
    private static final class Artwork extends JComponent {
        Artwork() { setPreferredSize(new Dimension(400,205)); }
        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(UiTheme.SOFT_BLUE);g.fillOval(22,24,186,168);
            g.setColor(UiTheme.SOFT_PINK);g.fillOval(211,3,165,168);
            g.setColor(new Color(0xDFE7F7));g.fillRoundRect(88,40,248,149,24,24);
            g.setColor(Color.WHITE);g.fillRoundRect(76,28,248,149,24,24);
            g.setColor(UiTheme.LINE);g.drawRoundRect(76,28,248,149,24,24);
            g.setColor(new Color(0xF5F7FD));g.fillRoundRect(89,41,222,111,16,16);
            g.drawImage(UiTheme.logo(68),165,62,null);
            g.setColor(UiTheme.PINK);g.fillRoundRect(52,130,97,33,14,14);
            g.setColor(Color.WHITE);g.setFont(new Font("Microsoft YaHei UI",Font.BOLD,12));g.drawString("LIVE  ·  直播",64,152);
            g.setColor(UiTheme.BLUE);g.fillOval(311,28,24,24);
            g.setColor(new Color(0xF1B5D4));g.fillOval(53,51,10,10);
            g.setColor(new Color(0x95B9F8));g.fillOval(346,142,13,13);
            g.setColor(new Color(0xDAE4F7));g.fillRoundRect(174,157,52,5,5,5);g.dispose();
        }
    }
}
