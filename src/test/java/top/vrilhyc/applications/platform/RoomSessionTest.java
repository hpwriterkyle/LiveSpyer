package top.vrilhyc.applications.platform;

import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.auth.*;
import top.vrilhyc.applications.model.*;
import top.vrilhyc.applications.player.LivePlayer;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomSessionTest {
    static final AccountSession ACCOUNT = new AccountSession(7,"test",Map.of());
    static class Player implements LivePlayer {
        volatile StreamSource source;
        volatile boolean rejectAudio;
        final AtomicInteger plays = new AtomicInteger(), stops = new AtomicInteger();
        final CountDownLatch released = new CountDownLatch(1);
        public void play(StreamSource source) {
            if (rejectAudio && source.audioOnly()) throw new PlatformException("unsupported");
            this.source = source; plays.incrementAndGet();
        }
        public void stop() { stops.incrementAndGet(); }
        public void volume(int value) {}
        public void muted(boolean value) {}
        public void close() { released.countDown(); }
    }
    static class Platform implements LivePlatform {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch connecting = new CountDownLatch(1);
        volatile CountDownLatch blockConnect, blockResolve;
        volatile boolean failSend, failConnect, connectionClosed;
        final AtomicInteger connections = new AtomicInteger();
        public String id() { return "test"; }
        public String displayName() { return "test"; }
        public String normalizeRoomId(String input) { return input; }
        public QrLogin qrLogin() { throw new UnsupportedOperationException(); }
        public LiveRoom resolveRoom(String input, AccountSession session) throws Exception {
            if (blockResolve != null) { connecting.countDown(); blockResolve.await(); }
            return new LiveRoom(id(),input,input,true);
        }
        public StreamSource resolveStream(LiveRoom room, AccountSession session) {
            return new StreamSource(URI.create("https://example.com/test.m3u8"),Map.of());
        }
        public RoomInteraction newInteraction(LiveRoom room, AccountSession session) {
            connections.incrementAndGet();
            return new RoomInteraction() {
                public void connect() throws Exception {
                    events.add("connect"); connecting.countDown();
                    if (blockConnect != null) blockConnect.await();
                    if (failConnect) throw new PlatformException("failed auth");
                    if (connectionClosed) throw new CancellationException();
                }
                public void sendDanmaku(String text) {
                    events.add("send:" + room.roomId());
                    if (failSend) throw new PlatformException("failed send");
                }
                public void close() { connectionClosed = true; events.add("disconnect"); }
            };
        }
    }
    RoomSession room(Platform platform, Player player) {
        return new RoomSession(platform,"1",player,() -> ACCOUNT,ignored -> {});
    }
    @Test void audioSwitchPreservesAccountDisconnectionAndFailedSwitchKeepsVideo() throws Exception {
        var platform = new Platform(); var player = new Player();
        try (var room = room(platform,player)) {
            room.play().get(2,TimeUnit.SECONDS);
            player.rejectAudio = true;
            assertThrows(ExecutionException.class,()->room.audioOnly(true).get(2,TimeUnit.SECONDS));
            assertFalse(room.audioOnly()); assertFalse(player.source.audioOnly());
            player.rejectAudio = false;
            room.audioOnly(true).get(2,TimeUnit.SECONDS); assertTrue(player.source.audioOnly());
            room.play().get(2,TimeUnit.SECONDS); assertTrue(player.source.audioOnly());
            room.stop().get(2,TimeUnit.SECONDS); int plays = player.plays.get();
            room.audioOnly(false).get(2,TimeUnit.SECONDS); assertEquals(plays,player.plays.get());
            room.play().get(2,TimeUnit.SECONDS); assertFalse(player.source.audioOnly());
            assertEquals(0,platform.connections.get()); assertEquals(RoomSession.InteractionState.DISCONNECTED,room.interactionState());
        }
    }
    @Test void playbackNeverConnectsAndSendClosesWithoutStoppingPlayback() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        try (var room = room(platform,player)) {
            room.play().get(2,TimeUnit.SECONDS);
            assertEquals(0,platform.connections.get());
            assertEquals(RoomSession.InteractionState.DISCONNECTED,room.interactionState());
            room.send("test").get(2,TimeUnit.SECONDS);
            assertEquals(List.of("connect","send:1","disconnect"),platform.events);
            assertEquals(1,player.plays.get());
            assertEquals(0,player.stops.get());
            assertEquals(RoomSession.InteractionState.DISCONNECTED,room.interactionState());
        }
        assertTrue(player.released.await(2,TimeUnit.SECONDS));
    }
    @Test void bothAuthenticationAndSendFailureStillDisconnect() throws Exception {
        for (boolean authFailure : List.of(true,false)) {
            Platform platform = new Platform(); Player player = new Player();
            platform.failConnect = authFailure; platform.failSend = !authFailure;
            try (var room = room(platform,player)) {
                room.play().get(2,TimeUnit.SECONDS);
                assertThrows(ExecutionException.class,() -> room.send("test").get(2,TimeUnit.SECONDS));
                assertEquals("disconnect",platform.events.getLast());
                assertEquals(RoomSession.InteractionState.DISCONNECTED,room.interactionState());
                assertEquals(0,player.stops.get());
            }
        }
    }
    @Test void closeDuringConnectNeverSendsAndReleasesPlayer() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        platform.blockConnect = new CountDownLatch(1);
        var room = room(platform,player);
        room.play().get(2,TimeUnit.SECONDS);
        var sending = room.send("test");
        assertTrue(platform.connecting.await(2,TimeUnit.SECONDS));
        room.close();
        platform.blockConnect.countDown();
        assertTrue(player.released.await(2,TimeUnit.SECONDS));
        assertTrue(sending.isCancelled() || sending.isCompletedExceptionally());
        assertTrue(platform.connectionClosed);
        assertFalse(platform.events.contains("send:1"));
    }
    @Test void logoutDuringConnectPreventsSend() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        platform.blockConnect = new CountDownLatch(1);
        AtomicReference<AccountSession> account = new AtomicReference<>(ACCOUNT);
        try (var room = new RoomSession(platform,"1",player,account::get,ignored -> {})) {
            room.play().get(2,TimeUnit.SECONDS);
            var sending = room.send("test");
            assertTrue(platform.connecting.await(2,TimeUnit.SECONDS));
            account.set(AccountSession.guest()); room.disconnectInteraction();
            platform.blockConnect.countDown();
            assertThrows(Exception.class,() -> sending.get(2,TimeUnit.SECONDS));
            assertFalse(platform.events.contains("send:1"));
        }
    }
    @Test void stopBeforeResolveReturnsDoesNotStartPlayback() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        platform.blockResolve = new CountDownLatch(1);
        try (var room = room(platform,player)) {
            var play = room.play();
            assertTrue(platform.connecting.await(2,TimeUnit.SECONDS));
            var stop = room.stop();
            platform.blockResolve.countDown();
            assertThrows(CancellationException.class,() -> play.get(2,TimeUnit.SECONDS));
            stop.get(2,TimeUnit.SECONDS);
            assertEquals(0,player.plays.get());
        }
    }
    @Test void roomsOperateIndependently() throws Exception {
        Platform platform = new Platform(); Player first = new Player(), second = new Player();
        try (var a = room(platform,first); var b = new RoomSession(platform,"2",second,() -> ACCOUNT,ignored -> {})) {
            a.play().get(2,TimeUnit.SECONDS); b.play().get(2,TimeUnit.SECONDS);
            a.close();
            b.send("test").get(2,TimeUnit.SECONDS);
            assertEquals(1,second.plays.get()); assertEquals(0,second.stops.get());
            assertTrue(platform.events.contains("send:2"));
        }
    }
    @Test void duplicatePendingSendIsRejected() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        platform.blockConnect = new CountDownLatch(1);
        try (var room = room(platform,player)) {
            room.play().get(2,TimeUnit.SECONDS);
            var send = room.send("first");
            assertTrue(platform.connecting.await(2,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,() -> room.send("second").get(2,TimeUnit.SECONDS));
            platform.blockConnect.countDown(); send.get(2,TimeUnit.SECONDS);
            assertEquals(1,platform.connections.get());
        }
    }
    @Test void guestCannotOpenInteractionConnection() throws Exception {
        Platform platform = new Platform(); Player player = new Player();
        try (var room = new RoomSession(platform,"1",player,AccountSession::guest,ignored -> {})) {
            room.play().get(2,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,() -> room.send("test").get(2,TimeUnit.SECONDS));
            assertEquals(0,platform.connections.get());
        }
    }
    @Test void closingRoomDoesNotWaitForBrowserStartupToComplete() throws Exception {
        Platform platform = new Platform();
        CountDownLatch starting = new CountDownLatch(1);
        Player player = new Player() {
            @Override public void play(StreamSource source) {
                starting.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        var room = room(platform,player);
        try {
            var playback = room.play();
            assertTrue(starting.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),room::close);
            assertTrue(player.released.await(2,TimeUnit.SECONDS));
            assertTrue(playback.isCancelled());
        } finally { room.close(); }
    }
}
