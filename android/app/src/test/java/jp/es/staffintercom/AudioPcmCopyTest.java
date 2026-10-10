package jp.es.staffintercom;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioPcmCopyTest {
    @Test public void readsDirectNativeMemorySynchronouslyWithoutChangingItsState(){
        ByteBuffer nativePcm=ByteBuffer.allocateDirect(16);
        byte[] voice={1,2,3,4,5,6,7,8};nativePcm.put(voice);
        nativePcm.position(7);nativePcm.limit(10);
        byte[] copy=AudioPcmCopy.copy(nativePcm,8);
        assertArrayEquals(voice,copy);assertEquals(7,nativePcm.position());assertEquals(10,nativePcm.limit());
        nativePcm.put(0,(byte)99);assertEquals(1,copy[0]);
    }
    @Test public void respectsTheValidByteCountInOffsetViews(){
        ByteBuffer parent=ByteBuffer.allocate(24);parent.position(4);parent.limit(20);
        ByteBuffer slice=parent.slice();slice.put(new byte[]{12,0,34,0,56,0,78,0});
        assertArrayEquals(new byte[]{12,0,34,0},AudioPcmCopy.copy(slice,4));
    }
    @Test public void rejectsIncompleteAndOutOfBoundsPcm(){
        ByteBuffer b=ByteBuffer.allocateDirect(8);
        for(int size:new int[]{-1,0,3,10})assertEquals(0,AudioPcmCopy.copy(b,size).length);
        assertEquals(0,AudioPcmCopy.copy(null,8).length);
    }
}
