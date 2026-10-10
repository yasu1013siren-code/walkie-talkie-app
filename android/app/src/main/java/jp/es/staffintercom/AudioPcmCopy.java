package jp.es.staffintercom;

import java.nio.ByteBuffer;

/** Copy native audio memory while its callback is active; never retain or mutate it. */
final class AudioPcmCopy {
    static byte[] copy(ByteBuffer buffer,int bytesRead){
        if(buffer==null || bytesRead<=0 || (bytesRead&1)!=0 || bytesRead>buffer.capacity())return new byte[0];
        // AudioRecord.read(ByteBuffer,...) writes from index zero independently of
        // the Java position/limit. Reading array() can use a separate backing view.
        ByteBuffer view=buffer.duplicate();view.clear();view.limit(bytesRead);
        byte[] pcm=new byte[bytesRead];view.get(pcm);return pcm;
    }
}
