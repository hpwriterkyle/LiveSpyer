package top.vrilhyc.applications.player;

import top.vrilhyc.applications.model.StreamSource;
import top.vrilhyc.applications.model.DanmakuMessage;

/** Called on the room worker. Implementations serialize native calls. */
public interface LivePlayer extends AutoCloseable {
    void play(StreamSource source);
    void stop();
    void volume(int percent);
    void muted(boolean muted);
    default void showDanmaku(DanmakuMessage message) {}
    default void danmakuVisible(boolean visible) {}
    default void goLive() {}
    @Override void close();
}
