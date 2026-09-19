package top.vrilhyc.applications.platform.bilibili;

import java.time.Duration;
import top.vrilhyc.applications.model.DanmakuMessage;

/** A refresh is an optional visitor operation, never an account reconnect or message resend. */
final class GuestRefreshPolicy {
    private final long interval;
    private boolean enabled=true, masked, clear, awaitingResult, paused, refreshPending;
    private long connectedAt;
    private int ineffective, refreshes;

    GuestRefreshPolicy() { this(Duration.ofSeconds(15)); }
    GuestRefreshPolicy(Duration interval) { this.interval=interval.toNanos(); }

    synchronized void enabled(boolean value) {
        if(value && !enabled) { paused=false; ineffective=0; awaitingResult=false; }
        enabled=value;
    }
    synchronized void connected(long now) { connectedAt=now; masked=false; clear=false; refreshPending=false; }
    synchronized void received(DanmakuMessage message) {
        if(message.gift()!=null) return;
        if(message.maskedSender()) masked=true;
        else if(!message.sender().contains("*") && !message.sender().contains("＊") && !message.sender().equals("未知用户")) clear=true;
    }
    synchronized boolean shouldRefresh(long now) {
        if(!enabled || paused || refreshPending || (!masked && now-connectedAt<interval)) return false;
        if(awaitingResult) {
            if(clear) ineffective=0;
            else if(masked) ineffective++;
        }
        if(ineffective>=3) { paused=true; return false; }
        awaitingResult=true; refreshes++;
        // Prevent repeated requests before a new connection has started.
        masked=false; connectedAt=now; refreshPending=true;
        return true;
    }
    synchronized boolean paused() { return enabled && paused; }
    synchronized int refreshes() { return refreshes; }
}
