package top.vrilhyc.applications.platform.bilibili;

import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.auth.QrLogin;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BiliQrLoginTest {
    @Test void qrStatesAndSuccessfulAccountVerification() throws Exception {
        AtomicInteger poll = new AtomicInteger();
        BiliApi api = new BiliApi(request -> {
            String path = request.uri().getPath();
            String body;
            Map<String,List<String>> headers = Map.of();
            if (path.endsWith("/poll")) {
                int code = new int[]{86101,86090,0}[poll.getAndIncrement()];
                body = "{\"code\":0,\"data\":{\"code\":" + code + "}}";
                if (code == 0) headers = Map.of("Set-Cookie",List.of("SESSDATA=test-session; Path=/; Secure","bili_jct=test-csrf; Path=/; Secure"));
            } else if (path.endsWith("/nav")) {
                assertTrue(request.headers().firstValue("Cookie").orElseThrow().contains("SESSDATA=test-session"));
                body = "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"test\"}}";
            } else body = "{\"code\":0,\"data\":{\"b_3\":\"test-buvid\"}}";
            return BilibiliPlatformTest.response(request,body,headers);
        });
        QrLogin login = new BiliQrLogin(api);
        var challenge = new QrLogin.Challenge(URI.create("https://example.com/qr"),"test",Instant.now().plusSeconds(60));
        assertEquals(QrLogin.Status.WAITING_SCAN,login.poll(challenge).status());
        assertEquals(QrLogin.Status.WAITING_CONFIRMATION,login.poll(challenge).status());
        var result = login.poll(challenge);
        assertEquals(QrLogin.Status.SUCCESS,result.status());
        assertEquals(42,result.session().userId());
        assertEquals("test-buvid",result.session().cookie("buvid3"));
        assertFalse(result.session().toString().contains("test-session"));
    }
    @Test void expiredQrDoesNotPoll() throws Exception {
        var login = new BiliQrLogin(new BiliApi(request -> { throw new AssertionError(); }));
        assertEquals(QrLogin.Status.EXPIRED,login.poll(new QrLogin.Challenge(URI.create("https://example.com"),
                "test",Instant.now().minusSeconds(1))).status());
    }
}
