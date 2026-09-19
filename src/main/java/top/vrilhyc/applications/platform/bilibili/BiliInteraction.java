package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.model.LiveRoom;
import top.vrilhyc.applications.platform.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class BiliInteraction implements RoomInteraction {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ConcurrentHashMap<Long, AtomicLong> SEND_AFTER = new ConcurrentHashMap<>();
    private final BiliApi api;
    private final LiveRoom room;
    private final AccountSession session;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> authenticated = new CompletableFuture<>();
    private volatile WebSocket socket;
    private volatile CompletableFuture<WebSocket> opening;

    BiliInteraction(BiliApi api, LiveRoom room, AccountSession session) {
        this.api = api; this.room = room; this.session = session;
    }
    @Override public void connect() throws Exception {
        if (!session.loggedIn()) throw new PlatformException("请先扫码登录");
        checkOpen();
        JsonObject nav = api.get("https://api.bilibili.com/x/web-interface/nav", session);
        JsonObject keys = nav.getAsJsonObject("wbi_img");
        String query = WbiSigner.sign(Map.of("id", room.roomId(), "type", "0"),
                key(keys.get("img_url").getAsString()), key(keys.get("sub_url").getAsString()), Instant.now().getEpochSecond());
        JsonObject info = api.get("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + query, session);
        JsonArray hosts = info.getAsJsonArray("host_list");
        if (hosts == null || hosts.isEmpty()) throw new PlatformException("未获取到弹幕服务器");
        JsonObject host = hosts.get(0).getAsJsonObject();
        String hostname = host.get("host").getAsString();
        if (!hostname.matches("[a-zA-Z0-9.-]+") || !(hostname.endsWith(".chat.bilibili.com") || hostname.endsWith(".bilibili.com")))
            throw new PlatformException("弹幕服务器地址无效");
        checkOpen();
        opening = CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .header("User-Agent", BiliApi.USER_AGENT).header("Origin", "https://live.bilibili.com")
                .header("Cookie", session.cookieHeader())
                .buildAsync(URI.create("wss://" + hostname + ":" + host.get("wss_port").getAsInt() + "/sub"), new Listener());
        // Handles a late handshake completing after cancellation/close.
        opening.thenAccept(ws -> { if (closed.get()) ws.abort(); });
        try {
            socket = opening.get(12, TimeUnit.SECONDS);
            checkOpen();
            JsonObject auth = new JsonObject();
            auth.addProperty("uid", session.userId());
            auth.addProperty("roomid", Long.parseLong(room.roomId()));
            auth.addProperty("protover", 2);
            auth.addProperty("platform", "web");
            auth.addProperty("type", 2);
            auth.addProperty("buvid", session.cookie("buvid3"));
            auth.addProperty("key", info.get("token").getAsString());
            socket.sendBinary(BiliPacket.encode(7, auth.toString().getBytes(StandardCharsets.UTF_8)), true).get(5, TimeUnit.SECONDS);
            authenticated.get(10, TimeUnit.SECONDS);
            checkOpen();
        } catch (Exception e) { close(); throw e; }
    }
    private static String key(String url) {
        String name = URI.create(url).getPath();
        return name.substring(name.lastIndexOf('/') + 1, name.lastIndexOf('.'));
    }
    private void checkOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("连接已取消");
    }
    @Override public void sendDanmaku(String text) throws Exception {
        checkOpen();
        if (!authenticated.isDone() || authenticated.isCompletedExceptionally() || socket == null || socket.isInputClosed())
            throw new PlatformException("弹幕连接尚未就绪");
        if (session.cookie("bili_jct").isBlank()) throw new PlatformException("登录凭据不完整，请重新扫码");
        int length = text.codePointCount(0, text.length());
        if (text.isBlank() || length > 20) throw new PlatformException("请输入 1–20 个字符的弹幕");
        AtomicLong gate = SEND_AFTER.computeIfAbsent(session.userId(), ignored -> new AtomicLong());
        long now = System.nanoTime(), prior = gate.get();
        if ((prior != 0 && now < prior) || !gate.compareAndSet(prior, now + TimeUnit.SECONDS.toNanos(3)))
            throw new PlatformException("弹幕发送过快，请等待 3 秒");
        api.post("https://api.live.bilibili.com/msg/send", session, Map.of(
                "roomid", room.roomId(), "msg", text, "color", "16777215", "fontsize", "25",
                "mode", "1", "bubble", "0", "rnd", Long.toString(Instant.now().getEpochSecond()),
                "csrf", session.cookie("bili_jct"), "csrf_token", session.cookie("bili_jct")));
    }
    @Override public void close() {
        closed.set(true);
        authenticated.completeExceptionally(new CancellationException("互动连接已断开"));
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        // No reconnect, presence heartbeat, or browser page survives this operation.
    }
    private final class Listener implements WebSocket.Listener {
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        @Override public void onOpen(WebSocket ws) {
            socket = ws;
            if (closed.get()) ws.abort(); else ws.request(1);
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            try {
                if (frame.size() + data.remaining() > BiliPacket.MAX_SIZE) throw new IllegalArgumentException();
                byte[] part = new byte[data.remaining()];
                data.get(part); frame.writeBytes(part);
                if (last) {
                    for (var packet : BiliPacket.decode(frame.toByteArray())) {
                        if (packet.operation() == 8) {
                            JsonObject reply = JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                            if (reply.get("code").getAsInt() == 0) authenticated.complete(null);
                            else authenticated.completeExceptionally(new PlatformException("弹幕连接鉴权失败，请重新登录"));
                        }
                    }
                    frame.reset();
                }
                ws.request(1);
            } catch (RuntimeException e) {
                authenticated.completeExceptionally(new PlatformException("弹幕协议响应无效"));
                close();
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.set(true);
            authenticated.completeExceptionally(new PlatformException("弹幕服务器已关闭连接"));
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            authenticated.completeExceptionally(new PlatformException("弹幕连接失败"));
            close();
        }
    }
}
