package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import top.vrilhyc.applications.model.*;
import top.vrilhyc.applications.platform.*;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class BiliDanmakuSubscription implements DanmakuSubscription {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final BiliApi api;
    private final LiveRoom room;
    private final Consumer<DanmakuMessage> messages;
    private final Consumer<String> status;
    private final AtomicBoolean started = new AtomicBoolean();
    private final GuestRefreshPolicy refresh = new GuestRefreshPolicy();
    private volatile boolean closed;
    private volatile Thread worker;
    private volatile Connection current;
    BiliDanmakuSubscription(BiliApi api, LiveRoom room, Consumer<DanmakuMessage> messages, Consumer<String> status) {
        this.api=api; this.room=room; this.messages=messages; this.status=status;
    }
    public synchronized void start() {
        if (closed || !started.compareAndSet(false,true)) return;
        worker=Thread.ofVirtual().start(this::run);
    }
    @Override public void autoRefresh(boolean enabled) { refresh.enabled(enabled); }
    private void run() {
        int failures=0;
        boolean refreshing=false;
        while(!closed) {
            Connection connection=new Connection(); current=connection;
            boolean plannedRefresh=false;
            try {
                publish(refreshing ? "游客弹幕：正在刷新会话 #"+refresh.refreshes()+"…"
                        : failures==0 ? "游客弹幕：正在连接…" : "游客弹幕：重连 "+failures+"/3");
                refreshing=false;
                var endpoint=BiliGuestEndpoint.fetch(api,room.roomId());
                if(closed) return;
                var opening=HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .header("User-Agent",BiliApi.USER_AGENT).header("Origin","https://live.bilibili.com")
                        .header("Cookie",endpoint.visitor().cookieHeader()).buildAsync(endpoint.uri(),connection);
                opening.thenAccept(ws -> { if(connection.ended || closed) ws.abort(); });
                var socket=opening.get(12,TimeUnit.SECONDS); connection.socket=socket;
                if(closed) return;
                JsonObject auth=new JsonObject();
                auth.addProperty("uid",0); auth.addProperty("roomid",Long.parseLong(room.roomId()));
                auth.addProperty("protover",2); auth.addProperty("platform","web"); auth.addProperty("type",2);
                auth.addProperty("buvid",endpoint.visitor().cookie("buvid3")); auth.addProperty("key",endpoint.token());
                socket.sendBinary(BiliPacket.encode(7,auth.toString().getBytes(StandardCharsets.UTF_8)),true).get(5,TimeUnit.SECONDS);
                connection.authenticated.get(10,TimeUnit.SECONDS);
                // Messages may arrive immediately after the acknowledgment, so reset observations in onBinary.
                publish("游客弹幕：已连接（账号仍 disconnected）");
                long heartbeatAt=System.nanoTime()+Duration.ofSeconds(25).toNanos();
                boolean pauseReported=false;
                while(!closed) {
                    connection.wakeup.tryAcquire(1,TimeUnit.SECONDS);
                    if(connection.finished.isDone()) { connection.finished.get(); break; }
                    long now=System.nanoTime();
                    if(refresh.shouldRefresh(now)) { plannedRefresh=true; break; }
                    if(refresh.paused() && !pauseReported) {
                        publish("游客弹幕：连续 3 次刷新未恢复全名，已暂停自动刷新（仍在接收）"); pauseReported=true;
                    } else if(!refresh.paused() && pauseReported) {
                        publish("游客弹幕：已连接（账号仍 disconnected）"); pauseReported=false;
                    }
                    if(now>=heartbeatAt) {
                        socket.sendBinary(BiliPacket.encode(2,new byte[0]),true).get(5,TimeUnit.SECONDS);
                        heartbeatAt=System.nanoTime()+Duration.ofSeconds(25).toNanos();
                    }
                }
                if(closed) return;
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch(Exception e) {
                if(closed) return;
            } finally { connection.close(); }
            if(plannedRefresh) { refreshing=true; continue; }
            if(failures>=3) { publish("游客弹幕：连接失败，请关闭后重新勾选"); return; }
            try { Thread.sleep(1000L << failures); failures++; }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void publish(String value) { if(!closed) status.accept(value); }
    public synchronized void close() {
        closed=true;
        Connection connection=current; if(connection!=null) connection.close();
        if(worker!=null) worker.interrupt();
    }
    private final class Connection implements WebSocket.Listener {
        volatile WebSocket socket;
        volatile boolean ended;
        final CompletableFuture<Void> authenticated=new CompletableFuture<>(), finished=new CompletableFuture<>();
        final Semaphore wakeup=new Semaphore(0);
        final AtomicBoolean redactionSignalled=new AtomicBoolean();
        final ByteArrayOutputStream frame=new ByteArrayOutputStream();
        public void onOpen(WebSocket socket) {
            this.socket=socket;
            if(closed || ended) socket.abort(); else socket.request(1);
        }
        public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last) {
            if(closed || ended || current!=this) { socket.abort(); return CompletableFuture.completedFuture(null); }
            try {
                if(frame.size()+data.remaining()>BiliPacket.MAX_SIZE) throw new IllegalArgumentException();
                byte[] part=new byte[data.remaining()]; data.get(part); frame.writeBytes(part);
                if(last) {
                    byte[] bytes=frame.toByteArray(); frame.reset();
                    for(var packet:BiliPacket.decode(bytes)) if(packet.operation()==8) {
                        int code=JsonParser.parseString(new String(packet.body(),StandardCharsets.UTF_8)).getAsJsonObject().get("code").getAsInt();
                        if(code==0) {
                            if(!authenticated.isDone() && !closed && !ended && current==this) refresh.connected(System.nanoTime());
                            authenticated.complete(null);
                        }
                        else throw new PlatformException("游客弹幕鉴权被拒绝");
                    }
                    for(var message:BiliDanmakuDecoder.decode(bytes)) if(!closed && !ended) {
                        refresh.received(message); messages.accept(message);
                        if(message.maskedSender() && redactionSignalled.compareAndSet(false,true)) wakeup.release();
                    }
                }
                if(!closed && !ended) socket.request(1);
            } catch(Exception e) { fail(); }
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onClose(WebSocket socket,int code,String reason) { fail(); return CompletableFuture.completedFuture(null); }
        public void onError(WebSocket socket,Throwable error) { fail(); }
        void fail() {
            authenticated.completeExceptionally(new PlatformException("游客弹幕连接中断"));
            finished.completeExceptionally(new PlatformException("游客弹幕连接中断"));
            close();
        }
        void close() {
            ended=true;
            authenticated.completeExceptionally(new CancellationException()); finished.complete(null);
            wakeup.release();
            if(socket!=null) socket.abort();
        }
    }
}
