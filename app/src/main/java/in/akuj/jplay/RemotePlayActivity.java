package in.akuj.jplay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Starts playback of one NAS file on request from a trusted host. The manifest guards this
 * activity with android.permission.DUMP, which the adb shell holds and no installed app can,
 * so only a machine this device has authorised for adb can reach it. The file must still sit
 * inside the configured NAS root, exactly as if it had been chosen from the library.
 */
public final class RemotePlayActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String target = getIntent().getStringExtra("uri");
            Profile profile = Profile.load(this);
            Uri uri = target == null ? null : Uri.parse(target);
            if (uri != null && (profile.contains(uri) || Streams.remote(uri))) {
                if (Streams.remote(uri)) Streams.register(uri, getIntent().getStringExtra("audio"), getIntent().getStringExtra("title"));
                Log.i("JPlay", "remote play " + uri);
                startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra("uri", uri.toString())
                    .putExtra("startMs", getIntent().getLongExtra("startMs", -1))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Log.w("JPlay", "remote play refused: " + target);
            }
        } catch (Exception e) {
            Log.w("JPlay", "remote play failed", e);
        }
        finish();
    }
}
