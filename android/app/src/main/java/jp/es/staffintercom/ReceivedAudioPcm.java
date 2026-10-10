package jp.es.staffintercom;

/** Size of a decoded interleaved PCM16 frame, validated before native-memory copy. */
final class ReceivedAudioPcm {
    static int byteCount(int bits,int channels,int frames,int capacity){
        if(bits!=16 || channels<1 || channels>8 || frames<=0)return 0;
        long bytes=(long)channels*frames*2;
        return bytes>capacity || bytes>Integer.MAX_VALUE?0:(int)bytes;
    }
}
