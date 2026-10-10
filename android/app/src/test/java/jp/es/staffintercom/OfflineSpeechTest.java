package jp.es.staffintercom;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineSpeechTest {
    @Test public void convertsBluetooth8kAndWebRtc48kTo16kMono() {
        for(int rate:new int[]{8000,16000,48000}){
            byte[] samples=new byte[rate/100*2];
            for(int i=0;i<samples.length;i+=2){samples[i]=(byte)0xe8;samples[i+1]=3;}
            byte[] output=OfflineSpeech.convert(samples,1,rate);
            assertEquals(320,output.length);
            for(int i=0;i<output.length;i+=2)assertEquals(1000,(short)((output[i]&255)|(output[i+1]<<8)));
        }
    }
    @Test public void mixesStereoAndRejectsInvalidFormats(){
        byte[] samples=new byte[640];for(int i=0;i<samples.length;i+=4){samples[i]=(byte)0xe8;samples[i+1]=3;}
        byte[] output=OfflineSpeech.convert(samples,2,16000);
        assertEquals(320,output.length);assertEquals(500,(short)((output[0]&255)|(output[1]<<8)));
        assertEquals(0,OfflineSpeech.convert(samples,0,16000).length);
        assertEquals(0,OfflineSpeech.convert(samples,1,0).length);
    }
}
