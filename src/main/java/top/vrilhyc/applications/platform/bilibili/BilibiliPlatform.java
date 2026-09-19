package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import top.vrilhyc.applications.auth.*;
import top.vrilhyc.applications.model.*;
import top.vrilhyc.applications.platform.*;
import java.net.URI;
import java.util.Map;

public final class BilibiliPlatform implements LivePlatform {
    private final BiliApi api;
    public BilibiliPlatform() { this(new BiliApi()); }
    public BilibiliPlatform(BiliApi api) { this.api = api; }
    @Override public String id() { return "bilibili"; }
    @Override public String displayName() { return "哔哩哔哩"; }
    @Override public String toString() { return displayName(); }
    @Override public QrLogin qrLogin() { return new BiliQrLogin(api); }
    @Override public String normalizeRoomId(String input) {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("https://") || value.startsWith("http://")) {
            URI uri;
            try { uri = URI.create(value); } catch (RuntimeException e) { throw new PlatformException("直播间链接无效"); }
            if (!"live.bilibili.com".equalsIgnoreCase(uri.getHost())) throw new PlatformException("请输入 Bilibili 直播间链接");
            value = uri.getPath().replaceAll("^/|/$", "");
        }
        if (!value.matches("[0-9]{1,18}")) throw new PlatformException("请输入有效的数字房间号");
        long id = Long.parseLong(value);
        if (id <= 0) throw new PlatformException("房间号必须大于零");
        return Long.toString(id);
    }
    @Override public LiveRoom resolveRoom(String input, AccountSession session) throws Exception {
        String id = normalizeRoomId(input);
        JsonObject data = api.get("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + id, session);
        if (!data.has("room_id") || data.get("room_id").getAsLong() <= 0) throw new PlatformException("直播间不存在");
        String realId = data.get("room_id").getAsString();
        return new LiveRoom(id(), realId, "直播间 " + realId, data.get("live_status").getAsInt() == 1);
    }
    @Override public StreamSource resolveStream(LiveRoom room, AccountSession session) throws Exception {
        JsonObject data = api.get("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id="
                + room.roomId() + "&protocol=0,1&format=0,1,2&codec=0&qn=10000&platform=web&ptype=8", session);
        if (data.has("live_status") && data.get("live_status").getAsInt() != 1) throw new PlatformException("直播间尚未开播");
        return parseStream(data, room.roomId());
    }
    static StreamSource parseStream(JsonObject data, String roomId) {
        try {
            JsonArray streams = data.getAsJsonObject("playurl_info").getAsJsonObject("playurl").getAsJsonArray("stream");
            // Bilibili's fMP4 HLS commonly uses shorter segments than its TS HLS.
            // Prefer it for low-latency playback, retaining TS when fMP4/AVC is unavailable.
            for (String formatName : new String[]{"fmp4", "ts"}) {
                for (JsonElement s : streams) {
                    JsonObject stream = s.getAsJsonObject();
                    if (!"http_hls".equals(stream.get("protocol_name").getAsString())) continue;
                    for (JsonElement f : stream.getAsJsonArray("format")) {
                        JsonObject format = f.getAsJsonObject();
                        if (!formatName.equals(format.get("format_name").getAsString())) continue;
                        for (JsonElement c : format.getAsJsonArray("codec")) {
                            JsonObject codec = c.getAsJsonObject();
                            if (!"avc".equals(codec.get("codec_name").getAsString())) continue;
                            for (JsonElement u : codec.getAsJsonArray("url_info")) {
                                JsonObject info = u.getAsJsonObject();
                                URI uri = URI.create(info.get("host").getAsString() + codec.get("base_url").getAsString() + info.get("extra").getAsString());
                                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) continue;
                                return new StreamSource(uri, Map.of("Referer", "https://live.bilibili.com/" + roomId, "User-Agent", BiliApi.USER_AGENT));
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) { throw new PlatformException("直播流数据不完整，可能未开播或接口已变化"); }
        throw new PlatformException("该直播间暂未提供可播放的 H.264 HLS 流");
    }
    @Override public RoomInteraction newInteraction(LiveRoom room, AccountSession session) {
        return new BiliInteraction(api, room, session);
    }
}
