package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.PlatformException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

record BiliGuestEndpoint(URI uri, String token, AccountSession visitor) {
    @Override public String toString() { return "BiliGuestEndpoint[redacted]"; }
    static BiliGuestEndpoint fetch(BiliApi api, String roomId) throws Exception {
        var identity = api.get("https://api.bilibili.com/x/frontend/finger/spi",AccountSession.guest());
        var visitor = new AccountSession(0,"游客",Map.of("buvid3",identity.get("b_3").getAsString(),
                "buvid4",identity.get("b_4").getAsString()));
        var response = api.rawGet("https://api.bilibili.com/x/web-interface/nav",visitor);
        if (response.statusCode() != 200) throw new PlatformException("游客弹幕签名信息请求失败");
        var nav = JsonParser.parseString(response.body()).getAsJsonObject();
        int code = nav.get("code").getAsInt();
        // Anonymous nav intentionally returns -101 but still publishes the WBI image keys.
        if (code != 0 && code != -101) throw new PlatformException("游客弹幕签名信息不可用");
        var keys = nav.getAsJsonObject("data").getAsJsonObject("wbi_img");
        String query = WbiSigner.sign(Map.of("id",roomId,"type","0"),key(keys.get("img_url").getAsString()),
                key(keys.get("sub_url").getAsString()),Instant.now().getEpochSecond());
        var info = api.get("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + query,visitor);
        for (JsonElement entry : info.getAsJsonArray("host_list")) {
            var host = entry.getAsJsonObject();
            String name = host.get("host").getAsString();
            int port = host.get("wss_port").getAsInt();
            if (name.matches("[a-zA-Z0-9.-]+") && name.endsWith(".chat.bilibili.com") && port > 0 && port <= 65535)
                return new BiliGuestEndpoint(URI.create("wss://"+name+":"+port+"/sub"),info.get("token").getAsString(),visitor);
        }
        throw new PlatformException("没有可用的游客弹幕服务器");
    }
    private static String key(String address) {
        String path = URI.create(address).getPath();
        return path.substring(path.lastIndexOf('/')+1,path.lastIndexOf('.'));
    }
}
