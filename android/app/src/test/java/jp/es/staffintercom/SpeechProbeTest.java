package jp.es.staffintercom;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.*;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;

public class SpeechProbeTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void explicitCapturePreservesBothPcmStreamsAndStopsAtFiveSeconds()throws Exception{
        File dir=folder.newFolder();SpeechProbe probe=new SpeechProbe(dir);
        byte[] raw=new byte[1600],normalized=new byte[1600];raw[0]=42;normalized[0]=84;
        probe.append(raw,normalized,8000);assertEquals(0,dir.list().length);
        probe.start();for(int i=0;i<70;i++)probe.append(raw,normalized,8000);
        assertFalse(probe.active());
        byte[] a=Files.readAllBytes(new File(dir,"speech-probe-raw.wav").toPath());
        byte[] b=Files.readAllBytes(new File(dir,"speech-probe-recognition.wav").toPath());
        assertEquals(80044,a.length);assertEquals(a.length,b.length);
        ByteBuffer header=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8000,header.getInt(24));assertEquals(80000,header.getInt(40));
        for(int i=0;i<50;i++){assertEquals(42,a[44+i*1600]);assertEquals(84,b[44+i*1600]);}
    }
    @Test public void switchingRateCancelsCaptureWithoutWritingMislabeledAudio()throws Exception{
        File dir=folder.newFolder();SpeechProbe probe=new SpeechProbe(dir);probe.start();
        probe.append(new byte[1600],new byte[1600],8000);
        probe.append(new byte[3200],new byte[3200],16000);
        assertFalse(probe.active());assertEquals(0,dir.list().length);
    }
}
