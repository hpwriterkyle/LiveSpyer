package top.vrilhyc.applications.platform;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.model.LiveRoom;
import top.vrilhyc.applications.model.StreamSource;
import top.vrilhyc.applications.player.LivePlayer;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One room owns its player and a serial worker. Account login never implies room presence. */
public final class RoomSession implements AutoCloseable {
    public enum InteractionState { DISCONNECTED, CONNECTING, CONNECTED }
    private final LivePlatform platform;
    private final String input;
    private final LivePlayer player;
    private final Supplier<AccountSession> account;
    private final Consumer<InteractionState> listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final AtomicLong playbackVersion = new AtomicLong();
    private final AtomicLong interactionVersion = new AtomicLong();
    private volatile boolean closed;
    private volatile RoomInteraction interaction;
    private volatile LiveRoom room;
    private StreamSource lastSource;
    private volatile boolean audioOnly;
    private boolean stopped = true;
    private volatile InteractionState state = InteractionState.DISCONNECTED;

    public RoomSession(LivePlatform platform, String input, LivePlayer player,
                       Supplier<AccountSession> account, Consumer<InteractionState> listener) {
        this.platform = platform; this.input = platform.normalizeRoomId(input);
        this.player = player; this.account = account; this.listener = listener;
    }
    public InteractionState interactionState() { return state; }
    public LiveRoom room() { return room; }
    public CompletableFuture<LiveRoom> play() {
        long ticket = playbackVersion.incrementAndGet();
        return submit(() -> {
            AccountSession credentials = account.get();
            LiveRoom resolved = platform.resolveRoom(input, credentials);
            checkOpen();
            if (!resolved.live()) throw new PlatformException("直播间尚未开播");
            var stream = platform.resolveStream(resolved, credentials);
            synchronized (this) {
                checkOpen();
                if (playbackVersion.get() != ticket) throw new CancellationException("播放请求已被替换");
                room = resolved;
            }
            player.play(stream.withAudioOnly(audioOnly));
            lastSource = stream; stopped = false;
            checkOpen();
            change(InteractionState.DISCONNECTED);
            return resolved;
        });
    }
    public CompletableFuture<Void> send(String text) {
        if (text == null || text.isBlank()) return CompletableFuture.failedFuture(new PlatformException("弹幕不能为空"));
        if (!sending.compareAndSet(false, true)) return CompletableFuture.failedFuture(new PlatformException("上一条弹幕正在发送"));
        AccountSession credentials = account.get();
        long ticket = interactionVersion.get();
        CompletableFuture<Void> future = submit(() -> {
            if (!credentials.loggedIn()) throw new PlatformException("请先扫码登录");
            LiveRoom target = room;
            if (target == null) throw new PlatformException("请先加载直播间");
            RoomInteraction connection = platform.newInteraction(target, credentials);
            try (connection) {
                synchronized (this) {
                    checkOpen();
                    if (interactionVersion.get() != ticket || account.get() != credentials)
                        throw new CancellationException("登录状态已变化");
                    interaction = connection;
                    change(InteractionState.CONNECTING);
                }
                connection.connect();
                checkOpen();
                // Logout/account replacement cancels an operation before the send.
                if (account.get() != credentials || interactionVersion.get() != ticket)
                    throw new PlatformException("登录状态已变化，请重试");
                change(InteractionState.CONNECTED);
                connection.sendDanmaku(text.trim());
                return null;
            } finally {
                interaction = null;
                change(InteractionState.DISCONNECTED);
            }
        });
        future.whenComplete((ignored, error) -> sending.set(false));
        return future;
    }
    public synchronized void disconnectInteraction() {
        interactionVersion.incrementAndGet();
        RoomInteraction current = interaction;
        if (current != null) current.close();
        change(InteractionState.DISCONNECTED);
    }
    public CompletableFuture<Void> stop() {
        playbackVersion.incrementAndGet();
        return submit(() -> { stopped = true; player.stop(); return null; });
    }
    public boolean audioOnly() { return audioOnly; }
    /** Prepare the alternate media before replacing playback; a failure preserves the current mode. */
    public CompletableFuture<Void> audioOnly(boolean value) {
        return submit(() -> {
            if (lastSource != null && !stopped) player.play(lastSource.withAudioOnly(value));
            audioOnly = value;
            return null;
        });
    }
    public CompletableFuture<Void> volume(int value) { return submit(() -> { player.volume(value); return null; }); }
    public CompletableFuture<Void> muted(boolean value) { return submit(() -> { player.muted(value); return null; }); }
    private void change(InteractionState next) {
        state = next;
        try { listener.accept(next); } catch (RuntimeException ignored) { /* Observers cannot break cleanup. */ }
    }
    private void checkOpen() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("直播间已关闭");
    }
    private <T> CompletableFuture<T> submit(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new CancellationException("直播间已关闭"));
            pending.add(result);
            Future<?> task = worker.submit(() -> {
                try { checkOpen(); result.complete(action.call()); }
                catch (Throwable e) { result.completeExceptionally(e); }
            });
            result.whenComplete((value, error) -> {
                pending.remove(result);
                if (result.isCancelled()) task.cancel(true);
            });
        }
        return result;
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        disconnectInteraction();
        pending.forEach(future -> future.cancel(true));
        worker.shutdownNow();
        // Native release can block; never do it on the UI event thread.
        Thread.ofVirtual().start(player::close);
    }
}
