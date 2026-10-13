package jp.es.staffintercom;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MicrophoneGainSettingsTest {
    @Before public void clear(){RuntimeEnvironment.getApplication().getSharedPreferences("intercom",0).edit().clear().commit();}
    @Test public void gainSelectionPersistsAndDefaultKeepsOriginalAudio(){
        IntercomService first=Robolectric.buildService(IntercomService.class).create().get();assertEquals(1f,first.getMicrophoneGain(),0);first.setMicrophoneGain(2.5f);first.onDestroy();
        IntercomService next=Robolectric.buildService(IntercomService.class).create().get();assertEquals(2.5f,next.getMicrophoneGain(),0);next.onDestroy();
    }
}
