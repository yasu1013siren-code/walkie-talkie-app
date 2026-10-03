package jp.es.staffintercom;

import java.util.*;
import org.json.*;
import org.webrtc.PeerConnection;

/** ICE configuration arrives on the authenticated signaling session, never in the APK. */
final class RtcSettings {
    static List<PeerConnection.IceServer> defaults() {
        return Arrays.asList(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
    }
    static List<PeerConnection.IceServer> parse(JSONObject message) throws JSONException {
        JSONArray entries = message.getJSONArray("iceServers");
        if (entries.length() < 1 || entries.length() > 16) throw new JSONException("Invalid ICE configuration");
        List<PeerConnection.IceServer> result = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Object value = entry.get("urls");
            List<String> urls = new ArrayList<>();
            if (value instanceof String) urls.add((String) value);
            else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int n = 0; n < array.length(); n++) urls.add(array.getString(n));
            } else throw new JSONException("Invalid ICE configuration");
            if (urls.isEmpty()) throw new JSONException("Invalid ICE configuration");
            for (String url : urls) if (!url.matches("(?:stun|stuns|turn|turns):[^\\s/@]+")) throw new JSONException("Invalid ICE configuration");
            result.add(PeerConnection.IceServer.builder(urls).setUsername(entry.optString("username", ""))
                .setPassword(entry.optString("credential", "")).createIceServer());
        }
        return result;
    }
}
