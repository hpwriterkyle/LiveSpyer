package top.vrilhyc.applications.model;

import java.net.URI;
import java.util.Map;

/** Signed media URL with media-only headers. Account cookies never go to the player. */
public record StreamSource(URI uri, Map<String, String> headers, boolean audioOnly) {
    public StreamSource(URI uri, Map<String, String> headers) { this(uri, headers, false); }
    public StreamSource withAudioOnly(boolean value) { return new StreamSource(uri, headers, value); }
    public StreamSource { headers = Map.copyOf(headers); }
    @Override public String toString() { return "StreamSource[signed URL redacted]"; }
}
