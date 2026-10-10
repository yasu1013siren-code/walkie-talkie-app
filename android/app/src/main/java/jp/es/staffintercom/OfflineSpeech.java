package jp.es.staffintercom;

import android.content.Context;
import android.media.AudioFormat;
import org.json.JSONObject;
import org.vosk.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.io.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Shares WebRTC's microphone samples; never opens a second microphone or uploads audio. */
final class OfflineSpeech {
    interface Listener { void text(String text, boolean complete, boolean transmitted, int epoch); void state(String text); }
    private final Context context; private final Listener listener;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(120),new ThreadPoolExecutor.DiscardPolicy());
    private volatile boolean enabled, closed, transmitting; private volatile int epoch;
    private volatile long sampleCount, lastAudioAt; private volatile int peak; private volatile String lastWords="", phase="準備待ち";
    String diagnostic() {
        long age=android.os.SystemClock.elapsedRealtime()-lastAudioAt;
        return "マイク入力：" + (sampleCount==0?"未取得":age>2000?"停止中":"取得中") + " / " + sampleCount + "回 / 音量 " + (age>2000?0:peak) + "\n認識：" + phase + (lastWords.isEmpty()?"":"\n自分の認識："+lastWords);
    }
    private Model model; private Recognizer recognizer; private float rate;
    private boolean lastTransmitting; private int lastEpoch; private long lastPartial;
    OfflineSpeech(Context c, Listener l) { context=c.getApplicationContext(); listener=l; }
    void enable(boolean value) {
        if (enabled == value || closed) return; enabled=value;
        worker.execute(() -> {
            if (closed) return;
            try {
                if (enabled && model == null) { phase="モデル準備中"; listener.state("日本語モデルを準備中…"); model=new Model(unpack().getAbsolutePath()); }
                if (!enabled && recognizer != null) { recognizer.close(); recognizer=null; }
                phase=enabled?"待機中":"OFF"; listener.state(enabled ? "文字起こし待機中（相手の新版も必要）" : "文字起こし・音声操作OFF");
            } catch (Throwable e) { enabled=false; phase="初期化エラー（"+e.getClass().getSimpleName()+"）"; listener.state("音声認識を準備できませんでした。通話は継続できます"); }
        });
    }
    boolean current(int token) { return token == epoch; }
    void transmission(boolean value) { transmitting=value; epoch++; }
    void samples(JavaAudioDeviceModule.AudioSamples samples) {
        if (!enabled || closed || samples.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT) return;
        byte[] data=samples.getData().clone(); sampleCount++; lastAudioAt=android.os.SystemClock.elapsedRealtime();
        int volume=0; for(int i=0;i+1<data.length;i+=2)volume=Math.max(volume,Math.abs((short)((data[i]&255)|(data[i+1]<<8)))); peak=volume;
        int channels=samples.getChannelCount(), sampleRate=samples.getSampleRate();
        boolean sent=transmitting; int token=epoch;
        worker.execute(() -> decode(data, channels, sampleRate, sent, token));
    }
    private void decode(byte[] data, int channels, int sampleRate, boolean sent, int token) {
        if (closed || !enabled || model == null || channels < 1 || sampleRate < 8000) return;
        try {
            if (recognizer == null || rate != 16000 || token != lastEpoch) {
                if (recognizer != null) { publish(recognizer.getFinalResult(),true,lastTransmitting,lastEpoch); recognizer.close(); }
                recognizer=new Recognizer(model,16000); rate=16000; lastEpoch=token; lastTransmitting=sent;
            }
            byte[] pcm = convert(data, channels, sampleRate);
            boolean done=recognizer.acceptWaveForm(pcm,pcm.length);
            long now=android.os.SystemClock.elapsedRealtime();
            if (done) publish(recognizer.getResult(),true,sent,token);
            else if (now-lastPartial>=300) { lastPartial=now; publish(recognizer.getPartialResult(),false,sent,token); }
        } catch (Throwable e) { enabled=false; phase="認識エラー（"+e.getClass().getSimpleName()+"）"; listener.state("音声認識が停止しました。OFF→ONで再試行できます"); }
    }
    private void publish(String json, boolean done, boolean sent, int token) throws Exception {
        String text=new JSONObject(json).optString(done?"text":"partial", "").trim();
        if(!text.isEmpty()) { lastWords=text; phase=done?"確定文字あり":"認識中"; }
        listener.text(text,done,sent,token);
    }
    static byte[] convert(byte[] in, int channels, int sampleRate) {
        if (channels<1 || sampleRate<8000 || sampleRate>192000) return new byte[0];
        int frames=in.length/(2*channels), outFrames=(int)((long)frames*16000/sampleRate);
        byte[] out=new byte[outFrames*2];
        for (int i=0;i<outFrames;i++) {
            double pos=(double)i*sampleRate/16000; int a=(int)pos, b=Math.min(a+1,frames-1); double fraction=pos-a;
            int x=mono(in,a,channels), y=mono(in,b,channels); int value=(int)Math.round(x+(y-x)*fraction);
            out[i*2]=(byte)value; out[i*2+1]=(byte)(value>>8);
        }
        return out;
    }
    private static int mono(byte[] data,int frame,int channels) {
        long sum=0; for(int c=0;c<channels;c++){int p=(frame*channels+c)*2;sum+=(short)((data[p]&255)|(data[p+1]<<8));}
        return (int)(sum/channels);
    }
    private File unpack() throws IOException {
        File dir=new File(context.getFilesDir(),"speech-ja-0.22"), marker=new File(dir,"ready");
        if(marker.exists()) return new File(dir,"vosk-model-small-ja-0.22");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException();
        try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("speech-ja.zip"))){
            ZipEntry e; long total=0; byte[] buf=new byte[16384];
            while((e=zip.getNextEntry())!=null){
                File out=new File(dir,e.getName()); if(!out.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new IOException();
                if(e.isDirectory()){if(!out.isDirectory()&&!out.mkdirs())throw new IOException();continue;}
                if(!out.getParentFile().isDirectory()&&!out.getParentFile().mkdirs())throw new IOException();
                try(OutputStream f=new FileOutputStream(out)){int n;while((n=zip.read(buf))>0){total+=n;if(total>200L*1024*1024)throw new IOException();f.write(buf,0,n);}}
            }
        }
        try(FileOutputStream f=new FileOutputStream(marker)){f.write(1);}
        return new File(dir,"vosk-model-small-ja-0.22");
    }
    void close() {
        if(closed)return;closed=true;enabled=false;worker.getQueue().clear();
        worker.execute(() -> {if(recognizer!=null)recognizer.close();if(model!=null)model.close();}); worker.shutdown();
    }
}
