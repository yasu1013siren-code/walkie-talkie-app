package jp.es.staffintercom;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
import org.webrtc.PeerConnection;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RtcSettingsTest {
    @Test public void parsesAuthenticatedTurnAndKeepsStunFallback() throws Exception {
        assertEquals(2, RtcSettings.defaults().size());
        List<PeerConnection.IceServer> servers = RtcSettings.parse(new JSONObject("{\"iceServers\":[{\"urls\":[\"turn:relay.example:3478?transport=udp\",\"turns:relay.example:5349?transport=tcp\"],\"username\":\"temporary\",\"credential\":\"temporary-password\"}]}"));
        assertEquals(2, servers.get(0).urls.size());
        assertEquals("temporary", servers.get(0).username);
        assertEquals("temporary-password", servers.get(0).password);
    }
    @Test public void rejectsEmptyOrNonIceConfiguration() throws Exception {
        for (String invalid : new String[]{"{\"iceServers\":[]}", "{\"iceServers\":[{\"urls\":\"https://example.com\"}]}"}) {
            try { RtcSettings.parse(new JSONObject(invalid)); fail("must reject"); } catch (JSONException expected) {}
        }
    }
}
