package top.vrilhyc.applications.platform.bilibili;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BiliPacketTest {
    @Test void parsesConcatenatedAuthAcknowledgmentAndHeartbeat() {
        ByteBuffer auth = BiliPacket.encode(8,"{\"code\":0}".getBytes(StandardCharsets.UTF_8));
        ByteBuffer heartbeat = BiliPacket.encode(3,new byte[4]);
        var bytes = ByteBuffer.allocate(auth.remaining()+heartbeat.remaining()).put(auth).put(heartbeat).array();
        var packets = BiliPacket.decode(bytes);
        assertEquals(2,packets.size()); assertEquals(8,packets.getFirst().operation());
        assertEquals("{\"code\":0}",new String(packets.getFirst().body(),StandardCharsets.UTF_8));
    }
    @Test void rejectsTruncatedOrInvalidFrames() {
        assertThrows(IllegalArgumentException.class,() -> BiliPacket.decode(new byte[15]));
        assertThrows(IllegalArgumentException.class,() -> BiliPacket.decode(new byte[16]));
        byte[] packet = BiliPacket.encode(8,new byte[0]).array();
        ByteBuffer.wrap(packet).putInt(1024);
        assertThrows(IllegalArgumentException.class,() -> BiliPacket.decode(packet));
    }
}
