package jp.es.staffintercom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Scales only the valid microphone PCM region before WebRTC consumes it. */
final class MicrophoneGain {
    static float normalize(float value){return Float.isFinite(value)?Math.max(1f,Math.min(4f,value)):1f;}
    static void apply(ByteBuffer input,int bytesRead,float requested){
        float gain=normalize(requested);
        if(gain==1f || input==null || input.isReadOnly() || bytesRead<=0 || (bytesRead&1)!=0 || bytesRead>input.capacity())return;
        ByteBuffer pcm=input.duplicate().order(ByteOrder.LITTLE_ENDIAN);pcm.clear();pcm.limit(bytesRead);
        int peak=0;for(int p=0;p<bytesRead;p+=2)peak=Math.max(peak,Math.abs((int)pcm.getShort(p)));
        if(peak==0)return;
        // A peak-aware limiter retains the waveform and never increases already-full-scale input.
        double effective=Math.max(1d,Math.min(gain,30000d/peak));
        for(int p=0;p<bytesRead;p+=2){int value=(int)Math.round(pcm.getShort(p)*effective);pcm.putShort(p,(short)Math.max(-32768,Math.min(32767,value)));}
    }
}
