package top.vrilhyc.applications.platform;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class PlatformRegistry {
    private final Map<String, LivePlatform> platforms = new LinkedHashMap<>();
    public void register(LivePlatform platform) {
        if (platforms.putIfAbsent(platform.id(), platform) != null)
            throw new IllegalArgumentException("平台 ID 重复：" + platform.id());
    }
    public void loadExtensions() { ServiceLoader.load(LivePlatform.class).forEach(this::register); }
    public Collection<LivePlatform> all() { return List.copyOf(platforms.values()); }
}
