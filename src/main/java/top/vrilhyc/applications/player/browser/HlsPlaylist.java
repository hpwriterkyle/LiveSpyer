package top.vrilhyc.applications.player.browser;

import java.net.URI;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Rewrites URI references without touching timing, sequence numbers, ranges or signatures. */
public final class HlsPlaylist {
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?<![A-Z0-9-])URI=\"([^\"]*)\"");
    public static String rewrite(String playlist, URI base, Function<URI,String> register) {
        StringBuilder result = new StringBuilder();
        for (String line : playlist.split("\\r?\\n", -1)) {
            if (line.startsWith("#")) {
                var matcher = URI_ATTRIBUTE.matcher(line);
                line = matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                        "URI=\"" + register.apply(resolve(base, match.group(1))) + "\""));
            } else if (!line.isBlank()) {
                line = register.apply(resolve(base, line.trim()));
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }
    private static URI resolve(URI base, String reference) {
        URI uri = base.resolve(reference);
        if (!isHttp(uri)) throw new IllegalArgumentException("Only HTTP media references are supported");
        return uri;
    }
    public static boolean isHttp(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null;
    }
}
