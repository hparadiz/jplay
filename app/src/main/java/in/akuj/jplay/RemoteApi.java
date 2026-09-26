package in.akuj.jplay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * "Play this" over HTTP, from the one computer allowed to ask. A connection from any other
 * address is closed before a byte of it is read. NAS files and plain http(s) streams play as
 * given; a YouTube link goes to that same computer's yt-dlp streamer, which answers with a
 * video stream and an audio stream the player can seek.
 *
 *   POST /play    url=<link or smb:// uri> [&start=<seconds>] [&title=...] [&audio=<url>]
 *   GET  /status
 *   POST /pause  /resume  /toggle  /stop
 */
final class RemoteApi {
    static final int PORT = 8791, STREAMER_PORT = 8793;
    private static boolean running;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private RemoteApi(Context context) { this.context = context.getApplicationContext(); }

    static synchronized void start(Context context) {
        if (running) return;
        running = true;
        RemoteApi api = new RemoteApi(context);
        Thread thread = new Thread(api::listen, "remote-api");
        thread.setDaemon(true);
        thread.start();
    }
    /** The computer allowed to send commands: the in-app setting, else the build's .env default. Blank disables the API. */
    static String controller(Context context) {
        String saved = context.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("controller", null);
        return (saved != null ? saved : context.getString(R.string.default_controller)).trim();
    }
    static void setController(Context context, String address) {
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("controller", address.trim()).apply();
    }
    /** An IPv4 or IPv6 literal; the API compares the peer address textually, so hostnames are not accepted. */
    static boolean validController(String address) {
        String a = address.trim();
        return a.isEmpty() || a.matches("(\\d{1,3}\\.){3}\\d{1,3}") || (a.contains(":") && a.matches("[0-9A-Fa-f:.]+"));
    }

