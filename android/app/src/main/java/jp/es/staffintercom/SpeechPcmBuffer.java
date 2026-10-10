package jp.es.staffintercom;

import java.io.ByteArrayOutputStream;

/** Worker-owned 100 ms PCM batching; take also drains short speech at PTT stop. */
final class SpeechPcmBuffer {
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    void append(byte[] pcm){bytes.write(pcm,0,pcm.length);}
    boolean ready(int rate){return rate>0 && bytes.size()>=rate/10*2;}
    byte[] take(){byte[] result=bytes.toByteArray();bytes.reset();return result;}
}
