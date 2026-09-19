package top.vrilhyc.applications.platform;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.auth.QrLogin;
import top.vrilhyc.applications.model.LiveRoom;
import top.vrilhyc.applications.model.StreamSource;
import top.vrilhyc.applications.model.DanmakuMessage;
import top.vrilhyc.applications.model.RoomDetails;
import top.vrilhyc.applications.model.OnlineViewers;
import java.util.function.Consumer;

public interface LivePlatform {
    String id();
    String displayName();
    String normalizeRoomId(String input);
    QrLogin qrLogin();
    LiveRoom resolveRoom(String input, AccountSession session) throws Exception;
    /** Resolve media without opening a room presence connection. */
    StreamSource resolveStream(LiveRoom room, AccountSession session) throws Exception;
    default boolean supportsRoomDetails() { return false; }
    /** These read-only queries never accept account credentials. */
    default RoomDetails roomDetails(String input) throws Exception { throw new PlatformException("该来源不支持直播间信息"); }
    default OnlineViewers onlineViewers(RoomDetails room) throws Exception { throw new PlatformException("该来源不支持在线列表"); }
    /** A fresh disconnected interaction connection for this operation. */
    RoomInteraction newInteraction(LiveRoom room, AccountSession session);
    /** No account argument: a display subscription must never borrow login credentials. */
    default DanmakuSubscription subscribeDanmaku(LiveRoom room, Consumer<DanmakuMessage> messages, Consumer<String> status) {
        throw new PlatformException("该来源不支持游客弹幕");
    }
}
