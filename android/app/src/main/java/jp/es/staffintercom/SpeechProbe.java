package jp.es.staffintercom;

import java.io.*;
import java.nio.*;

/** Explicit, bounded, private capture of the exact PCM supplied to the recognizer. */
final class SpeechProbe {
    private final File directory;
    private final ByteArrayOutputStream raw=new ByteArrayOutputStream(), recognized=new ByteArrayOutputStream();
    private volatile boolean active;
    private volatile String status="音声確認：未録音";
    private int rate;
    SpeechProbe(File directory){this.directory=directory;}
    void start(){new File(directory,"speech-probe-raw.wav").delete();new File(directory,"speech-probe-recognition.wav").delete();raw.reset();recognized.reset();rate=0;active=true;status="音声確認：録音中。5秒ほど話してください";}
    String status(){return status;}
    boolean active(){return active;}
    void append(byte[] before,byte[] after,int sampleRate){
        if(!active)return;
        if(sampleRate<8000 || sampleRate>192000 || before.length!=after.length){cancel();return;}
        if(rate!=0 && rate!=sampleRate){cancel();status="音声確認：入力が切り替わりました。再録音してください";return;}
        rate=sampleRate;
        int remaining=rate*2*5-raw.size(),count=Math.min(remaining,before.length)&~1;
        raw.write(before,0,count);recognized.write(after,0,count);
        if(raw.size()>=rate*2*5)finish();
    }
    void finish(){
        if(!active)return;active=false;
        if(raw.size()==0){status="音声確認：録音できませんでした。送信中に再試行してください";return;}
        try{
            writeWave(new File(directory,"speech-probe-raw.wav"),raw.toByteArray(),rate);
            writeWave(new File(directory,"speech-probe-recognition.wav"),recognized.toByteArray(),rate);
            status="音声確認：保存済み（"+(raw.size()/(rate*2.0))+"秒）。送信を停止して再生してください";
        }catch(IOException e){status="音声確認：保存できませんでした";}
        raw.reset();recognized.reset();
    }
    void cancel(){active=false;raw.reset();recognized.reset();status="音声確認：中止";}
    static void writeWave(File file,byte[] pcm,int rate)throws IOException{
        ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R','I','F','F'}).putInt(36+pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '});
        h.putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16);
        h.put(new byte[]{'d','a','t','a'}).putInt(pcm.length);
        File temp=new File(file.getPath()+".tmp");
        try(OutputStream out=new FileOutputStream(temp)){out.write(h.array());out.write(pcm);}
        if(!temp.renameTo(file))throw new IOException("Could not install capture");
    }
}
