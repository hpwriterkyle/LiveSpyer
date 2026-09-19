package top.vrilhyc.applications.ui;

import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.platform.*;
import top.vrilhyc.applications.auth.*;
import top.vrilhyc.applications.model.*;
import javax.swing.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RoomInfoPanelTest {
    @Test void durationDoesNotWrapAfterADayAndNeverBecomesNegative() {
        Instant start = Instant.parse("2026-09-18T00:00:00Z");
        assertEquals("27:01:02",RoomInfoPanel.elapsed(start,start.plusSeconds(27*3600+62)));
        assertEquals("00:00:00",RoomInfoPanel.elapsed(start,start.minusSeconds(1)));
    }
    @Test void closingRoomCancelsMetadataAndSuppressesLateUiResults() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var ranking = new AtomicBoolean();
        LivePlatform platform = new LivePlatform() {
            public String id() { return "test"; }
            public String displayName() { return "test"; }
            public String normalizeRoomId(String input) { return input; }
            public QrLogin qrLogin() { throw new UnsupportedOperationException(); }
            public LiveRoom resolveRoom(String input,AccountSession account) { throw new AssertionError(); }
            public StreamSource resolveStream(LiveRoom room,AccountSession account) { throw new AssertionError(); }
            public RoomInteraction newInteraction(LiveRoom room,AccountSession account) { throw new AssertionError(); }
            public RoomDetails roomDetails(String input) throws Exception {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch(InterruptedException e) { interrupted.countDown(); throw e; }
                throw new AssertionError();
            }
            public OnlineViewers onlineViewers(RoomDetails room) { ranking.set(true); throw new AssertionError(); }
        };
        RoomInfoPanel[] panel = {null};
        SwingUtilities.invokeAndWait(()->panel[0]=new RoomInfoPanel(platform,"1"));
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(panel[0]::close);
            assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->assertEquals("已播时长：加载中…",panel[0].durationLabel().getText()));
            assertFalse(ranking.get());
        } finally { SwingUtilities.invokeAndWait(panel[0]::close); }
    }
}
