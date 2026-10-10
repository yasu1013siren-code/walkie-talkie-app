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
        assertEquals(1500,OfflineSpeech.pcmRms(OfflineSpeech.conditionForRecognition(mono)));
        assertArrayEquals(new byte[960],OfflineSpeech.conditionForRecognition(new byte[960]));
        byte[] loud={(byte)0xff,0x7f,0,(byte)0x80};
        assertArrayEquals(loud,OfflineSpeech.conditionForRecognition(loud));
    }
    @Test public void quietBluetoothSpeechIsAmplifiedWithoutMutatingSharedPcm(){
        byte[] pcm=new byte[960];for(int i=0;i<pcm.length;i+=2){pcm[i]=(byte)((i%4==0)?56:-56);pcm[i+1]=(byte)((i%4==0)?0:-1);}
        byte[] original=pcm.clone();
        assertEquals(56,OfflineSpeech.pcmRms(pcm));
        assertEquals(1500,OfflineSpeech.pcmRms(OfflineSpeech.conditionForRecognition(pcm)));
        assertArrayEquals(original,pcm);
        byte[] transient=new byte[9600];for(int i=0;i<transient.length;i+=2)transient[i]=50;
        transient[0]=0x20;transient[1]=0x4e; // 20000 peak among quiet samples
        byte[] conditioned=OfflineSpeech.conditionForRecognition(transient);
        int peak=0;for(int i=0;i<conditioned.length;i+=2)peak=Math.max(peak,Math.abs((short)((conditioned[i]&255)|(conditioned[i+1]<<8))));
        assertEquals(30000,peak);
    }
}
