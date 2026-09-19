package top.vrilhyc.applications.platform;

/** Read-only visitor feed, independent of the account's short-lived send connection. */
public interface DanmakuSubscription extends AutoCloseable {
    void start();
    /** Optional bounded refresh of this visitor session; never uses account credentials. */
    default void autoRefresh(boolean enabled) {}
    @Override void close();
}
