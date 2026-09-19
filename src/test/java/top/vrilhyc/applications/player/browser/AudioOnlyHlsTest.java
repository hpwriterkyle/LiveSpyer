package top.vrilhyc.applications.player.browser;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import top.vrilhyc.applications.model.StreamSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioOnlyHlsTest {
    @Test void selectsSeparateAudioWithoutResolvingVideoAndPreservesSignatures() throws Exception {
        String text = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"sound/list.m3u8?token=a%2Bb\",DEFAULT=YES\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"a\"\nvideo.m3u8\n";
        assertEquals("https://cdn.example/sound/list.m3u8?token=a%2Bb",AudioOnlyHls.audioRendition(text,URI.create("https://cdn.example/root.m3u8")).toString());
        assertNull(AudioOnlyHls.audioRendition("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10\nvideo.m3u8",URI.create("https://cdn.example/root.m3u8")));
    }
    @Test void endToEndRemuxUsesRangesAndFailedPreparationPreservesExistingPlayback() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] init = Mp4AudioTest.init(), segment = Mp4AudioTest.fragment(false,0,6000);
        var ignoreRange = new AtomicBoolean(); var ranges = new AtomicInteger(); var cookie = new AtomicBoolean();
        var partialRequested = new AtomicBoolean();
        upstream.createContext("/",e -> { try(e) {
            if (e.getRequestHeaders().containsKey("Cookie")) cookie.set(true);
            String path = e.getRequestURI().getPath(); byte[] body;
            if (path.contains("partial")) partialRequested.set(true);
            if (path.equals("/root.m3u8")) body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10000,CODECS=\"avc1.640028,mp4a.40.2\"\nlist.m3u8\n".getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/list.m3u8")) body = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:1\n#EXT-X-MAP:URI=\"init.mp4?key=a%2Bb\"\n#EXTINF:1,\nseg.m4s?key=a%2Bb\n#EXT-X-PART:DURATION=0.3,URI=\"partial.m4s\"\n".getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/init.mp4")) body = init;
            else body = segment;
            int status = 200; String range = e.getRequestHeaders().getFirst("Range");
            if (range != null && !ignoreRange.get()) {
                ranges.incrementAndGet(); String[] limits = range.substring(6).split("-"); int from = Integer.parseInt(limits[0]),to = Integer.parseInt(limits[1]);
                int video = java.nio.ByteBuffer.wrap(segment).getInt() + 8;
                assertFalse(from < video+6000 && to+1 > video);
                e.getResponseHeaders().set("Content-Range","bytes "+from+"-"+to+"/"+body.length);
                body = Arrays.copyOfRange(body,from,to+1); status=206;
            }
            e.sendResponseHeaders(status,body.length); e.getResponseBody().write(body);
        }}); upstream.start();
        try(var client = HttpClient.newHttpClient(); var server = new BrowserMediaServer(ignored -> {})) {
            URI uri = URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/root.m3u8");
            var source = new StreamSource(uri,Map.of("Cookie","must-not-leak"),true);
            var audio = AudioOnlyHls.prepare(client,source);
            byte[] result = audio.media(uri.resolve("seg.m4s?key=a%2Bb"));
            assertTrue(result.length < segment.length/10); assertTrue(ranges.get()>=4); assertFalse(cookie.get());
            assertFalse(partialRequested.get()); assertEquals("/list.m3u8",audio.root().getPath());
            server.play(source.withAudioOnly(false));
            String before = client.send(HttpRequest.newBuilder(server.page().resolve("state")).build(),HttpResponse.BodyHandlers.ofString()).body();
            ignoreRange.set(true);
            assertThrows(top.vrilhyc.applications.platform.PlatformException.class,()->server.play(source));
            assertEquals(before,client.send(HttpRequest.newBuilder(server.page().resolve("state")).build(),HttpResponse.BodyHandlers.ofString()).body());
        } finally { upstream.stop(0); }
    }
}
