package in.akuj.jplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Profile {
    String host = "", share = "", user = "", password = "", domain = "", folder = "";
    int bufferMs = 3000;

    Uri root() { return new Uri.Builder().scheme("smb").encodedAuthority(host).appendPath(share).appendPath("").build(); }
    Uri start() {
        Uri.Builder b = root().buildUpon();
        for (String part : folder.split("/")) if (!part.isEmpty()) b.appendPath(part);
        return b.build();
    }
    boolean contains(Uri uri) {
        return "smb".equals(uri.getScheme()) && host.equalsIgnoreCase(uri.getEncodedAuthority())
            && uri.getPathSegments().size() >= 1 && share.equals(uri.getPathSegments().get(0))
            && !uri.getPathSegments().contains("..") && !uri.getPathSegments().contains(".");
    }
    private JSONObject json() throws Exception {
        return new JSONObject().put("host", host).put("share", share).put("user", user)
            .put("password", password).put("domain", domain).put("folder", folder).put("bufferMs", bufferMs);
    }
    private static Profile parse(byte[] bytes) throws Exception {
        JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Profile p = new Profile();
        p.host = o.optString("host"); p.share = o.optString("share"); p.user = o.optString("user");
        p.password = o.optString("password"); p.domain = o.optString("domain"); p.folder = o.optString("folder");
        p.bufferMs = Math.max(1000, Math.min(15000, o.optInt("bufferMs", 3000)));
        return p;
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("jplay.nas")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("jplay.nas", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey("jplay.nas", null);
    }
    void save(Context context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(json().toString().getBytes(StandardCharsets.UTF_8));
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "nas.enc"));
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(cipher.getIV()); out.write(encrypted); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
        CrashReports.get(context).rememberSecrets(this);
        CrashReports.get(context).uploadNow();
    }
    static Profile load(Context context) throws Exception {
        File provision = new File(context.getFilesDir(), "provision.json");
        // Private ADB provisioning exists only in debug builds. Release ignores it.
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 && provision.isFile()) {
            try { Profile p = parse(Files.readAllBytes(provision.toPath())); p.save(context); return p; }
            finally { Files.deleteIfExists(provision.toPath()); }
        }
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "nas.enc"));
        if (!file.getBaseFile().isFile()) return new Profile();
        byte[] data = file.readFully();
        if (data.length < 28) throw new IllegalStateException("Invalid saved profile");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 0, 12)));
        return parse(cipher.doFinal(Arrays.copyOfRange(data, 12, data.length)));
    }
}
