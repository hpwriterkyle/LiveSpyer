package top.vrilhyc.applications.player.browser;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HlsPlaylistTest {
    @Test void rewritesNestedPlaylistsMapsKeysAndSegmentsWithoutDroppingSignatures() {
        List<URI> urls = new ArrayList<>();
        String playlist = """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:42
                #EXT-X-KEY:METHOD=AES-128,URI="../key?k=a%2Bb"
                #EXT-X-MAP:URI="init.mp4?token=x%2Fy",BYTERANGE="100@0"
                #EXTINF:1.0,
                segment.m4s?sig=z%2Fq&n=3
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                high/list.m3u8?quality=high
                """;
        String rewritten = HlsPlaylist.rewrite(playlist,URI.create("https://cdn.example.com/live/index.m3u8?master=secret"),
                uri -> { urls.add(uri); return "/media/" + urls.size(); });
        assertEquals(List.of(
                URI.create("https://cdn.example.com/key?k=a%2Bb"),
                URI.create("https://cdn.example.com/live/init.mp4?token=x%2Fy"),
                URI.create("https://cdn.example.com/live/segment.m4s?sig=z%2Fq&n=3"),
                URI.create("https://cdn.example.com/live/high/list.m3u8?quality=high")),urls);
        assertTrue(rewritten.contains("BYTERANGE=\"100@0\""));
        assertTrue(rewritten.contains("#EXT-X-MEDIA-SEQUENCE:42"));
        assertFalse(rewritten.contains("secret"));
    }
    @Test void refusesNonHttpReferences() {
        assertThrows(IllegalArgumentException.class,() -> HlsPlaylist.rewrite("#EXTM3U\nfile:///etc/passwd",URI.create("https://example.com"),URI::toString));
    }
}
