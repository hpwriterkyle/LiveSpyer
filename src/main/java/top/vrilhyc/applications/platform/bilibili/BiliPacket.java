package top.vrilhyc.applications.platform.bilibili;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class BiliPacket {
    static final int MAX_SIZE = 1024 * 1024;
    record Packet(int operation, int version, byte[] body) {}
    static ByteBuffer encode(int operation, byte[] body) {
        return ByteBuffer.allocate(16 + body.length).putInt(16 + body.length).putShort((short)16)
                .putShort((short)1).putInt(operation).putInt(1).put(body).flip();
    }
    static List<Packet> decode(byte[] bytes) {
        if (bytes.length > MAX_SIZE) throw new IllegalArgumentException("协议包过大");
        var packets = new ArrayList<Packet>();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 16) throw new IllegalArgumentException("协议包头不完整");
            int start = buffer.position(), length = buffer.getInt();
            int header = Short.toUnsignedInt(buffer.getShort()), version = Short.toUnsignedInt(buffer.getShort());
            int operation = buffer.getInt();
            buffer.getInt();
            if (header < 16 || length < header || length > bytes.length - start)
                throw new IllegalArgumentException("协议包长度无效");
            buffer.position(start + header);
            byte[] body = new byte[length - header];
            buffer.get(body);
            packets.add(new Packet(operation, version, body));
        }
        return packets;
    }
}
