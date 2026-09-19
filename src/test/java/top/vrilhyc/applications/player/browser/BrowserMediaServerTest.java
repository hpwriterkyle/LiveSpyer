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
    @Test void forwardsOnlyCurrentLocalViewCommandsWithoutChangingPlayback() throws Exception {
        var commands=new java.util.concurrent.CopyOnWriteArrayList<String>();
        try(var relay=new BrowserMediaServer(commands::add)) {
            var before=JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            long generation=before.get("generation").getAsLong();
            String origin="http://"+relay.page().getAuthority();
            for(String command:List.of("view:focus","view:fullscreen","view:escape","view:unknown")) {
                var response=client.send(HttpRequest.newBuilder(relay.page().resolve("event"))
                        .header("Origin",origin).POST(HttpRequest.BodyPublishers.ofString(
                                "{\"event\":\""+command+"\",\"generation\":"+generation+"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());
            }
            assertEquals(List.of("view:focus","view:fullscreen","view:escape"),commands);
            assertEquals(before,JsonParser.parseString(get(relay.page().resolve("state")).body()));
            relay.stop();
            client.send(HttpRequest.newBuilder(relay.page().resolve("event")).header("Origin",origin)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"view:focus\",\"generation\":"+generation+"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(3,commands.size());
        }
    }
    @Test void passesFullPublicNameGloryAndGiftAsPlainDisplayText() throws Exception {
        try(var relay=new BrowserMediaServer(ignored -> {})) {
            var item=new top.vrilhyc.applications.model.DanmakuMessage("gift-1","完整昵称<script>\n名字",
                    "赠送 小花花 × 3",java.time.Instant.now(),12,
                    new top.vrilhyc.applications.model.DanmakuMessage.Gift("小花花",3));
            relay.danmaku(item);
            var state=JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            var message=state.getAsJsonArray("danmaku").get(0).getAsJsonObject();
            assertEquals("[礼物] [荣耀 Lv.12] 完整昵称<script> 名字：赠送 小花花 × 3",message.get("displayText").getAsString());
            assertTrue(message.get("gift").getAsBoolean());
            // Seeking the video must retain the exact same names and messages.
            for(int i=0;i<3;i++) relay.goLive();
            var after=JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            assertEquals(state.get("danmaku"),after.get("danmaku"));
            assertEquals(state.get("generation"),after.get("generation"));
            assertEquals(3,after.get("liveRequest").getAsInt());
        }
    }
    @Test void boundsDanmakuQueueClearsOnDisableAndKeepsGoLiveIndependentOfSource() throws Exception {
        try(var relay=new BrowserMediaServer(ignored -> {})) {
            relay.play(new StreamSource(URI.create("https://example.com/live.m3u8"),Map.of()));
            for(int i=0;i<130;i++) relay.danmaku(new top.vrilhyc.applications.model.DanmakuMessage(""+i,"viewer","<script>"+i,java.time.Instant.now()));
            var state=JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            assertEquals(100,state.getAsJsonArray("danmaku").size());
            long generation=state.get("generation").getAsLong();
            relay.goLive(); relay.danmakuVisible(false);
            state=JsonParser.parseString(get(relay.page().resolve("state")).body()).getAsJsonObject();
            assertEquals(generation,state.get("generation").getAsLong());
            assertEquals(1,state.get("liveRequest").getAsLong());
            assertEquals(0,state.getAsJsonArray("danmaku").size());
        }
    }
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
