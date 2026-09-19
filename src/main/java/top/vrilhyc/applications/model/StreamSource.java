package top.vrilhyc.applications.model;

import java.net.URI;
import java.util.Map;

/** Signed media URL with media-only headers. Account cookies never go to the player. */
public record StreamSource(URI uri, Map<String, String> headers) {
    public StreamSource { headers = Map.copyOf(headers); }
    @Override public String toString() { return "StreamSource[signed URL redacted]"; }
}
