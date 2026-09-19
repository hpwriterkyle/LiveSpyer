package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.JsonObject;
import top.vrilhyc.applications.auth.*;
import top.vrilhyc.applications.platform.PlatformException;
import java.net.HttpCookie;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;

final class BiliQrLogin implements QrLogin {
    private final BiliApi api;
    BiliQrLogin(BiliApi api) { this.api = api; }
    @Override public Challenge generate() throws Exception {
        JsonObject data = api.get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", AccountSession.guest());
        return new Challenge(URI.create(data.get("url").getAsString()), data.get("qrcode_key").getAsString(), Instant.now().plusSeconds(180));
    }
    @Override public Result poll(Challenge challenge) throws Exception {
        if (Instant.now().isAfter(challenge.expiresAt())) return new Result(Status.EXPIRED, null);
        var response = api.rawGet("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                + BiliApi.escape(challenge.key()), AccountSession.guest());
        JsonObject data = BiliApi.parse(response);
        int code = data.get("code").getAsInt();
        if (code == 86101) return new Result(Status.WAITING_SCAN, null);
        if (code == 86090) return new Result(Status.WAITING_CONFIRMATION, null);
        if (code == 86038) return new Result(Status.EXPIRED, null);
        if (code != 0) throw new PlatformException("扫码登录失败（" + code + "）");
        var cookies = new HashMap<String,String>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (!cookie.hasExpired()) cookies.put(cookie.getName(), cookie.getValue());
            }
        }
        if (!cookies.containsKey("SESSDATA") || !cookies.containsKey("bili_jct"))
            throw new PlatformException("登录响应未返回完整凭据，请刷新二维码重试");
        AccountSession candidate = new AccountSession(0, "", cookies);
        JsonObject nav = api.get("https://api.bilibili.com/x/web-interface/nav", candidate);
        if (!nav.get("isLogin").getAsBoolean()) throw new PlatformException("账号登录验证失败");
        // Obtain a real browser identifier from the platform instead of inventing one.
        JsonObject identity = api.get("https://api.bilibili.com/x/frontend/finger/spi", candidate);
        if (identity.has("b_3")) cookies.put("buvid3", identity.get("b_3").getAsString());
        if (identity.has("b_4")) cookies.put("buvid4", identity.get("b_4").getAsString());
        return new Result(Status.SUCCESS, new AccountSession(nav.get("mid").getAsLong(), nav.get("uname").getAsString(), cookies));
    }
}
