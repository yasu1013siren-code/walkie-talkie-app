package jp.es.staffintercom;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechResampleTest {
    @Test public void preservesDurationAndAveragesSigned48kSamplesWithoutChangingInput(){
        byte[] input=new byte[9600];
        for(int i=0;i<input.length;i+=6){input[i]=60;input[i+2]=30;input[i+4]=-30;input[i+5]=-1;}
        byte[] original=input.clone(),output=SpeechResample.to16k(input,48000);
        assertEquals(3200,output.length);assertArrayEquals(original,input);
        for(int i=0;i<output.length;i+=2){assertEquals(20,output[i]);assertEquals(0,output[i+1]);}
    }
    @Test public void preserves16kAndUpsamples8kWithCorrectSignedInterpolation(){
        byte[] input={0,0,100,0};assertSame(input,SpeechResample.to16k(input,16000));
        assertArrayEquals(new byte[]{0,0,50,0,100,0,100,0},SpeechResample.to16k(input,8000));
        assertEquals(0,SpeechResample.to16k(new byte[]{1},48000).length);
    }
}
