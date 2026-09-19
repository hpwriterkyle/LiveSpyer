package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.PlatformException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public final class BiliApi {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    @FunctionalInterface public interface Transport {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }
    private final Transport transport;
    public BiliApi() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        transport = request -> client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    public BiliApi(Transport transport) { this.transport = transport; }
    public JsonObject get(String url, AccountSession session) throws Exception { return data(request(url, session).GET().build()); }
    public JsonObject post(String url, AccountSession session, Map<String,String> form) throws Exception {
        return data(request(url, session).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))).build());
    }
    public HttpResponse<String> rawGet(String url, AccountSession session) throws Exception {
        return transport.send(request(url, session).GET().build());
    }
    private HttpRequest.Builder request(String url, AccountSession session) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                !(uri.getHost().equals("bilibili.com") || uri.getHost().endsWith(".bilibili.com")))
            throw new PlatformException("拒绝向非 Bilibili 接口发送账号信息");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT).header("Referer", "https://live.bilibili.com/")
                .header("Origin", "https://live.bilibili.com");
        if (!session.cookieHeader().isBlank()) builder.header("Cookie", session.cookieHeader());
        return builder;
    }
    private JsonObject data(HttpRequest request) throws Exception { return parse(transport.send(request)); }
    public static JsonObject parse(HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new PlatformException("Bilibili 网络请求失败（HTTP " + response.statusCode() + "）");
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            int code = root.get("code").getAsInt();
            if (code != 0) throw error(code);
            // Send API sometimes returns a nonempty message with code=0 for a blocked message.
            String message = root.has("message") ? root.get("message").getAsString() :
                    root.has("msg") ? root.get("msg").getAsString() : "";
            if (response.uri().getPath().endsWith("/msg/send") &&
                    !message.isBlank() && !message.equalsIgnoreCase("ok") && !message.equals("0"))
                throw new PlatformException("弹幕未被接受，请检查内容或稍后重试");
            return root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : new JsonObject();
        } catch (PlatformException e) { throw e; }
        catch (RuntimeException e) { throw new PlatformException("Bilibili 响应格式发生变化"); }
    }
    private static PlatformException error(int code) {
        return new PlatformException(switch(code) {
            case -101, -111 -> "登录已失效，请重新扫码";
            case -352, -412 -> "Bilibili 风控限制（" + code + "），请重新登录或稍后重试";
            case 19002003, 10030 -> "弹幕发送过快，请稍后重试";
            default -> "Bilibili 请求失败（错误码 " + code + "）";
        });
    }
    public static String encode(Map<String,String> values) {
        return values.entrySet().stream().map(e -> escape(e.getKey()) + "=" + escape(e.getValue())).collect(Collectors.joining("&"));
    }
    public static String escape(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
