package top.vrilhyc.applications.platform;

import top.vrilhyc.applications.auth.*;
import top.vrilhyc.applications.model.*;
import top.vrilhyc.applications.player.browser.HlsPlaylist;
import java.net.URI;
import java.util.Map;

/** A user-supplied media URL; no platform login, room presence, or account cookies. */
public final class DirectStreamPlatform implements LivePlatform {
    private final Map<String,String> headers;
    private final StreamSource stream;
    public DirectStreamPlatform(String address, String referer) {
        if (referer == null || referer.isBlank()) headers = Map.of("User-Agent","Mozilla/5.0");
        else {
            if (!HlsPlaylist.isHttp(URI.create(referer.trim()))) throw new PlatformException("Referer 必须是 HTTP 或 HTTPS 地址");
            headers = Map.of("User-Agent","Mozilla/5.0","Referer",referer.trim());
        }
        stream = new StreamSource(URI.create(normalizeRoomId(address)),headers);
    }
    public String id() { return "direct"; }
    public String displayName() { return "m3u8 直链"; }
    public String normalizeRoomId(String input) {
        try {
            URI uri = URI.create(input.trim());
            if (!HlsPlaylist.isHttp(uri)) throw new IllegalArgumentException();
            return uri.toString();
        } catch (RuntimeException e) { throw new PlatformException("请输入完整的 HTTP/HTTPS m3u8 地址"); }
    }
    public QrLogin qrLogin() { throw new PlatformException("直链播放无需登录"); }
    public LiveRoom resolveRoom(String input, AccountSession account) { return new LiveRoom(id(),"direct","m3u8 直链",true); }
    public StreamSource resolveStream(LiveRoom room, AccountSession account) { return stream; }
    public RoomInteraction newInteraction(LiveRoom room, AccountSession account) { throw new PlatformException("直链未绑定房间号，无法发送弹幕"); }
}
