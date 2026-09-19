package top.vrilhyc.applications.platform;
import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.auth.AccountSession;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class DirectStreamPlatformTest {
    @Test void directSourcePreservesSignatureWithoutForwardingAccountCredentials() {
        String address = "https://cdn.example.com/live.m3u8?sign=a%2Bb&deadline=123";
        var direct = new DirectStreamPlatform(address,"https://live.bilibili.com/");
        var account = new AccountSession(12,"test",Map.of("SESSDATA","secret"));
        var source = direct.resolveStream(direct.resolveRoom(address,account),account);
        assertEquals(address,source.uri().toString()); assertFalse(source.headers().containsKey("Cookie"));
        assertThrows(PlatformException.class,() -> direct.newInteraction(direct.resolveRoom(address,account),account));
    }
    @Test void rejectsNonHttpOrEmbeddedCredentials() {
        for (String invalid : new String[]{"file:///c:/test.m3u8","javascript:alert(1)","https://user:password@example.com/live.m3u8"})
            assertThrows(PlatformException.class,() -> new DirectStreamPlatform(invalid,""));
    }
}
