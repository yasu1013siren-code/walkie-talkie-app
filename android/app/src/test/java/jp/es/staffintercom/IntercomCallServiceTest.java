package jp.es.staffintercom;

import android.content.Context;
import android.os.Looper;
import android.telecom.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowTelecomManager;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = IntercomCallServiceTest.PermittedTelecom.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class IntercomCallServiceTest {
    @Implements(TelecomManager.class)
    public static class PermittedTelecom extends ShadowTelecomManager {
        @Implementation protected boolean isIncomingCallPermitted(PhoneAccountHandle handle) { return true; }
    }
    private Context app;
    private ShadowTelecomManager telecom;
    private FakeClient client;
    private static class FakeClient implements IntercomCallService.Client {
        List<Boolean> transmissions = new ArrayList<>();
        public boolean transmit(boolean value) { transmissions.add(value); IntercomCallService.syncTalking(value); return true; }
        public boolean isTransmitting() { return !transmissions.isEmpty() && transmissions.get(transmissions.size() - 1); }
        public void status(String value) {}
        public void restoreRoute() {}
        public void interrupted() { transmit(false); }
    }
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        telecom = shadowOf(app.getSystemService(TelecomManager.class));
        telecom.setConnectionService(Robolectric.buildService(IntercomCallService.class).create().get());
        client = new FakeClient();
    }
    @After public void cleanup() { IntercomCallService.disable(); }
    private Connection start() {
        IntercomCallService.enable(app, client, true);
        Connection connection = telecom.allowIncomingCall(telecom.getLastIncomingCall());
        assertEquals(Connection.STATE_RINGING, connection.getState());
        assertTrue(client.transmissions.isEmpty());
        return connection;
    }
    @Test public void ownPendingAndRingingCallRetainAudioOnFocusLoss() {
        IntercomService audio = Robolectric.buildService(IntercomService.class).create().get();
        IntercomCallService.enable(app, client, true);
        audio.onAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue(audio.canUseAudio());
        Connection connection = telecom.allowIncomingCall(telecom.getLastIncomingCall());
        audio.onAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS);
        assertTrue(audio.canUseAudio());
        assertEquals(Connection.STATE_RINGING, connection.getState());
        assertTrue(client.transmissions.isEmpty());
        connection.onAnswer();
        assertEquals(Arrays.asList(true), client.transmissions);
        connection.onDisconnect();
        assertFalse(IntercomCallService.ownsControlAudio());
        audio.onDestroy();
    }
    @Test public void realTelecomFocusLossStopsAndDoesNotRearm() {
        Connection connection = start(); connection.onAnswer();
        IntercomCallService service = Robolectric.buildService(IntercomCallService.class).create().get();
        service.onConnectionServiceFocusLost();
        assertFalse(IntercomCallService.ownsControlAudio());
        assertEquals(Arrays.asList(true, false), client.transmissions);
        service.onConnectionServiceFocusGained(); connection.onAnswer();
        IntercomCallService.setConnected(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(1, telecom.getAllIncomingCalls().size());
        assertEquals(Arrays.asList(true, false), client.transmissions);
    }
    @Test public void ordinaryFocusLossWithoutS10BlocksAudio() {
        IntercomService audio = Robolectric.buildService(IntercomService.class).create().get();
        audio.onAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(audio.canUseAudio());
        audio.onAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertFalse(audio.canUseAudio()); audio.onDestroy();
    }
    @Test public void repeatedAnswerDisconnectCyclesStopAndRearmWithoutAutoTransmission() {
        Connection connection = start();
        for (int cycle = 0; cycle < 3; cycle++) {
            connection.onAnswer(); connection.onAnswer();
            assertEquals(Connection.STATE_ACTIVE, connection.getState());
            assertEquals(cycle * 2 + 1, client.transmissions.size());
            assertEquals(Boolean.TRUE, client.transmissions.get(cycle * 2));
            connection.onDisconnect();
            assertEquals(Connection.STATE_DISCONNECTED, connection.getState());
            assertEquals(Boolean.FALSE, client.transmissions.get(cycle * 2 + 1));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
            assertEquals(cycle + 2, telecom.getAllIncomingCalls().size());
            Connection stale = connection;
            connection = telecom.allowIncomingCall(telecom.getLastIncomingCall());
            stale.onAnswer();
            assertEquals((cycle + 1) * 2, client.transmissions.size());
            assertEquals(Connection.STATE_RINGING, connection.getState());
        }
    }
    @Test public void disableCancelsPendingRearmAndIgnoresLateAnswer() {
        Connection connection = start(); connection.onAnswer(); connection.onDisconnect();
        IntercomCallService.disable();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        connection.onAnswer();
        assertEquals(1, telecom.getAllIncomingCalls().size());
        assertEquals(Arrays.asList(true, false), client.transmissions);
    }
    @Test public void lostConnectionRejectsLateIncomingAndDoesNotRestartTransmission() {
        IntercomCallService.enable(app, client, true);
        ShadowTelecomManager.IncomingCallRecord request = telecom.getLastIncomingCall();
        IntercomCallService.setConnected(false);
        Connection late = telecom.allowIncomingCall(request);
        assertEquals(Connection.STATE_DISCONNECTED, late.getState());
        late.onAnswer();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(client.transmissions.isEmpty());
        assertEquals(1, telecom.getAllIncomingCalls().size());
    }
    @Test public void screenStartBeforeCallCreationMakesFirstHeadsetPressStop() {
        IntercomCallService.enable(app, client, true);
        client.transmit(true);
        Connection connection = telecom.allowIncomingCall(telecom.getLastIncomingCall());
        assertEquals(Connection.STATE_ACTIVE, connection.getState());
        connection.onDisconnect();
        assertEquals(Arrays.asList(true, false), client.transmissions);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(2, telecom.getAllIncomingCalls().size());
    }
    @Test public void screenStopAlsoRearmsAndDoesNotStartMicrophone() {
        Connection connection = start();
        IntercomCallService.syncTalking(true); assertEquals(Connection.STATE_ACTIVE, connection.getState());
        IntercomCallService.syncTalking(false); assertEquals(Connection.STATE_DISCONNECTED, connection.getState());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(2, telecom.getAllIncomingCalls().size()); assertTrue(client.transmissions.isEmpty());
    }
}
