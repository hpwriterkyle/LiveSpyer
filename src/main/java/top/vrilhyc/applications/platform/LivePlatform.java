package top.vrilhyc.applications.platform;

import top.vrilhyc.applications.auth.AccountSession;
import top.vrilhyc.applications.auth.QrLogin;
import top.vrilhyc.applications.model.LiveRoom;
import top.vrilhyc.applications.model.StreamSource;

public interface LivePlatform {
    String id();
    String displayName();
    String normalizeRoomId(String input);
    QrLogin qrLogin();
    LiveRoom resolveRoom(String input, AccountSession session) throws Exception;
    /** Resolve media without opening a room presence connection. */
    StreamSource resolveStream(LiveRoom room, AccountSession session) throws Exception;
    /** A fresh disconnected interaction connection for this operation. */
    RoomInteraction newInteraction(LiveRoom room, AccountSession session);
}
