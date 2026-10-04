package jp.es.staffintercom;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class ReceiveGainTest {
    @Before public void clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("intercom", 0).edit().clear().commit();
    }
    @Test public void defaultIsBoostedAndUserSelectionSurvivesServiceRecreation() {
        IntercomService first = Robolectric.buildService(IntercomService.class).create().get();
        assertEquals(2f, first.getReceiveGain(), 0f);
        first.setReceiveGain(1.5f); first.onDestroy();
        IntercomService next = Robolectric.buildService(IntercomService.class).create().get();
        assertEquals(1.5f, next.getReceiveGain(), 0f); next.onDestroy();
    }
    @Test public void invalidSavedGainRecoversAndOutOfRangeSelectionIsBounded() {
        RuntimeEnvironment.getApplication().getSharedPreferences("intercom", 0).edit().putFloat("receiveGain", Float.NaN).commit();
        IntercomService service = Robolectric.buildService(IntercomService.class).create().get();
        assertEquals(2f, service.getReceiveGain(), 0f);
        service.setReceiveGain(10f); assertEquals(3f, service.getReceiveGain(), 0f);
        service.setReceiveGain(-1f); assertEquals(1f, service.getReceiveGain(), 0f);
        service.onDestroy();
    }
}
