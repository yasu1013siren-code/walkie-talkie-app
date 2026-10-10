package jp.es.staffintercom;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceivedAudioPcmTest {
    @Test public void countsFramesAndChannelsInsteadOfUsingSampleRateAsByteLength(){
        assertEquals(960,ReceivedAudioPcm.byteCount(16,1,480,960));
        assertEquals(1920,ReceivedAudioPcm.byteCount(16,2,480,1920));
    }
    @Test public void refusesUnsupportedOrUnsafeNativeMemoryReads(){
        assertEquals(0,ReceivedAudioPcm.byteCount(32,1,480,1920));
        assertEquals(0,ReceivedAudioPcm.byteCount(16,0,480,960));
        assertEquals(0,ReceivedAudioPcm.byteCount(16,1,-1,960));
        assertEquals(0,ReceivedAudioPcm.byteCount(16,2,480,960));
        assertEquals(0,ReceivedAudioPcm.byteCount(16,8,Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
}
