package top.vrilhyc.applications.player.browser;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.model.StreamSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserMediaServerTest {
    @Test void forwardsLowLatencyReloadDirectivesWithoutChangingSignatureOrAllowingArbitraryParameters() {
        URI source = URI.create("https://cdn.example.com/live.m3u8?sign=a%2Bb&expires=123");
        assertEquals("https://cdn.example.com/live.m3u8?sign=a%2Bb&expires=123&_HLS_msn=42&_HLS_part=2&_HLS_skip=YES",
                BrowserMediaServer.deliveryDirectives(source,"_HLS_msn=42&_HLS_part=2&_HLS_skip=YES&url=https://other.example").toString());
    }
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpResponse<String> get(URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void relaysMediaAndRangeWithRefererButNoCookiesAndInvalidatesOldPlayback() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> range = new AtomicReference<>(), referer = new AtomicReference<>(), cookie = new AtomicReference<>();
        upstream.createContext("/", exchange -> {
            referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body;
            int status = 200;
            if (exchange.getRequestURI().getPath().equals("/index.m3u8")) {
                body = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nchunk.m4s?secret=a%2Bb\n".getBytes(StandardCharsets.UTF_8);
            } else {
                range.set(exchange.getRequestHeaders().getFirst("Range")); status = 206;
                exchange.getResponseHeaders().set("Content-Range","bytes 2-4/10");
                body = "234".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        upstream.start();
        try (var relay = new BrowserMediaServer(ignored -> {})) {
            URI media = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/index.m3u8");
            relay.play(new StreamSource(media,Map.of("Referer","https://live.bilibili.com/6","Cookie","must-not-leak")));
            var state = JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            URI manifest = relay.page().resolve(state.get("source").getAsString());
            String playlist = get(manifest).body();
            assertFalse(playlist.contains("secret"));
            String path = playlist.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).findFirst().orElseThrow();
            var response = client.send(HttpRequest.newBuilder(relay.page().resolve(path)).header("Range","bytes=2-4").build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(206,response.statusCode()); assertEquals("234",response.body());
            assertEquals("bytes=2-4",range.get()); assertEquals("https://live.bilibili.com/6",referer.get()); assertNull(cookie.get());
            relay.stop();
            assertEquals(410,get(manifest).statusCode());
            assertFalse(JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject().get("playing").getAsBoolean());
            assertEquals(404,get(relay.page().resolve("/state")).statusCode());
        } finally { upstream.stop(0); }
    }
    @Test void servesBundledPlayerWithoutNetworkAndRejectsForeignEvents() throws Exception {
        AtomicInteger events = new AtomicInteger();
        try (var relay = new BrowserMediaServer(event -> events.incrementAndGet())) {
            assertEquals(200,get(relay.page()).statusCode());
            var library = get(relay.page().resolve("hls.min.js"));
            assertEquals(200,library.statusCode()); assertTrue(library.body().length() > 10000);
            var response = client.send(HttpRequest.newBuilder(relay.page().resolve("event"))
                    .header("Origin","https://example.com").POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"error\",\"generation\":0}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode()); assertEquals(0,events.get());
        }
    }
}
