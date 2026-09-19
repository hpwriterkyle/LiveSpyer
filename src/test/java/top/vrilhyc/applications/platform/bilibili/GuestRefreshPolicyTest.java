package top.vrilhyc.applications.platform.bilibili;

import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.model.DanmakuMessage;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class GuestRefreshPolicyTest {
    private static long seconds(long n) { return Duration.ofSeconds(n).toNanos(); }
    private static DanmakuMessage message(boolean masked) {
        return new DanmakuMessage("id",masked ? "小***" : "完整昵称","消息",Instant.EPOCH,1,null,masked);
    }
    @Test void refreshesEveryFifteenSecondsAndDoesNotDuplicatePendingRefresh() {
        var policy=new GuestRefreshPolicy();policy.connected(0);
        assertFalse(policy.shouldRefresh(seconds(14)));
        assertTrue(policy.shouldRefresh(seconds(15)));
        policy.received(message(true));assertFalse(policy.shouldRefresh(seconds(16)));
        assertFalse(policy.shouldRefresh(seconds(40)));
        policy.connected(seconds(41));
        assertFalse(policy.shouldRefresh(seconds(55)));assertTrue(policy.shouldRefresh(seconds(56)));
    }
    @Test void redactionRefreshesImmediatelyEvenJustAfterConnecting() {
        var policy=new GuestRefreshPolicy();policy.connected(seconds(100));
        policy.received(message(true));assertTrue(policy.shouldRefresh(seconds(100)));
        policy.connected(seconds(102));policy.received(message(true));
        assertTrue(policy.shouldRefresh(seconds(102)));
        policy.enabled(false);policy.connected(seconds(105));policy.received(message(true));
        assertFalse(policy.shouldRefresh(seconds(105)));assertFalse(policy.shouldRefresh(seconds(200)));
    }
    @Test void quietSessionsStillRefreshWithoutBeingCountedAsFailuresOrRecovery() {
        var policy=new GuestRefreshPolicy();
        for(int i=0;i<10;i++) {
            policy.connected(seconds(i*15));assertTrue(policy.shouldRefresh(seconds((i+1)*15)));
        }
        assertFalse(policy.paused());
    }
    @Test void pausesAfterThreeIneffectiveRefreshesButCanBeExplicitlyReenabled() {
        var policy=new GuestRefreshPolicy();
        for(int i=0;i<4;i++) {
            policy.connected(seconds(i*60));policy.received(message(true));
            assertEquals(i<3,policy.shouldRefresh(seconds((i+1)*60)));
        }
        assertTrue(policy.paused()); assertEquals(3,policy.refreshes());
        assertFalse(policy.shouldRefresh(seconds(9999)));
        policy.enabled(false);assertFalse(policy.shouldRefresh(seconds(10000)));
        policy.enabled(true);assertTrue(policy.shouldRefresh(seconds(10000)));
    }
    @Test void recoveredPublicNamesResetFailureCountAndGiftsDoNotProveRecovery() {
        var policy=new GuestRefreshPolicy();
        for(int i=0;i<8;i++) {
            policy.connected(seconds(i*60));policy.received(message(false));policy.received(message(true));
            assertTrue(policy.shouldRefresh(seconds((i+1)*60)));
        }
        for(int i=8;i<11;i++) {
            policy.connected(seconds(i*60));policy.received(message(true));
            policy.received(new DanmakuMessage("gift","完整昵称","礼物",Instant.EPOCH,null,new DanmakuMessage.Gift("花",1)));
            assertEquals(i<10,policy.shouldRefresh(seconds((i+1)*60)));
        }
        assertTrue(policy.paused());
    }
}
