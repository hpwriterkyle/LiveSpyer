package top.vrilhyc.applications.ui;

import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/** Shared palette and small, finite animations; no animation runs while a button is idle. */
public final class UiTheme {
    public static final Color BACKGROUND=new Color(0xF4F6FC), SURFACE=Color.WHITE,
            INK=new Color(0x293650), MUTED=new Color(0x626F88), BLUE=new Color(0x355DB4),
            PINK=new Color(0xB84179), SOFT_BLUE=new Color(0xEAF0FF), SOFT_PINK=new Color(0xFCEAF3),
            LINE=new Color(0xE3E8F2);
    private UiTheme() {}

    public static void install() {
        com.formdev.flatlaf.FlatLaf.setUseNativeWindowDecorations(true);
        JFrame.setDefaultLookAndFeelDecorated(true); JDialog.setDefaultLookAndFeelDecorated(true);
        FlatLightLaf.setup();
        UIManager.put("TitlePane.background",new Color(0xF3F4FC));
        UIManager.put("TitlePane.foreground",INK);
        UIManager.put("TitlePane.unifiedBackground",false);
        UIManager.put("defaultFont",new Font("Microsoft YaHei UI",Font.PLAIN,13));
        UIManager.put("Panel.background",BACKGROUND);
        UIManager.put("Label.foreground",INK);
        UIManager.put("Component.accentColor",BLUE);
        UIManager.put("Component.focusColor",new Color(0xB3C7FA));
        UIManager.put("Component.focusWidth",2);
        UIManager.put("Component.arc",16);
        UIManager.put("Component.borderColor",LINE);
        UIManager.put("Button.arc",16);
        UIManager.put("Button.margin",new Insets(9,15,9,15));
        UIManager.put("TextComponent.arc",16);
        UIManager.put("TextField.margin",new Insets(10,13,10,13));
        UIManager.put("TextField.background",SURFACE);
        UIManager.put("TextField.selectionBackground",new Color(0xD7E4FF));
        UIManager.put("TextArea.background",SURFACE);
        UIManager.put("TextArea.foreground",INK);
        UIManager.put("ComboBox.background",SURFACE);
        UIManager.put("ComboBox.padding",new Insets(8,10,8,10));
        UIManager.put("CheckBox.icon.selectedBackground",BLUE);
        UIManager.put("CheckBox.icon.selectedBorderColor",BLUE);
        UIManager.put("CheckBox.icon.checkmarkColor",Color.WHITE);
        UIManager.put("Slider.trackColor",LINE);
        UIManager.put("Slider.thumbColor",BLUE);
        UIManager.put("Slider.trackValueColor",new Color(0x91B2F8));
        UIManager.put("ScrollBar.width",9);
        UIManager.put("ScrollBar.thumbArc",999);
        UIManager.put("ScrollBar.track",SURFACE);
        UIManager.put("ScrollBar.thumb",new Color(0xDCE2F0));
        UIManager.put("TabbedPane.background",BACKGROUND);
        UIManager.put("TabbedPane.selectedBackground",SURFACE);
        UIManager.put("TabbedPane.underlineColor",PINK);
        UIManager.put("TabbedPane.hoverColor",SOFT_BLUE);
        UIManager.put("TabbedPane.tabHeight",42);
        UIManager.put("TabbedPane.tabInsets",new Insets(7,14,7,14));
        UIManager.put("TabbedPane.contentAreaColor",BACKGROUND);
        UIManager.put("TabbedPane.showContentSeparator",false);
        UIManager.put("ToolTip.background",INK);
        UIManager.put("ToolTip.foreground",SURFACE);
        UIManager.put("OptionPane.background",BACKGROUND);
        UIManager.put("OptionPane.okButtonText","确定");
        UIManager.put("OptionPane.cancelButtonText","取消");
    }
    static JPanel transparent(LayoutManager layout) { var p=new JPanel(layout);p.setOpaque(false);return p; }
    static JPanel ambient(LayoutManager layout) {
        return new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var g=(Graphics2D)graphics.create();
                float w=Math.max(1,getWidth()),h=Math.max(1,getHeight());
                g.setPaint(new GradientPaint(0,0,new Color(0xF6F7FD),w,h,new Color(0xEEF3FD)));
                g.fillRect(0,0,getWidth(),getHeight());
                glow(g,w*.87f,h*.12f,Math.max(w,h)*.65f,new Color(243,173,211,70));
                glow(g,w*.08f,h*.85f,Math.max(w,h)*.65f,new Color(140,184,254,65));
                g.dispose();
            }
        };
    }
    private static void glow(Graphics2D g,float x,float y,float radius,Color color) {
        g.setPaint(new RadialGradientPaint(x,y,radius,new float[]{0,1},new Color[]{color,new Color(color.getRed(),color.getGreen(),color.getBlue(),0)}));
        g.fill(new Rectangle2D.Float(x-radius,y-radius,radius*2,radius*2));
    }
    static JLabel label(String text,int size,Color color) {
        var label=new JLabel(text); label.putClientProperty("html.disable",true);
        label.setFont(new Font("Microsoft YaHei UI",Font.PLAIN,size));label.setForeground(color);return label;
    }
    static JLabel heading(String text,int size) { var label=label(text,size,INK);label.setFont(label.getFont().deriveFont(Font.BOLD));return label; }
    static void chip(JLabel label,Color fill,Color text) {
        label.putClientProperty("html.disable",true);label.setOpaque(true);label.setBackground(fill);label.setForeground(text);
        label.setBorder(new EmptyBorder(5,10,5,10));label.setFont(label.getFont().deriveFont(11f));
    }
    static JPanel card(LayoutManager layout) {
        var p=new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(255,255,255,210));g.fillRoundRect(0,0,getWidth(),getHeight(),22,22);
                g.setColor(new Color(255,255,255,235));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,22,22);g.dispose();
            }
        };
        p.setOpaque(false);p.setBorder(new EmptyBorder(16,16,16,16));return p;
    }
    public static JButton button(String text,boolean primary) { return new SoftButton(text,primary); }
    public static Image logo(int size) {
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0,0,new Color(0xA9C5FF),size,size,new Color(0xF2ACD1)));
        g.fill(new RoundRectangle2D.Float(0,0,size,size,size*.32f,size*.32f));
        var play=new Path2D.Float();play.moveTo(size*.39,size*.27);play.lineTo(size*.72,size*.5);play.lineTo(size*.39,size*.73);play.closePath();
        g.setColor(Color.WHITE);g.fill(play);g.dispose();return image;
    }
    private static boolean motionAllowed() { return !Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation")); }
    private static Color blend(Color a,Color b,float value) {
        return new Color((int)(a.getRed()+(b.getRed()-a.getRed())*value),(int)(a.getGreen()+(b.getGreen()-a.getGreen())*value),(int)(a.getBlue()+(b.getBlue()-a.getBlue())*value));
    }
    private static final class SoftButton extends JButton {
        final boolean primary;
        final Timer timer;
        float hover,from,target;
        long started;
        SoftButton(String text,boolean primary) {
            super(text);this.primary=primary;
            setContentAreaFilled(false);setBorderPainted(false);setOpaque(false);setFocusPainted(false);setRolloverEnabled(true);
            setForeground(primary ? Color.WHITE : BLUE);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(10,16,10,16));setFont(getFont().deriveFont(Font.BOLD));
            timer=new Timer(16,e -> {
                float elapsed=Math.min(1,(System.nanoTime()-started)/180_000_000f);
                hover=from+(target-from)*(1-(float)Math.pow(1-elapsed,3));repaint();
                if(elapsed>=1) ((Timer)e.getSource()).stop();
            });
            getModel().addChangeListener(e -> {
                float next=isEnabled() && getModel().isRollover() ? 1 : 0;
                if(next!=target) {
                    from=hover;target=next;started=System.nanoTime();
                    if(isShowing() && motionAllowed()) timer.start();else {hover=target;timer.stop();}
                }
                repaint();
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            if(!isEnabled()) g.setComposite(AlphaComposite.SrcOver.derive(.5f));
            Color base=primary ? PINK : SOFT_BLUE, over=primary ? new Color(0xAE3D73) : new Color(0xD9E5FF);
            g.setColor(blend(base,over,getModel().isPressed() ? 1 : hover));
            g.fillRoundRect(2,2,getWidth()-4,getHeight()-4,16,16);
            if(hasFocus()) {g.setColor(BLUE);g.setStroke(new BasicStroke(1.5f));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,19,19);}
            g.dispose();super.paintComponent(graphics);
        }
        @Override public void removeNotify() { timer.stop();hover=target;super.removeNotify(); }
    }
}
