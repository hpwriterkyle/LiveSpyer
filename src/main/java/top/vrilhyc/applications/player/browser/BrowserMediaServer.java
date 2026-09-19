package top.vrilhyc.applications.player.browser;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.*;
import top.vrilhyc.applications.model.StreamSource;
import top.vrilhyc.applications.model.DanmakuMessage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Loopback-only, token-scoped media relay. It forwards media, never account cookies or room pages. */
public final class BrowserMediaServer implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private final String origin;
    private final Consumer<String> events;
    private volatile Media media;
    private volatile long generation;
    private volatile int volume = 30;
    private volatile boolean muted;
    private volatile boolean closed;
    private boolean danmakuEnabled=true;
    private long danmakuSequence, liveRequest;
    private final ArrayDeque<Map<String,Object>> danmaku = new ArrayDeque<>();

    public BrowserMediaServer(Consumer<String> events) throws IOException {
        this.events = events;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(workers);
        server.start();
    }
    public URI page() { return URI.create(origin + prefix + "player.html"); }
    public void play(StreamSource source) {
        if (closed) return;
        long expectedGeneration = generation;
        if (!HlsPlaylist.isHttp(source.uri())) throw new IllegalArgumentException("Invalid media URL");
        AudioOnlyHls audio = null;
        if (source.audioOnly()) {
            try { audio = AudioOnlyHls.prepare(client, source); }
            catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new top.vrilhyc.applications.platform.PlatformException("该来源暂不支持仅音频流，或音轨读取失败；请恢复画面后重试");
            }
        }
        synchronized (this) {
            if (closed) { if (audio != null) audio.cancel(); return; }
            if (generation != expectedGeneration) {
                if (audio != null) audio.cancel();
                throw new CancellationException("播放请求已被替换");
            }
            cancelAudio();
            generation++;
            media = new Media(source, prefix, generation, audio);
        }
    }
    public synchronized void stop() { cancelAudio(); generation++; media = null; }
    private void cancelAudio() { if (media != null && media.audio != null) media.audio.cancel(); }
    public void volume(int value) { volume = Math.clamp(value,0,100); }
    public void muted(boolean value) { muted = value; }
    public synchronized void danmaku(DanmakuMessage message) {
        if(closed || !danmakuEnabled) return;
        danmaku.addLast(Map.of("sequence",++danmakuSequence,"sender",message.sender(),"text",message.text(),
                "displayText",message.displayText(),"gift",message.gift()!=null));
        while(danmaku.size()>100) danmaku.removeFirst();
    }
    public synchronized void danmakuVisible(boolean visible) { danmakuEnabled=visible; if(!visible) danmaku.clear(); }
    public synchronized void goLive() { liveRequest++; }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            if (closed || !exchange.getRequestURI().getPath().startsWith(prefix)
                    || !Objects.equals(exchange.getRequestHeaders().getFirst("Host"), URI.create(origin).getAuthority())) {
                send(exchange,404,"text/plain",new byte[0]); return;
            }
            String route = exchange.getRequestURI().getPath().substring(prefix.length());
            if (route.equals("event") && exchange.getRequestMethod().equals("POST")) {
                if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) { send(exchange,403,"text/plain",new byte[0]); return; }
                var event = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(2048),StandardCharsets.UTF_8)).getAsJsonObject();
                String name = event.get("event").getAsString();
                if (event.get("generation").getAsLong() == generation && media != null
                        && Set.of("playing","buffering","error","autoplay").contains(name)) events.accept(name);
                if(event.get("generation").getAsLong()==generation
                        && Set.of("view:focus","view:fullscreen","view:escape").contains(name)) events.accept(name);
                if (event.get("generation").getAsLong()==generation && media!=null && name.equals("metrics")) {
                    double distance=event.get("distance").getAsDouble();
                    if(Double.isFinite(distance) && distance>=0 && distance<3600)
                        events.accept("distance:"+String.format(Locale.ROOT,"%.1f",distance));
                }
                send(exchange,200,"application/json","{}".getBytes(StandardCharsets.UTF_8)); return;
            }
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange,405,"text/plain",new byte[0]); return; }
            if (route.equals("state")) {
                String json;
                synchronized (this) {
                    Media current = media;
                    json = JSON.toJson(Map.of("generation",generation,"playing",current != null,
                            "source",current == null ? "" : current.root,"volume",volume / 100.0,"muted",muted,
                            "danmakuEnabled",danmakuEnabled,"danmaku",List.copyOf(danmaku),"liveRequest",liveRequest,
                            "audioOnly",current != null && current.source.audioOnly()));
                }
                send(exchange,200,"application/json",json.getBytes(StandardCharsets.UTF_8));
            } else if (route.equals("player.html") || route.equals("player.js") || route.equals("hls.min.js")) {
                String resource = route.equals("hls.min.js")
                        ? "/META-INF/resources/webjars/hls.js/1.5.15/dist/hls.min.js"
                        : "/player/" + route;
                try (InputStream input = BrowserMediaServer.class.getResourceAsStream(resource)) {
                    if (input == null) { send(exchange,404,"text/plain",new byte[0]); return; }
                    exchange.getResponseHeaders().set("Content-Security-Policy",
                            "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; worker-src 'self' blob:; frame-ancestors 'none'");
                    send(exchange,200,route.endsWith(".html") ? "text/html; charset=utf-8" : "text/javascript; charset=utf-8",input.readAllBytes());
                }
            } else if (route.startsWith("media/")) {
                Media current = media;
                URI remote = current == null ? null : current.find(exchange.getRequestURI().getPath());
                if (remote == null) { send(exchange,410,"text/plain",new byte[0]); return; }
                relay(exchange,current,current.audio != null && !current.audio.separate() ? remote
                        : deliveryDirectives(remote,exchange.getRequestURI().getRawQuery()));
            } else send(exchange,404,"text/plain",new byte[0]);
        } catch (Exception ignored) {
            // Do not log exceptions containing signed media URLs; hls.js handles failed requests.
        }
    }
    static URI deliveryDirectives(URI remote, String query) {
        if (query == null || query.isBlank()) return remote;
        var allowed = new ArrayList<String>();
        for (String pair : query.split("&")) {
            String[] field = pair.split("=",2);
            if (field.length != 2) continue;
            if ((field[0].equals("_HLS_msn") || field[0].equals("_HLS_part")) && field[1].matches("[0-9]{1,20}")) allowed.add(pair);
            else if (field[0].equals("_HLS_skip") && (field[1].equals("YES") || field[1].equals("v2"))) allowed.add(pair);
        }
        if (allowed.isEmpty()) return remote;
        String address = remote.toString().split("#",2)[0];
        return URI.create(address + (remote.getRawQuery() == null ? "?" : "&") + String.join("&",allowed));
    }
    private void relay(HttpExchange exchange, Media current, URI remote) throws Exception {
        if (current.audio != null && !current.audio.separate()) {
            byte[] audio = current.audio.media(remote);
            if (audio != null) { send(exchange,200,"audio/mp4",audio); return; }
            if (!remote.equals(current.audio.root())) { send(exchange,410,"text/plain",new byte[0]); return; }
        }
        var builder = HttpRequest.newBuilder(remote).timeout(Duration.ofSeconds(20)).GET();
        for (String name : List.of("Referer","User-Agent")) {
            String value = current.source.headers().get(name);
            if (value != null && !value.isBlank()) builder.header(name,value);
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.matches("bytes=[0-9]*-[0-9]*")) builder.header("Range",range);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                send(exchange,response.statusCode() >= 400 ? response.statusCode() : 502,"text/plain",new byte[0]); return;
            }
            var content = new BufferedInputStream(input);
            content.mark(16);
            byte[] start = content.readNBytes(7);
            content.reset();
            String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            if (new String(start,StandardCharsets.US_ASCII).equals("#EXTM3U")) {
                byte[] data = content.readNBytes(2 * 1024 * 1024 + 1);
                if (data.length > 2 * 1024 * 1024) { send(exchange,502,"text/plain",new byte[0]); return; }
                String playlist = new String(data,StandardCharsets.UTF_8);
                String rewritten = current.audio == null ? HlsPlaylist.rewrite(playlist,response.uri(),current::register)
                        : current.audio.rewrite(playlist,response.uri(),current::register);
                send(exchange,200,"application/vnd.apple.mpegurl",rewritten.getBytes(StandardCharsets.UTF_8));
            } else {
                if (current.audio != null && !current.audio.separate()) {
                    send(exchange,415,"text/plain",new byte[0]); return;
                }
                exchange.getResponseHeaders().set("Content-Type",type);
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                response.headers().firstValue("Content-Range").ifPresent(value -> exchange.getResponseHeaders().set("Content-Range",value));
                response.headers().firstValue("Accept-Ranges").ifPresent(value -> exchange.getResponseHeaders().set("Accept-Ranges",value));
                exchange.sendResponseHeaders(response.statusCode(),0);
                content.transferTo(exchange.getResponseBody());
            }
        }
    }
    private static void send(HttpExchange exchange, int code, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",type);
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; cancelAudio(); media = null; generation++;
        server.stop(0); workers.shutdownNow(); client.shutdownNow();
    }
    private static final class Media {
        final StreamSource source;
        final AudioOnlyHls audio;
        final String prefix, root;
        final LinkedHashMap<URI,String> paths = new LinkedHashMap<>(16,0.75f,true);
        final Map<String,URI> urls = new HashMap<>();
        Media(StreamSource source, String prefix, long generation, AudioOnlyHls audio) {
            this.audio = audio;
            this.source = source; this.prefix = prefix + "media/" + generation + "/";
            root = register(audio == null ? source.uri() : audio.root());
        }
        synchronized String register(URI uri) {
            String path = paths.get(uri);
            if (path != null) return path;
            path = prefix + UUID.randomUUID();
            paths.put(uri,path); urls.put(path,uri);
            if (paths.size() > 4096) {
                for (var iterator = paths.entrySet().iterator(); iterator.hasNext();) {
                    var entry = iterator.next();
                    if (entry.getValue().equals(root)) continue;
                    urls.remove(entry.getValue()); iterator.remove(); break;
                }
            }
            return path;
        }
        synchronized URI find(String path) { return urls.get(path); }
    }
}
