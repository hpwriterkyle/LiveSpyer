package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;
import top.vrilhyc.applications.model.LiveRoom;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class BiliDanmakuTest {
    static byte[] packet(int operation,int version,byte[] body) {
        return BiliPacket.encode(operation,body).putShort(6,(short)version).array();
    }
    static byte[] zlib(byte[] bytes) throws IOException {
        var output=new ByteArrayOutputStream();
        try(var compressor=new DeflaterOutputStream(output)) { compressor.write(bytes); }
        return output.toByteArray();
    }
    @Test void decodesPlainAndZlibDanmakuAndIgnoresOtherEvents() throws Exception {
        byte[] body="""
                {"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,16777215,1700000000,123],"<script>弹幕</script>",[42,"访客"]]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] danmaku=packet(5,1,body);
        byte[] acknowledgment=packet(8,1,"{\"code\":0}".getBytes(StandardCharsets.UTF_8));
        byte[] combined=ByteBuffer.allocate(acknowledgment.length+danmaku.length).put(acknowledgment).put(danmaku).array();
        var plain=BiliDanmakuDecoder.decode(combined);
        var compressed=BiliDanmakuDecoder.decode(packet(5,2,zlib(combined)));
        assertEquals(plain,compressed); assertEquals(1,plain.size());
        assertEquals("<script>弹幕</script>",plain.getFirst().text());
        assertEquals("访客",plain.getFirst().sender());
        assertEquals(1700000000,plain.getFirst().time().getEpochSecond());
    }
    @Test void decodesBrotliFixtureGeneratedOutsideTheJavaDecoder() throws Exception {
        byte[] fixture=Base64.getDecoder().decode("G2sAAESJbQ3rhMgtY954TaJJXEqklgLZAHZQIQBQAAhfTvczpixk1UpnWGmVqXN9WByZdru27uhuoDthFEWuE+hOZCPdcb2+zsnleNuttcX6dbtf5tS7vqvzsZ4/5xf2+38=");
        var messages=BiliDanmakuDecoder.decode(packet(5,3,fixture));
        assertEquals("brotli fixture",messages.getFirst().text());
    }
    private static byte[] event(String json) {
        return packet(5,1,json.getBytes(StandardCharsets.UTF_8));
    }
    @Test void preservesServerRedactionAndDoesNotGuessNamesForZeroUid() throws Exception {
        // Sanitized shape observed in room 1965473206 without clicking go-live.
        var packet=JsonParser.parseString("""
            {"cmd":"DANMU_MSG","info":[[0,1,25,16777215,1700000000,123],"消息",[0,"小***"]]}
            """).getAsJsonObject();
        var meta=packet.getAsJsonArray("info").get(0).getAsJsonArray();
        while(meta.size()<15) meta.add(JsonNull.INSTANCE);
        meta.add(JsonParser.parseString("""
            {"user":{"uid":0,"base":{"is_mystery":false,"name":"小***","origin_info":{"name":"小***"}},"wealth":{"level":24}}}
            """));
        var item=BiliDanmakuDecoder.decode(event(packet.toString())).getFirst();
        assertEquals("小***",item.sender()); assertTrue(item.maskedSender()); assertEquals(24,item.gloryLevel());
        var details=meta.get(15).getAsJsonObject().getAsJsonObject("user");
        details.getAsJsonObject("base").addProperty("name","完整公开昵称");
        item=BiliDanmakuDecoder.decode(event(packet.toString())).getFirst();
        assertEquals("完整公开昵称",item.sender()); assertFalse(item.maskedSender());
        details.getAsJsonObject("base").addProperty("name","小***");
        packet.getAsJsonArray("info").get(2).getAsJsonArray().set(0,new JsonPrimitive(42));
        assertFalse(BiliDanmakuDecoder.decode(event(packet.toString())).getFirst().maskedSender());
    }
    @Test void showsPublicNameAndGloryWithoutConfusingUserOrMedalLevels() throws Exception {
        var chat=JsonParser.parseString("""
            {"cmd":"DANMU_MSG","info":[[0,1,25,16777215,1700000000,123],"你好",[42,"基础昵称"],[38,"粉丝牌"],[60],[],0,3,null,{},0,0,null,null,0,13,[17]]}
            """).getAsJsonObject();
        var meta=chat.getAsJsonArray("info").get(0).getAsJsonArray();
        while(meta.size()<15) meta.add(JsonNull.INSTANCE);
        meta.add(JsonParser.parseString("""
            {"user":{"base":{"name":"完整的公开昵称","origin_info":{"name":"原始名称"}},"wealth":null}}
            """));
        var item=BiliDanmakuDecoder.decode(event(chat.toString())).getFirst();
        assertEquals("完整的公开昵称",item.sender()); assertEquals(17,item.gloryLevel());
        assertEquals("[荣耀 Lv.17] 完整的公开昵称：你好",item.displayText());
        var user=meta.get(15).getAsJsonObject().getAsJsonObject("user");
        user.add("wealth",JsonParser.parseString("{\"level\":23}"));
        assertEquals(23,BiliDanmakuDecoder.decode(event(chat.toString())).getFirst().gloryLevel());
        user.getAsJsonObject("base").addProperty("name","神秘人");
        user.getAsJsonObject("base").addProperty("is_mystery",true);
        item=BiliDanmakuDecoder.decode(event(chat.toString())).getFirst();
        assertEquals("神秘人",item.sender()); assertFalse(item.displayText().contains("原始名称"));
        meta.set(15,JsonNull.INSTANCE); chat.getAsJsonArray("info").set(16,new JsonArray());
        item=BiliDanmakuDecoder.decode(event(chat.toString())).getFirst();
        assertEquals("基础昵称",item.sender()); assertNull(item.gloryLevel());
        assertFalse(item.displayText().contains("荣耀"));
    }
    @Test void decodesGiftQuantityAndIgnoresCumulativeComboSummary() throws Exception {
        String json="""
            {"cmd":"SEND_GIFT","data":{"uid":42,"uname":"旧昵称","giftName":"小花花","num":3,"timestamp":1700000000,"tid":"transaction-1","sender_uinfo":{"base":{"name":"礼物用户"},"wealth":{"level":12}}}}
            """;
        var items=BiliDanmakuDecoder.decode(event(json));
        assertEquals(1,items.size()); var item=items.getFirst();
        assertEquals("小花花",item.gift().name()); assertEquals(3,item.gift().quantity());
        assertEquals("[礼物] [荣耀 Lv.12] 礼物用户：赠送 小花花 × 3",item.displayText());
        assertTrue(BiliDanmakuDecoder.decode(event(json.replace("SEND_GIFT","COMBO_SEND"))).isEmpty());
        assertTrue(BiliDanmakuDecoder.decode(event(json.replace("\"num\":3","\"num\":-1"))).isEmpty());
    }
    private static byte[] giftProtoItem(String name,long count,String transaction) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=CodedOutputStream.newInstance(bytes);
        out.writeString(2,name); out.writeUInt64(3,count); out.writeString(9,transaction);
        out.writeUInt64(10,1700000000); out.writeString(100,"unknown future field"); out.flush();
        return bytes.toByteArray();
    }
    private static byte[] giftV2(byte[]... items) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=CodedOutputStream.newInstance(bytes);
        out.writeUInt64(1,42);out.writeString(2,"新版礼物用户"); out.writeUInt64(5,3);
        for(byte[] item:items) out.writeByteArray(10,item);
        out.flush();return bytes.toByteArray();
    }
    private static byte[] giftV2Event(byte[] bytes) {
        return event("{\"cmd\":\"SEND_GIFT_V2\",\"data\":{\"pb\":\""+Base64.getEncoder().encodeToString(bytes)+"\"}}");
    }
    @Test void decodesV2GiftBatchAndSharesLegacyTransactionIdentity() throws Exception {
        var items=BiliDanmakuDecoder.decode(giftV2Event(giftV2(giftProtoItem("小花花",3,"transaction-1"),
                giftProtoItem("点赞",5,"transaction-2"))));
        assertEquals(2,items.size());
        assertEquals("新版礼物用户",items.getFirst().sender());assertEquals(3,items.getFirst().gift().quantity());
        assertNull(items.getFirst().gloryLevel()); // Guard level 3 is not glory level 3.
        var old=BiliDanmakuDecoder.decode(event("""
            {"cmd":"SEND_GIFT","data":{"uid":42,"uname":"旧昵称","giftName":"小花花","num":3,"timestamp":1700000000,"tid":"transaction-1"}}
            """)).getFirst();
        assertEquals(old.id(),items.getFirst().id());
    }
    @Test void boundsV2BatchesAndSurvivesMalformedGifts() throws Exception {
        byte[] valid=giftProtoItem("小花花",1,"unique");
        byte[][] many=new byte[250][];Arrays.fill(many,valid);
        assertEquals(200,BiliDanmakuDecoder.decode(giftV2Event(giftV2(many))).size());
        assertEquals(1,BiliDanmakuDecoder.decode(giftV2Event(giftV2(new byte[]{18,127},valid))).size());
        byte[] malformed=giftV2Event(new byte[]{18,127});
        byte[] correct=giftV2Event(giftV2(valid));
        byte[] combined=ByteBuffer.allocate(malformed.length+correct.length).put(malformed).put(correct).array();
        assertEquals(1,BiliDanmakuDecoder.decode(combined).size());
        assertTrue(BiliDanmakuDecoder.decode(event("{\"cmd\":\"SEND_GIFT_V2\",\"data\":{\"pb\":\"%%%\"}}")).isEmpty());
    }
    @Test void boundsDecompressedSizeAndNesting() throws Exception {
        byte[] bomb=packet(5,2,zlib(new byte[BiliPacket.MAX_SIZE+1]));
        assertThrows(IOException.class,() -> BiliDanmakuDecoder.decode(bomb));
        byte[] nested=packet(8,1,"{}".getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<5;i++) nested=packet(5,2,zlib(nested));
        byte[] frame=nested;
        assertThrows(IOException.class,() -> BiliDanmakuDecoder.decode(frame));
    }
    @Test void anonymousEndpointUsesOnlyDeviceCookiesAndAcceptsGuestNav() throws Exception {
        var api=new BiliApi(request -> {
            String cookie=request.headers().firstValue("Cookie").orElse("");
            assertFalse(cookie.contains("SESSDATA")); assertFalse(cookie.contains("bili_jct"));
            String path=request.uri().getPath();
            String body;
            if(path.endsWith("/spi")) body="{\"code\":0,\"data\":{\"b_3\":\"device3\",\"b_4\":\"device4\"}}";
            else if(path.endsWith("/nav")) body="""
                {"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
                """;
            else {
                assertTrue(request.uri().getRawQuery().contains("w_rid="));
                assertTrue(cookie.contains("buvid3=device3"));
                body="{\"code\":0,\"data\":{\"token\":\"test\",\"host_list\":[{\"host\":\"broadcastlv.chat.bilibili.com\",\"wss_port\":443}]}}";
            }
            return BilibiliPlatformTest.response(request,body,Map.of());
        });
        var endpoint=BiliGuestEndpoint.fetch(api,"6");
        assertEquals(0,endpoint.visitor().userId()); assertFalse(endpoint.visitor().loggedIn());
        assertFalse(endpoint.toString().contains("test"));
    }
    @Test void closingSubscriptionInterruptsPendingLookupAndDoesNotRestart() throws Exception {
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        AtomicInteger requests=new AtomicInteger();
        var api=new BiliApi(request -> {
            requests.incrementAndGet(); started.countDown();
            try { new CountDownLatch(1).await(); }
            catch(InterruptedException e) { interrupted.countDown(); throw e; }
            throw new AssertionError();
        });
        var subscription=new BiliDanmakuSubscription(api,new LiveRoom("bilibili","6","room",true),ignored -> {},ignored -> {});
        subscription.start(); assertTrue(started.await(2,TimeUnit.SECONDS));
        subscription.close(); assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        subscription.start(); assertEquals(1,requests.get());
    }
}
