package top.vrilhyc.applications.auth;

import java.util.Map;
import java.util.stream.Collectors;

/** In-memory credentials, independent of room presence. */
public final class AccountSession {
    private final Map<String, String> cookies;
    private final long userId;
    private final String displayName;
    public AccountSession(long userId, String displayName, Map<String, String> cookies) {
        this.userId = userId;
        this.displayName = displayName;
        this.cookies = Map.copyOf(cookies);
    }
    public static AccountSession guest() { return new AccountSession(0, "未登录", Map.of()); }
    public boolean loggedIn() { return userId > 0; }
    public long userId() { return userId; }
    public String displayName() { return displayName; }
    public String cookie(String name) { return cookies.getOrDefault(name, ""); }
    public String cookieHeader() {
        return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
    }
    @Override public String toString() { return "AccountSession[credentials redacted]"; }
}
