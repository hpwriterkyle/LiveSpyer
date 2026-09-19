package top.vrilhyc.applications.player;

import top.vrilhyc.applications.model.StreamSource;

/** Called on the room worker. Implementations serialize native calls. */
public interface LivePlayer extends AutoCloseable {
    void play(StreamSource source);
    void stop();
    void volume(int percent);
    void muted(boolean muted);
    @Override void close();
}
