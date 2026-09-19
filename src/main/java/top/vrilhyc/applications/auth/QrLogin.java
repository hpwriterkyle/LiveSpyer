package top.vrilhyc.applications.auth;

import java.net.URI;
import java.time.Instant;

public interface QrLogin {
    record Challenge(URI imageContent, String key, Instant expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    enum Status { WAITING_SCAN, WAITING_CONFIRMATION, EXPIRED, SUCCESS }
    record Result(Status status, AccountSession session) {}
    Challenge generate() throws Exception;
    /** One poll only; caller controls interval, deadline and cancellation. */
    Result poll(Challenge challenge) throws Exception;
}
