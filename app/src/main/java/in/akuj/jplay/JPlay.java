package in.akuj.jplay;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;

public final class JPlay extends Application {
    private LibVLC vlc;
    private ConnectivityManager connectivity;
    private volatile Network local;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.post(() -> { bindLocalNetwork(); changed(); }); }
            @Override public void onLost(Network network) {
                main.post(() -> {
                    if (network.equals(local)) local = null;
                    // Keep the process bound to the lost network until another local network
                    // is available: never fall back to a cellular default route.
                    bindLocalNetwork(); changed();
                });
            }
        };
        // Wi-Fi on a phone, Ethernet on a TV box; both are LAN routes to the NAS.
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback);
        bindLocalNetwork();
        RemoteApi.start(this);
        CrashReports.get(this).start();
    }
    /** Binds the process to a LAN transport, preferring Ethernet, and never to cellular. */
    public synchronized boolean bindLocalNetwork() {
        for (int transport : new int[]{NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI}) {
            for (Network n : connectivity.getAllNetworks()) {
                NetworkCapabilities c = connectivity.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(transport)) {
                    if (connectivity.bindProcessToNetwork(n)) { local = n; return true; }
                }
            }
        }
        local = null;
        return false;
    }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
        CrashReports.get(this).uploadNow();
    }
    public LibVLC vlc() {
        if (vlc == null) vlc = new LibVLC(this, new ArrayList<>(Arrays.asList(
            "--network-caching=3000", "--file-caching=3000", "--audio-time-stretch",
            "--no-video-title-show", "--android-display-chroma=RV32", "--quiet")));
        return vlc;
    }
    public boolean softwareDecoder(android.net.Uri uri) {
        android.content.SharedPreferences options=getSharedPreferences("player-options",MODE_PRIVATE);
        return options.getBoolean(LibraryStore.key(uri)+".software",options.getBoolean("software",false));
    }
    public void configureDecoder(Media media,android.net.Uri uri) {
        media.setHWDecoderEnabled(!softwareDecoder(uri),false);
    }
    public Media media(Profile profile, android.net.Uri uri) {
        Media m = new Media(vlc(), uri);
        m.addOption(":smb-user=" + profile.user);
        m.addOption(":smb-pwd=" + profile.password);
        m.addOption(":smb-domain=" + profile.domain);
        m.addOption(":network-caching=" + profile.bufferMs);
        return m;
    }
}
