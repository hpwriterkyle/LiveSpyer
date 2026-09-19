package top.vrilhyc.applications.platform.bilibili;

import org.junit.jupiter.api.*;
import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.auth.QrLogin;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in read-only check; no login, room WebSocket, or message sending. */
@Tag("live")
class LiveSmokeTest {
    @Test void qrCanBeGeneratedAndPolledWithoutAnAccount() throws Exception {
        var login = new BilibiliPlatform().qrLogin();
        var challenge = login.generate();
        assertEquals("https",challenge.imageContent().getScheme());
        assertFalse(challenge.key().isBlank());
        assertEquals(QrLogin.Status.WAITING_SCAN,login.poll(challenge).status());
    }
    @Test void publicRoomProvidesAccessibleHls() throws Exception {
        var platform = new BilibiliPlatform();
        var room = platform.resolveRoom(System.getProperty("live.room","6"),AccountSession.guest());
        Assumptions.assumeTrue(room.live(),"直播间未开播");
        var source = platform.resolveStream(room,AccountSession.guest());
        var builder = HttpRequest.newBuilder(source.uri()).timeout(Duration.ofSeconds(15)).GET();
        source.headers().forEach(builder::header);
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        assertTrue(response.body().stripLeading().startsWith("#EXTM3U"),"Expected an HLS playlist");
    }
}
