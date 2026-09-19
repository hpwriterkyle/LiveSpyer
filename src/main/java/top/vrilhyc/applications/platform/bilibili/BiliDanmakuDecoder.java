package top.vrilhyc.applications.platform.bilibili;

import com.google.gson.*;
import org.brotli.dec.BrotliInputStream;
import top.vrilhyc.applications.model.DanmakuMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.InflaterInputStream;

final class BiliDanmakuDecoder {
    static List<DanmakuMessage> decode(byte[] frame) throws IOException {
        var messages = new ArrayList<DanmakuMessage>();
        decode(frame,messages,0);
        return messages;
    }
    private static void decode(byte[] frame, List<DanmakuMessage> messages, int depth) throws IOException {
        if (depth > 3) throw new IOException("弹幕压缩嵌套过深");
        for (var packet : BiliPacket.decode(frame)) {
            if (packet.operation() != 5) continue;
            if (packet.version() == 2 || packet.version() == 3) {
                try (InputStream input = packet.version() == 2
                        ? new InflaterInputStream(new ByteArrayInputStream(packet.body()))
                        : new BrotliInputStream(new ByteArrayInputStream(packet.body()))) {
                    byte[] inflated = input.readNBytes(BiliPacket.MAX_SIZE+1);
                    if (inflated.length > BiliPacket.MAX_SIZE) throw new IOException("弹幕解压内容过大");
                    decode(inflated,messages,depth+1);
                }
            } else if (packet.version() == 0 || packet.version() == 1) {
                try {
                    var event = JsonParser.parseString(new String(packet.body(),StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!event.has("cmd") || messages.size()>=200) continue;
                    switch(event.get("cmd").getAsString().split(":",2)[0]) {
                        case "DANMU_MSG" -> messages.add(chat(event));
                        case "SEND_GIFT" -> messages.add(gift(event.getAsJsonObject("data")));
                        case "SEND_GIFT_V2" -> {
                            try {
                                messages.addAll(BiliGiftDecoder.decode(event.getAsJsonObject("data").get("pb").getAsString(),200-messages.size()));
                            } catch(IOException ignored) { /* A bad gift must not stop subsequent chat. */ }
                        }
                        // COMBO_SEND is a cumulative summary of SEND_GIFT, not another gift.
                        default -> { }
                    }
                } catch (RuntimeException ignored) { /* Ignore unrelated or malformed events, never render JSON as HTML. */ }
            }
        }
    }

    private static DanmakuMessage chat(JsonObject event) {
        var info=event.getAsJsonArray("info");
        var meta=info.get(0).getAsJsonArray();
        var user=info.get(2).getAsJsonArray();
        JsonElement details=path(at(meta,15),"user");
        String sender=publicName(details,user.get(1).getAsString());
        String text=bounded(info.get(1).getAsString(),1000);
        long stamp=meta.get(4).getAsLong();
        Integer level=level(path(details,"wealth","level"));
        if(level==null) level=level(at(at(info,16),0));
        String id="chat:"+user.get(0).getAsString()+":"+stamp+":"+(meta.size()>5 ? meta.get(5).toString() : "")+":"+text;
        // Guest redaction observed in real packets: uid=0 and a public nickname ending in stars.
        // Never associate these messages by uid=0 or by their first character to recover a cached name.
        boolean masked="0".equals(user.get(0).getAsString()) && sender.matches("[^*＊]+[*＊]+");
        return new DanmakuMessage(id,sender,text,time(stamp),level,null,masked);
    }

    private static DanmakuMessage gift(JsonObject data) {
        JsonElement user=path(data,"sender_uinfo");
        if(user.isJsonNull()) user=path(data,"uinfo");
        String sender=publicName(user,string(path(data,"uname")));
        String name=bounded(data.get("giftName").getAsString(),200);
        long count=data.get("num").getAsLong(), stamp=data.get("timestamp").getAsLong();
        String transaction=string(path(data,"tid"));
        if(transaction.isBlank()) transaction=string(path(data,"rnd"));
        return giftMessage(string(path(data,"uid")),sender,name,count,stamp,transaction,level(path(user,"wealth","level")));
    }

    static DanmakuMessage giftMessage(String uid,String sender,String name,long count,long stamp,String transaction,Integer level) {
        sender=bounded(sender.isBlank() ? "未知用户" : sender,200); name=bounded(name,200);
        if(count<=0 || count>1_000_000_000L) throw new IllegalArgumentException("礼物数量无效");
        String id="gift:"+uid+":"+(transaction.isBlank() ? stamp+":"+name+":"+count : bounded(transaction,200));
        return new DanmakuMessage(id,sender,"赠送 "+name+" × "+count,time(stamp),level,new DanmakuMessage.Gift(name,count));
    }

    private static String publicName(JsonElement user,String fallback) {
        // base.name is the current public display name; origin_info may contain a mystery user's original identity.
        String name=string(path(user,"base","name"));
        if(name.isBlank()) name=fallback;
        return bounded(name.isBlank() ? "未知用户" : name,200);
    }
    private static JsonElement path(JsonElement value,String... keys) {
        for(String key:keys) {
            if(value==null || !value.isJsonObject()) return JsonNull.INSTANCE;
            value=value.getAsJsonObject().get(key);
        }
        return value==null ? JsonNull.INSTANCE : value;
    }
    private static JsonElement at(JsonElement value,int index) {
        return value!=null && value.isJsonArray() && value.getAsJsonArray().size()>index ? value.getAsJsonArray().get(index) : JsonNull.INSTANCE;
    }
    private static String string(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }
    private static Integer level(JsonElement value) {
        try { int number=value.getAsInt(); return number>=0 && number<=1000 ? number : null; }
        catch(RuntimeException ignored) { return null; }
    }
    private static String bounded(String value,int max) {
        if(value.isBlank() || value.length()>max) throw new IllegalArgumentException("直播消息字段无效");
        return value;
    }
    private static Instant time(long stamp) {
        return stamp>100_000_000_000L ? Instant.ofEpochMilli(stamp) : Instant.ofEpochSecond(stamp);
    }
}
