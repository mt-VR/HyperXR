package dev.compatibility_layer.vrapidriver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** An empty provider whose URIs, granted to a game, make this driver visible to it. */
public final class VisibilityProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.compatibility_layer.vrapidriver.visibility";

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
}
