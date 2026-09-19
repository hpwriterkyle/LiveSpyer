package top.vrilhyc.applications.model;

import java.time.Instant;

public record DanmakuMessage(String id, String sender, String text, Instant time,
                             Integer gloryLevel, Gift gift, boolean maskedSender) {
    public record Gift(String name, long quantity) {}

    public DanmakuMessage(String id, String sender, String text, Instant time) {
        this(id, sender, text, time, null, null);
    }

    public DanmakuMessage(String id, String sender, String text, Instant time, Integer gloryLevel, Gift gift) {
        this(id, sender, text, time, gloryLevel, gift, false);
    }

    public String displayText() {
        return (gift == null ? "" : "[礼物] ")
                + (gloryLevel == null || gloryLevel <= 0 ? "" : "[荣耀 Lv." + gloryLevel + "] ")
                + singleLine(sender) + "：" + singleLine(text);
    }

    private static String singleLine(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
    }
}
