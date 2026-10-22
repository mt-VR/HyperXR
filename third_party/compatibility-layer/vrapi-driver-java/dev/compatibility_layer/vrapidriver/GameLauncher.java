package dev.compatibility_layer.vrapidriver;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Starts an unpatched VrApi game so that it can see this driver and Compatibility-Layer Runtime. A game made for
 * Android 11+ sees only packages it asked for, or that granted it a content URI: Compatibility-Layer Runtime
 * starts this activity with a URI of its own, and the game is started from here with that URI and
 * one of this package — both become visible to it.
 */
public final class GameLauncher extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String name = getIntent().getStringExtra("component");
        ComponentName component = name == null ? null : ComponentName.unflattenFromString(name);
        if (component != null) {
            Uri own = Uri.parse("content://" + VisibilityProvider.AUTHORITY + "/" + component.getPackageName());
            ClipData clip = ClipData.newRawUri("Compatibility-Layer", own);
            ClipData received = getIntent().getClipData();
            if (received != null) {
                for (int i = 0; i < received.getItemCount(); i++) {
                    Uri uri = received.getItemAt(i).getUri();
                    if (uri != null) clip.addItem(new ClipData.Item(uri));
                }
            }
            Intent game = new Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            game.setClipData(clip);
            try {
                startActivity(game);
            } catch (RuntimeException ignored) {
                // Not installed or not startable.
            }
        }
        finish();
    }
}
