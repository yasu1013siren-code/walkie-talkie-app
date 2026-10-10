package jp.es.staffintercom;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechPcmBufferTest {
    @Test public void retainsEvery10msFrameAtNativeRatesAndDrainsTailAtStop(){
        for(int rate:new int[]{8000,16000,48000}){
            SpeechPcmBuffer buffer=new SpeechPcmBuffer();
            byte[] frame=new byte[rate/100*2];frame[0]=42;
            for(int i=0;i<9;i++){buffer.append(frame);assertFalse(buffer.ready(rate));}
            buffer.append(frame);assertTrue(buffer.ready(rate));
            byte[] batch=buffer.take();assertEquals(frame.length*10,batch.length);
            for(int i=0;i<10;i++)assertEquals(42,batch[i*frame.length]);
            buffer.append(frame);assertFalse(buffer.ready(rate));
            assertArrayEquals(frame,buffer.take());assertEquals(0,buffer.take().length);
        }
    }
    @Test public void monoMixPreservesNativeDurationAndLowVolumeGetsGainWithoutClipping(){
        byte[] stereo=new byte[1920];for(int i=0;i<stereo.length;i+=4){stereo[i]=100;}
        byte[] mono=OfflineSpeech.monoForRecognition(stereo,2);
        assertEquals(960,mono.length);assertEquals(50,OfflineSpeech.pcmRms(mono));
        assertEquals(400,OfflineSpeech.pcmRms(OfflineSpeech.conditionForRecognition(mono)));
        assertArrayEquals(new byte[960],OfflineSpeech.conditionForRecognition(new byte[960]));
        byte[] loud={(byte)0xff,0x7f,0,(byte)0x80};
        assertArrayEquals(loud,OfflineSpeech.conditionForRecognition(loud));
    }
}
