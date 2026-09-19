package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.platform.PlatformException;
import java.net.*;
import java.net.http.*;
import java.util.*;
import javax.net.ssl.SSLSession;
import static org.junit.jupiter.api.Assertions.*;

class BilibiliPlatformTest {
    static HttpResponse<String> response(HttpRequest request, String body, Map<String,List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers,(a,b) -> true); }
            public String body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
    @Test void normalizeValidatesHostAndNumericId() {
        var platform = new BilibiliPlatform(new BiliApi(request -> { throw new AssertionError(); }));
        assertEquals("6",platform.normalizeRoomId(" https://live.bilibili.com/0006?foo=1 "));
        for (String bad : List.of("0","-1","abc","https://live.bilibili.com.evil.test/6","https://live.bilibili.com/6/7"))
            assertThrows(PlatformException.class,() -> platform.normalizeRoomId(bad));
    }
    @Test void hlsParserSkipsFlvAndPreservesSignedQuery() {
        var data = JsonParser.parseString("""
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_stream","format":[]},
                  {"protocol_name":"http_hls","format":[{"format_name":"ts","codec":[
                    {"codec_name":"hevc","base_url":"/wrong.m3u8","url_info":[]},
                    {"codec_name":"avc","base_url":"/live/list.m3u8","url_info":[
                      {"host":"https://media.example.com","extra":"?token=a%2Bb&expires=123"}]}]}]}
                ]}}}
                """).getAsJsonObject();
        var source = BilibiliPlatform.parseStream(data,"6");
        assertEquals("https://media.example.com/live/list.m3u8?token=a%2Bb&expires=123",source.uri().toString());
        assertEquals("https://live.bilibili.com/6",source.headers().get("Referer"));
        assertFalse(source.headers().containsKey("Cookie"));
        assertFalse(source.toString().contains("token"));
        assertThrows(PlatformException.class,() -> BilibiliPlatform.parseStream(JsonParser.parseString("{}").getAsJsonObject(),"6"));
    }
    @Test void cookieIsNeverForwardedOutsideBilibili() {
        var api = new BiliApi(request -> { throw new AssertionError("must not send"); });
        assertThrows(PlatformException.class,() -> api.get("https://bilibili.com.evil.test/",new AccountSession(1,"x",Map.of("SESSDATA","secret"))));
        assertThrows(PlatformException.class,() -> api.get("http://api.bilibili.com/",AccountSession.guest()));
    }
    @Test void prefersFmp4EvenWhenApiListsTsFirst() {
        var data = JsonParser.parseString("""
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_hls","format":[
                    {"format_name":"ts","codec":[{"codec_name":"avc","base_url":"/ts.m3u8",
                      "url_info":[{"host":"https://media.example.com","extra":"?token=ts"}]}]},
                    {"format_name":"fmp4","codec":[{"codec_name":"avc","base_url":"/fmp4.m3u8",
                      "url_info":[{"host":"https://media.example.com","extra":"?token=mp4%2B"}]}]}
                  ]}
                ]}}}
                """).getAsJsonObject();
        assertEquals("https://media.example.com/fmp4.m3u8?token=mp4%2B",
                BilibiliPlatform.parseStream(data,"6").uri().toString());
    }
    @Test void fallsBackToTsIfFmp4HasNoCompatibleCodec() {
        var data = JsonParser.parseString("""
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_hls","format":[
                    {"format_name":"fmp4","codec":[{"codec_name":"hevc","base_url":"/hevc.m3u8",
                      "url_info":[{"host":"https://media.example.com","extra":""}]}]},
                    {"format_name":"ts","codec":[{"codec_name":"avc","base_url":"/ts.m3u8",
                      "url_info":[{"host":"https://media.example.com","extra":"?token=ts"}]}]}
                  ]}
                ]}}}
                """).getAsJsonObject();
        assertEquals("https://media.example.com/ts.m3u8?token=ts",
                BilibiliPlatform.parseStream(data,"6").uri().toString());
    }
    @Test void errorsAreRedactedAndSoftSendFailureIsNotSuccess() {
        var request = HttpRequest.newBuilder(URI.create("https://api.live.bilibili.com/msg/send")).build();
        var blocked = response(request,"{\"code\":0,\"message\":\"blocked secret\",\"data\":{}}",Map.of());
        var exception = assertThrows(PlatformException.class,() -> BiliApi.parse(blocked));
        assertFalse(exception.getMessage().contains("secret"));
        var risk = response(request,"{\"code\":-352,\"message\":\"sensitive\"}",Map.of());
        assertTrue(assertThrows(PlatformException.class,() -> BiliApi.parse(risk)).getMessage().contains("-352"));
    }
    @Test void wbiMatchesFixedVector() throws Exception {
        String signed = WbiSigner.sign(Map.of("foo","114","bar","514","baz","1919810"),
                "7cd084941338484aae1ad9425b84077c","4932caff0ff746eab6f01bf08b70ac45",1702204169);
        // Independently checked with mixin key ea1db124af3c7062474693fa704f4ff8.
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee",signed);
    }
}
