package top.vrilhyc.applications.platform.bilibili;

import com.google.protobuf.CodedInputStream;
import top.vrilhyc.applications.model.DanmakuMessage;
import java.io.IOException;
import java.util.*;

/** Reads the needed SendGiftBroadcast fields and skips additions to the platform's schema. */
final class BiliGiftDecoder {
    static List<DanmakuMessage> decode(String encoded,int limit) throws IOException {
        if(encoded.length()>BiliPacket.MAX_SIZE*4/3+4) throw new IOException("礼物数据过大");
        byte[] bytes=Base64.getDecoder().decode(encoded);
        if(bytes.length>BiliPacket.MAX_SIZE) throw new IOException("礼物数据过大");
        var input=CodedInputStream.newInstance(bytes);
        String uid="0",sender="";
        var gifts=new ArrayList<byte[]>();
        for(int tag;(tag=input.readTag())!=0;) {
            switch(tag) {
                case 8 -> uid=Long.toString(input.readUInt64());
                case 18 -> sender=input.readStringRequireUtf8();
                case 82 -> { if(gifts.size()<limit) gifts.add(input.readByteArray()); else skip(input,tag); }
                default -> skip(input,tag);
            }
        }
        var result=new ArrayList<DanmakuMessage>();
        for(byte[] gift:gifts) {
            try { result.add(item(gift,uid,sender)); }
            catch(IOException | RuntimeException ignored) { /* Keep valid items in a partially malformed batch. */ }
        }
        return result;
    }

    private static DanmakuMessage item(byte[] bytes,String uid,String sender) throws IOException {
        var input=CodedInputStream.newInstance(bytes);
        String name="",transaction="",random="";
        long count=0,stamp=0;
        for(int tag;(tag=input.readTag())!=0;) {
            switch(tag) {
                case 18 -> name=input.readStringRequireUtf8();
                case 24 -> count=input.readUInt64();
                case 74 -> transaction=input.readStringRequireUtf8();
                case 80 -> stamp=input.readUInt64();
                case 98 -> random=input.readStringRequireUtf8();
                default -> skip(input,tag);
            }
        }
        // No verified glory-level field in this schema. Do not substitute guard or medal levels.
        return BiliDanmakuDecoder.giftMessage(uid,sender,name,count,stamp,transaction.isBlank()?random:transaction,null);
    }

    private static void skip(CodedInputStream input,int tag) throws IOException {
        if(!input.skipField(tag)) throw new IOException("礼物协议结束标记无效");
    }
}