    private void listen() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            String allowed = controller(context);
            Log.i("JPlay", "remote api on port " + PORT + (allowed.isEmpty() ? ", disabled until a controller is set" : ", answering only " + allowed));
            while (true) {
                Socket client = server.accept();
                Thread thread = new Thread(() -> serve(client), "remote-api-request");
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException e) {
            Log.w("JPlay", "remote api stopped", e);
            synchronized (RemoteApi.class) { running = false; }
        }
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            String from = client.getInetAddress().getHostAddress();
            if (from.startsWith("::ffff:")) from = from.substring(7);
            String allowed = controller(context);
            if (allowed.isEmpty() || !from.equals(allowed)) return;
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String[] request = String.valueOf(readLine(in)).split(" ");
            if (request.length < 2) return;
            int length = 0;
            for (String header; (header = readLine(in)) != null && !header.isEmpty(); ) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("content-length"))
                    length = Math.max(0, Math.min(65536, Integer.parseInt(header.substring(colon + 1).trim())));
            }
            byte[] body = new byte[length];
            for (int read = 0, n; read < length && (n = in.read(body, read, length - read)) > 0; ) read += n;

            URI target = URI.create(request[1]);
            HashMap<String, String> params = new HashMap<>();
            form(target.getRawQuery(), params);
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) {
                JSONObject json = new JSONObject(text);
                for (Iterator<String> keys = json.keys(); keys.hasNext(); ) { String key = keys.next(); params.put(key, json.optString(key)); }
            } else {
                form(text, params);
            }

            int status = 200;
            JSONObject reply;
            try {
                reply = route(target.getPath(), params);
            } catch (IllegalArgumentException e) {
                status = 400;
                reply = new JSONObject().put("error", e.getMessage());
            } catch (Exception e) {
                status = 502;
                reply = new JSONObject().put("error", String.valueOf(e.getMessage()));
                Log.w("JPlay", "remote api request failed", e);
            }
            byte[] out = (reply.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            OutputStream o = client.getOutputStream();
            o.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : status == 400 ? " Bad Request" : " Bad Gateway")
                + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.write(out);
            o.flush();
        } catch (Exception e) {
            Log.w("JPlay", "remote api connection failed", e);
        }
    }

    private JSONObject route(String path, HashMap<String, String> params) throws Exception {
        switch (path) {
            case "/play": return play(params);
            case "/status": return onMain(this::status);
            case "/pause": case "/resume": case "/toggle": case "/stop":
                String action = path.substring(1);
                return onMain(() -> control(action));
            default: throw new IllegalArgumentException("unknown path " + path);
        }
    }

    private JSONObject play(HashMap<String, String> params) throws Exception {
        String url = clean(params.get("url"));
        if (url == null) throw new IllegalArgumentException("url is required");
        String audio = clean(params.get("audio")), title = clean(params.get("title"));
        long start = clean(params.get("start")) == null ? -1 : Math.max(0, Math.round(Double.parseDouble(params.get("start")) * 1000));
        Uri uri = Uri.parse(url);
        if (youtube(uri)) {
            JSONObject stream = resolve(url);
            uri = Uri.parse(stream.getString("video"));
            audio = clean(stream.optString("audio", null));
            if (title == null) title = clean(stream.optString("title", null));
        }
        if (!Profile.load(context).contains(uri) && !Streams.remote(uri))
            throw new IllegalArgumentException("not a file on the NAS or an http(s) stream: " + url);
        if (Streams.remote(uri)) Streams.register(uri, audio, title);
        if (title == null && !Streams.remote(uri)) title = LibraryStore.get(context).item(uri).title();
        final Uri playing = uri;
        final long startMs = start;
        wake();
        // Give the screensaver a moment to finish: an activity started behind it never resumes.
        main.postDelayed(() -> context.startActivity(new Intent(context, PlayerActivity.class)
            .putExtra("uri", playing.toString()).putExtra("startMs", startMs).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), 400);
        Log.i("JPlay", "remote api play " + (title != null ? title : playing));
        return new JSONObject().put("playing", title != null ? title : playing.toString());
    }

    /** "Play this" wakes the screen and ends a screensaver, as a remote's play button would. */
    @SuppressWarnings("deprecation")
    private void wake() {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) return;
        power.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "jplay:remote").acquire(3000);
    }

    private JSONObject status() throws Exception {
        PlaybackService service = PlaybackService.peek();
        JSONObject reply = new JSONObject();
        if (service == null || !service.hasMedia()) return reply.put("playing", false);
        return reply.put("playing", service.isPlaying()).put("title", service.title()).put("uri", String.valueOf(service.currentUri()))
            .put("positionMs", service.positionMs()).put("durationMs", service.durationMs()).put("status", service.status());
    }

    private JSONObject control(String action) throws Exception {
        PlaybackService service = PlaybackService.peek();
        if (service == null || !service.hasMedia()) return new JSONObject().put("playing", false);
        switch (action) {
            case "pause": service.pause(); break;
            case "resume": service.play(); break;
            case "toggle": service.togglePlayback(); break;
            default: PlaybackService.stop(context);
        }
        return new JSONObject().put("done", action);
    }

    /**
     * Asks the controller's streamer for a YouTube link. A plain socket rather than
     * HttpURLConnection, whose cleartext policy would refuse a LAN address the app trusts.
     */
    private JSONObject resolve(String link) throws Exception {
        String host = controller(context);
        if (host.isEmpty()) throw new IOException("no controller computer is set");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, STREAMER_PORT), 5000);
            socket.setSoTimeout(90000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /resolve?url=" + URLEncoder.encode(link, "UTF-8") + " HTTP/1.0\r\nHost: " + host + ":" + STREAMER_PORT
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String[] statusLine = String.valueOf(readLine(in)).split(" ");
            int code = statusLine.length > 1 ? Integer.parseInt(statusLine[1]) : 0;
            while (true) { String header = readLine(in); if (header == null || header.isEmpty()) break; }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) body.write(buffer, 0, n);
            JSONObject json = new JSONObject(body.size() == 0 ? "{}" : body.toString("UTF-8"));
            if (code != 200) throw new IOException("YouTube streamer: " + json.optString("error", "HTTP " + code));
            return json;
        }
    }

    private static boolean youtube(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com") || host.endsWith("youtube-nocookie.com");
    }

    private <T> T onMain(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        main.post(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static void form(String raw, HashMap<String, String> into) throws IOException {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            into.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"), URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value) ? null : value.trim();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return line.toString();
            if (c != '\r') line.append((char) c);
            if (line.length() > 16384) throw new IOException("header line too long");
        }
        return line.length() == 0 ? null : line.toString();
    }
}
