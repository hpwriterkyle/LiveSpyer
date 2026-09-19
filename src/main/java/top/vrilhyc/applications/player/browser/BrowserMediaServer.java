package top.vrilhyc.applications.player.browser;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.*;
import top.vrilhyc.applications.model.StreamSource;
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

    public BrowserMediaServer(Consumer<String> events) throws IOException {
        this.events = events;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(workers);
        server.start();
    }
    public URI page() { return URI.create(origin + prefix + "player.html"); }
    public synchronized void play(StreamSource source) {
        if (closed) return;
        if (!HlsPlaylist.isHttp(source.uri())) throw new IllegalArgumentException("Invalid media URL");
        generation++;
        media = new Media(source, prefix, generation);
    }
    public synchronized void stop() { generation++; media = null; }
    public void volume(int value) { volume = Math.clamp(value,0,100); }
    public void muted(boolean value) { muted = value; }

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
                send(exchange,200,"application/json","{}".getBytes(StandardCharsets.UTF_8)); return;
            }
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange,405,"text/plain",new byte[0]); return; }
            if (route.equals("state")) {
                String json;
                synchronized (this) {
                    Media current = media;
                    json = JSON.toJson(Map.of("generation",generation,"playing",current != null,
                            "source",current == null ? "" : current.root,"volume",volume / 100.0,"muted",muted));
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
                relay(exchange,current,deliveryDirectives(remote,exchange.getRequestURI().getRawQuery()));
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
                String rewritten = HlsPlaylist.rewrite(new String(data,StandardCharsets.UTF_8),response.uri(),current::register);
                send(exchange,200,"application/vnd.apple.mpegurl",rewritten.getBytes(StandardCharsets.UTF_8));
            } else {
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
        closed = true; media = null; generation++;
        server.stop(0); workers.shutdownNow(); client.shutdownNow();
    }
    private static final class Media {
        final StreamSource source;
        final String prefix, root;
        final LinkedHashMap<URI,String> paths = new LinkedHashMap<>(16,0.75f,true);
        final Map<String,URI> urls = new HashMap<>();
        Media(StreamSource source, String prefix, long generation) {
            this.source = source; this.prefix = prefix + "media/" + generation + "/";
            root = register(source.uri());
        }
        synchronized String register(URI uri) {
            String path = paths.get(uri);
            if (path != null) return path;
            path = prefix + UUID.randomUUID();
            paths.put(uri,path); urls.put(path,uri);
            if (paths.size() > 4096) {
                for (var iterator = paths.entrySet().iterator(); iterator.hasNext();) {
                    var entry = iterator.next();
                    if (entry.getKey().equals(source.uri())) continue;
                    urls.remove(entry.getValue()); iterator.remove(); break;
                }
            }
            return path;
        }
        synchronized URI find(String path) { return urls.get(path); }
    }
}
