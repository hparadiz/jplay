package in.akuj.jplay;

import android.net.Uri;
import java.util.HashMap;

/**
 * Streams handed to the player from outside the NAS: a URL, an optional separate audio URL
 * (YouTube serves video and audio apart), and a title. They play like any file but are never
 * catalogued, since the library only records files it can find again on the share.
 */
final class Streams {
    static final class Stream {
        final String audio, title;
        Stream(String audio, String title) { this.audio = audio; this.title = title; }
    }
    private static final HashMap<String, Stream> known = new HashMap<>();
    private Streams() {}

    static boolean remote(Uri uri) {
        String scheme = uri == null ? null : uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
    static synchronized void register(Uri uri, String audio, String title) {
        known.put(uri.toString(), new Stream(blank(audio), blank(title)));
    }
    static synchronized Stream get(Uri uri) {
        return uri == null ? null : known.get(uri.toString());
    }
    static String title(Uri uri, String fallback) {
        Stream stream = get(uri);
        return stream != null && stream.title != null ? stream.title : fallback;
    }
    private static String blank(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value) ? null : value.trim();
    }
}
