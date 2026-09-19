package top.vrilhyc.applications.platform;

/** Independent of playback. close is idempotent and cancels an in-flight connect. */
public interface RoomInteraction extends AutoCloseable {
    /** Return only after server authentication acknowledgment. */
    void connect() throws Exception;
    /** Return after a successful server response; never automatically retry. */
    void sendDanmaku(String text) throws Exception;
    @Override void close();
}
