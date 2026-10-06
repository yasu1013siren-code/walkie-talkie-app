package jp.es.staffintercom;
import android.graphics.Insets;
import android.view.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class Api36UiTest {
    @Test public void systemBarsAndKeyboardDoNotCoverControlsOrAccumulatePadding() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        View scroll = content.getChildAt(0);
        WindowInsets keyboard = new WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 260)).build();
        scroll.dispatchApplyWindowInsets(keyboard);
        assertEquals(24, scroll.getPaddingTop()); assertEquals(260, scroll.getPaddingBottom());
        scroll.dispatchApplyWindowInsets(new WindowInsets.Builder(keyboard)
            .setInsets(WindowInsets.Type.ime(), Insets.NONE).build());
        assertEquals(24, scroll.getPaddingTop()); assertEquals(48, scroll.getPaddingBottom());
        activity.finish();
    }
    @Test public void malformedLicenseChallengeFailsWithoutCallingGoogle() {
        final boolean[] failed = {false};
        PlayLicense.request(RuntimeEnvironment.getApplication(), 0, "invalid", new PlayLicense.Callback() {
            public void success(String token) { fail("Invalid challenge must not be accepted"); }
            public void failure() { failed[0] = true; }
        });
        assertTrue(failed[0]);
    }
}
