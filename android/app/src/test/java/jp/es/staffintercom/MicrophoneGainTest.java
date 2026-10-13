package jp.es.staffintercom;
import java.nio.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class MicrophoneGainTest {
    @Test public void boostsTheNativePcmRegionWithoutChangingPositionLimitOrTail(){
        ByteBuffer b=ByteBuffer.allocateDirect(10).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0,(short)1000);b.putShort(2,(short)-2000);b.putShort(4,(short)777);b.position(3);b.limit(7);
        MicrophoneGain.apply(b,4,2f);assertEquals(2000,b.getShort(0));assertEquals(-4000,b.getShort(2));assertEquals(777,b.getShort(4));assertEquals(3,b.position());assertEquals(7,b.limit());
    }
    @Test public void limitsPeaksWithoutFlatteningSpeechAndLeavesDefaultInputUntouched(){
        ByteBuffer b=ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0,(short)20000);b.putShort(2,(short)-10000);b.putShort(4,Short.MIN_VALUE);
        MicrophoneGain.apply(b,4,4f);assertEquals(30000,b.getShort(0));assertEquals(-15000,b.getShort(2));assertEquals(Short.MIN_VALUE,b.getShort(4));
        byte[] original=b.array().clone();MicrophoneGain.apply(b,6,1f);assertArrayEquals(original,b.array());MicrophoneGain.apply(b,6,4f);assertArrayEquals(original,b.array());
    }
    @Test public void invalidValuesAndFormatsAreSafe(){
        assertEquals(1f,MicrophoneGain.normalize(Float.NaN),0);assertEquals(4f,MicrophoneGain.normalize(99f),0);assertEquals(1f,MicrophoneGain.normalize(-3f),0);
        ByteBuffer b=ByteBuffer.wrap(new byte[]{1,2,3,4});byte[] original=b.array().clone();
        for(int n:new int[]{-1,0,3,6})MicrophoneGain.apply(b,n,2f);assertArrayEquals(original,b.array());
        MicrophoneGain.apply(b.asReadOnlyBuffer(),4,2f);MicrophoneGain.apply(null,4,2f);
    }
}
