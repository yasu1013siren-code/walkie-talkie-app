package jp.es.staffintercom;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Final text only. App-private, bounded, excluded from Android backup by manifest. */
final class ConversationStore extends SQLiteOpenHelper {
    ConversationStore(Context context) { super(context, "conversation.db", null, 1); }
    public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, speaker TEXT NOT NULL, body TEXT NOT NULL)"); }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    synchronized void add(String speaker, String body) {
        if (body == null || body.trim().isEmpty()) return;
        ContentValues v = new ContentValues(); v.put("at", System.currentTimeMillis());
        v.put("speaker", clean(speaker, 40)); v.put("body", clean(body, 2000));
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.insertOrThrow("entries", null, v); db.execSQL("DELETE FROM entries WHERE id NOT IN (SELECT id FROM entries ORDER BY id DESC LIMIT 5000)"); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    synchronized String text(int limit) {
        StringBuilder result = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at,speaker,body FROM (SELECT * FROM entries ORDER BY id DESC LIMIT ?) ORDER BY id", new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) result.append(fmt.format(new Date(c.getLong(0)))).append(" ").append(c.getString(1)).append("\n").append(c.getString(2)).append("\n\n");
        }
        return result.toString();
    }
    synchronized void clear() { getWritableDatabase().delete("entries", null, null); }
    static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
