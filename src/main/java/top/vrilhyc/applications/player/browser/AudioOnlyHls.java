package top.vrilhyc.applications.player.browser;

import top.vrilhyc.applications.model.StreamSource;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Selects a separate HLS audio rendition, or remuxes AAC ranges out of unencrypted fMP4. */
final class AudioOnlyHls {
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
    private final HttpClient client;
    private final StreamSource source;
    private final LinkedHashMap<URI, Mp4Audio.Init> initializations = new LinkedHashMap<>();
    private final LinkedHashMap<URI, URI> segments = new LinkedHashMap<>();
    private URI root;
    private boolean separate;
    private volatile boolean cancelled;
    private record Playlist(URI uri, String text) {}

    private AudioOnlyHls(HttpClient client, StreamSource source) { this.client = client; this.source = source; }
    static AudioOnlyHls prepare(HttpClient client, StreamSource source) throws Exception {
        var audio = new AudioOnlyHls(client, source);
        Playlist playlist = audio.loadPlaylist(source.uri());
        for (int depth = 0; depth < 3; depth++) {
            URI rendition = audioRendition(playlist.text, playlist.uri);
            if (rendition != null) { audio.separate = true; playlist = audio.loadPlaylist(rendition); continue; }
            if (!playlist.text.contains("#EXT-X-STREAM-INF:")) break;
            if (audio.separate) throw unsupported();
            // A mixed master still permits audio extraction from its selected fMP4 media playlist.
            String[] lines = playlist.text.split("\\r?\\n"); URI variant = null;
            for (int i = 0; i + 1 < lines.length; i++) if (lines[i].startsWith("#EXT-X-STREAM-INF:")
                    && !lines[i + 1].isBlank() && !lines[i + 1].startsWith("#")) {
                variant = resolve(playlist.uri, lines[i + 1].trim()); break;
            }
            if (variant == null) throw unsupported();
            playlist = audio.loadPlaylist(variant);
        }
        if (playlist.text.contains("#EXT-X-STREAM-INF:")) throw unsupported();
        audio.root = playlist.uri;
        if (!audio.separate) {
            audio.rewrite(playlist.text, playlist.uri, URI::toString);
            URI first;
            synchronized (audio) {
                var available = new ArrayList<>(audio.segments.keySet());
                if (available.isEmpty()) throw unsupported();
                // Avoid both the oldest expiring segment and the newest possibly still-growing one.
                first = available.get(Math.max(0, available.size() - 2));
            }
            // Verify both range support and the actual audio fragment before changing playback.
            audio.media(first);
        }
        return audio;
    }
    URI root() { return root; }
    boolean separate() { return separate; }
    void cancel() { cancelled = true; }
    static URI audioRendition(String text, URI base) throws IOException {
        String[] lines = text.split("\\r?\\n");
        URI fallback = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#EXT-X-MEDIA:")) {
                var attrs = attributes(line);
                if ("AUDIO".equals(attrs.get("TYPE")) && attrs.containsKey("URI")) {
                    URI uri = resolve(base, attrs.get("URI"));
                    if ("YES".equals(attrs.get("DEFAULT"))) return uri;
                    if (fallback == null) fallback = uri;
                }
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                var attrs = attributes(line); String codec = attrs.getOrDefault("CODECS", "");
                if (codec.matches("mp4a\\.[0-9A-Fa-f.]+") && !attrs.containsKey("RESOLUTION")
                        && i + 1 < lines.length && !lines[i + 1].startsWith("#")) fallback = resolve(base, lines[i + 1].trim());
            }
        }
        return fallback;
    }
    synchronized String rewrite(String text, URI base, Function<URI, String> register) throws Exception {
        if (separate) return HlsPlaylist.rewrite(text, base, register);
        if (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-BYTERANGE")) throw unsupported();
        // Only completed segments are range-remuxed; growing low-latency parts are not fetched.
        text = text.lines().filter(line -> !line.startsWith("#EXT-X-PART:") && !line.startsWith("#EXT-X-PRELOAD-HINT:")
                && !line.startsWith("#EXT-X-SERVER-CONTROL:") && !line.startsWith("#EXT-X-RENDITION-REPORT:")
                && !line.startsWith("#EXT-X-PART-INF:")).collect(java.util.stream.Collectors.joining("\n"));
        URI init = null;
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("#EXT-X-KEY:") && !"NONE".equals(attributes(line).get("METHOD"))) throw unsupported();
            if (line.startsWith("#EXT-X-MAP:")) {
                var attrs = attributes(line);
                if (attrs.containsKey("BYTERANGE") || !attrs.containsKey("URI")) throw unsupported();
                init = resolve(base, attrs.get("URI"));
                if (!initializations.containsKey(init)) {
                    initializations.put(init, Mp4Audio.initialization(download(init, Mp4Audio.MAX_METADATA)));
                    while (initializations.size() > 8) initializations.remove(initializations.keySet().iterator().next());
                }
            } else if (!line.isBlank() && !line.startsWith("#")) {
                if (init == null) throw unsupported();
                segments.put(resolve(base, line.trim()), init);
                while (segments.size() > 512) segments.remove(segments.keySet().iterator().next());
            }
        }
        if (init == null) throw unsupported();
        return HlsPlaylist.rewrite(text, base, register);
    }
    byte[] media(URI uri) throws Exception {
        Mp4Audio.Init init;
        synchronized (this) {
            init = initializations.get(uri); if (init != null) return init.bytes();
            URI initUri = segments.get(uri);
            if (initUri == null) return null;
            init = initializations.get(initUri); if (init == null) throw unsupported();
        }
        return Mp4Audio.segment(new RemoteRanges(uri), init);
    }
    private Playlist loadPlaylist(URI uri) throws Exception {
        var response = client.send(request(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() != 200) throw unsupported();
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw unsupported();
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.startsWith("#EXTM3U")) throw unsupported();
            return new Playlist(response.uri(), text);
        }
    }
    private byte[] download(URI uri, int limit) throws Exception {
        var response = client.send(request(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() != 200) throw unsupported();
            byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw unsupported(); return bytes;
        }
    }
    private HttpRequest.Builder request(URI uri) throws IOException {
        if (!HlsPlaylist.isHttp(uri)) throw unsupported();
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        for (String name : List.of("Referer", "User-Agent")) {
            String value = source.headers().get(name); if (value != null && !value.isBlank()) builder.header(name, value);
        }
        return builder;
    }
    private final class RemoteRanges implements Mp4Audio.Ranges {
        private final URI uri;
        private long length = -1;
        private final byte[] first;
        private final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        private int requests;
        RemoteRanges(URI uri) throws Exception { this.uri = uri; first = read(0, 8); }
        public long length() { return length; }
        public byte[] read(long offset, int count) throws Exception {
            if (offset == 0 && count == 8 && first != null) return first;
            if (count < 0 || count > Mp4Audio.MAX_AUDIO || offset < 0 || ++requests > 512
                    || cancelled || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw unsupported();
            if (count == 0) return new byte[0];
            long end = offset + count - 1;
            var response = client.send(request(uri).header("Range", "bytes=" + offset + "-" + end).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                // Reject a server ignoring Range before reading any potentially full video response.
                if (response.statusCode() != 206) throw unsupported();
                var match = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                        .matcher(response.headers().firstValue("Content-Range").orElse(""));
                if (!match.matches() || Long.parseLong(match.group(1)) != offset || Long.parseLong(match.group(2)) != end) throw unsupported();
                long total = Long.parseLong(match.group(3));
                if (total <= end || (length >= 0 && length != total)) throw unsupported();
                length = total;
                byte[] data = input.readNBytes(count + 1); if (data.length != count) throw unsupported(); return data;
            }
        }
    }
    private static Map<String, String> attributes(String line) {
        var result = new HashMap<String, String>(); var matcher = ATTRIBUTE.matcher(line.substring(line.indexOf(':') + 1));
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        return result;
    }
    private static URI resolve(URI base, String reference) throws IOException {
        URI uri = base.resolve(reference); if (!HlsPlaylist.isHttp(uri)) throw unsupported(); return uri;
    }
    private static IOException unsupported() { return new IOException("该来源暂不支持仅音频流，请恢复画面或粘贴独立音频 HLS"); }
}
