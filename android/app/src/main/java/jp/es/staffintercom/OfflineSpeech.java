package jp.es.staffintercom;

import android.content.Context;
import android.media.AudioFormat;
import org.json.JSONObject;
import org.vosk.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Shares WebRTC's microphone samples; never opens a second microphone or uploads audio. */
final class OfflineSpeech {
    interface Listener { void text(String text, boolean complete, boolean transmitted, int epoch); void state(String text); }
    private final Context context; private final Listener listener;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean enabled, closed, transmitting; private volatile int epoch;
    private volatile long sampleCount, lastAudioAt, dropped; private volatile int inputRate, inputChannels, rms, recognitionRms; private volatile int peak; private volatile String lastWords="", phase="準備待ち";
    private volatile String inputLabel="通話入力の直接バッファ";
    void inputLabel(String value){inputLabel=value;}
    boolean startProbe(){if(!enabled || closed)return false;worker.execute(() -> probe.start());return true;}
    String diagnostic() {
        long age=android.os.SystemClock.elapsedRealtime()-lastAudioAt;
        return "音声入力：" + (sampleCount==0?"未取得":age>2000?"停止中":"取得中") + " / " + sampleCount + "回 / 音量 " + (age>2000?0:peak) + " / RMS " + rms + "\n取得経路："+inputLabel+"\n入力形式："+inputRate+"Hz / "+inputChannels+"ch / 欠落 "+dropped+" / 処理待ち（100ms単位） "+worker.getQueue().size()+"\n"+probe.status()+"\n認識処理：16000Hz\n認識用音量："+recognitionRms+" / 端末ノイズ抑制OFF\n認識：" + phase + (lastWords.isEmpty()?"":"\n認識文字："+lastWords);
    }
    private Model model; private Recognizer recognizer; private float rate;
    private final SpeechPcmBuffer pcmBuffer=new SpeechPcmBuffer();
    private final SpeechPcmBuffer incomingBatch=new SpeechPcmBuffer();
    private final SpeechProbe probe;
    private boolean lastTransmitting; private int lastEpoch; private long lastPartial;
    OfflineSpeech(Context c, Listener l) { context=c.getApplicationContext(); listener=l; probe=new SpeechProbe(context.getFilesDir()); }
    synchronized void enable(boolean value) {
        if (enabled == value || closed) return; enabled=value;
        if(!value)incomingBatch.take();
        worker.execute(() -> {
            if (closed) return;
            try {
                if (enabled && model == null) { phase="モデル準備中"; listener.state("日本語モデルを準備中…"); synchronized(OfflineSpeech.class){model=new Model(unpack().getAbsolutePath());} }
                if (!enabled) { probe.cancel(); if(recognizer != null) { recognizer.close(); recognizer=null; } }
                phase=enabled?"待機中":"OFF"; listener.state(enabled ? "文字起こし待機中（相手の新版も必要）" : "文字起こし・音声操作OFF");
            } catch (Throwable e) { enabled=false; phase="初期化エラー（"+e.getClass().getSimpleName()+"）"; listener.state("音声認識を準備できませんでした。通話は継続できます"); }
        });
    }
    boolean current(int token) { return token == epoch; }
    synchronized void transmission(boolean value) {
        if(closed)return;
        flushIncoming();
        int previous=epoch; transmitting=value; epoch++;
        worker.execute(() -> { if(closed || recognizer==null || lastEpoch!=previous)return;
            try { finishRecognition(); } catch(Throwable e){recognitionFailed(e);} });
    }
    void captureFailed(){phase="録音バッファをコピーできませんでした";}
    synchronized void samples(ByteBuffer buffer,int audioFormat,int channels,int sampleRate,int bytesRead) {
        if (!enabled || closed || audioFormat!=AudioFormat.ENCODING_PCM_16BIT) return;
        byte[] data=AudioPcmCopy.copy(buffer,bytesRead); if(data.length==0){captureFailed();return;} sampleCount++; lastAudioAt=android.os.SystemClock.elapsedRealtime();
        int volume=0; for(int i=0;i+1<data.length;i+=2)volume=Math.max(volume,Math.abs((short)((data[i]&255)|(data[i+1]<<8)))); peak=volume;
        if(inputRate!=0 && (inputRate!=sampleRate || inputChannels!=channels))flushIncoming();
        inputRate=sampleRate; inputChannels=channels;
        incomingBatch.append(monoForRecognition(data,channels));
        if(incomingBatch.ready(sampleRate))flushIncoming();
    }
    private void flushIncoming(){
        byte[] pcm=incomingBatch.take();if(pcm.length==0 || closed)return;
        if(worker.getQueue().size()>=60){dropped++;return;}
        int sampleRate=inputRate,token=epoch;boolean sent=transmitting;
        worker.execute(() -> decode(pcm,1,sampleRate,sent,token));
    }
    private void decode(byte[] data, int channels, int sampleRate, boolean sent, int token) {
        if (closed || !enabled || model == null || channels < 1 || sampleRate < 8000) return;
        try {
            if (recognizer == null || rate != sampleRate || token != lastEpoch) {
                finishRecognition();
                recognizer=new Recognizer(model,16000); rate=sampleRate; lastEpoch=token; lastTransmitting=sent;
            }
            pcmBuffer.append(monoForRecognition(data,channels));
            if(pcmBuffer.ready(sampleRate))feedBuffered();
        } catch (Throwable e) { recognitionFailed(e); }
    }
    private void recognitionFailed(Throwable e){enabled=false;phase="認識エラー（"+e.getClass().getSimpleName()+"）";listener.state("音声認識が停止しました。OFF→ONで再試行できます");}
    private void feedBuffered() throws Exception {
        byte[] pcm=pcmBuffer.take(); if(pcm.length==0 || recognizer==null)return;
        rms=pcmRms(pcm); byte[] raw=pcm; pcm=conditionForRecognition(pcm); recognitionRms=pcmRms(pcm); probe.append(raw,pcm,(int)rate);
        pcm=SpeechResample.to16k(pcm,(int)rate);
        boolean done=recognizer.acceptWaveForm(pcm,pcm.length);
        long now=android.os.SystemClock.elapsedRealtime();
        if(done)publish(recognizer.getResult(),true,lastTransmitting,lastEpoch);
        else if(now-lastPartial>=300){lastPartial=now;publish(recognizer.getPartialResult(),false,lastTransmitting,lastEpoch);}
    }
    private void finishRecognition() throws Exception {
        if(recognizer==null){pcmBuffer.take();return;}
        feedBuffered();publish(recognizer.getFinalResult(),true,lastTransmitting,lastEpoch);
        recognizer.close();recognizer=null;
        probe.finish();
    }
    static byte[] monoForRecognition(byte[] input,int channels){
        if(channels<1 || channels>8)return new byte[0];
        int frames=input.length/(2*channels);byte[] output=new byte[frames*2];
        for(int i=0;i<frames;i++){int value=mono(input,i,channels);output[i*2]=(byte)value;output[i*2+1]=(byte)(value>>8);}
        return output;
    }
    static int pcmRms(byte[] input){
        long energy=0;int count=input.length/2;
        for(int i=0;i+1<input.length;i+=2){int value=(short)((input[i]&255)|(input[i+1]<<8));energy+=(long)value*value;}
        return count==0?0:(int)Math.sqrt((double)energy/count);
    }
    static byte[] conditionForRecognition(byte[] input){
        int level=pcmRms(input);if(level<30)return input;
        // Raw AudioRecord samples precede WebRTC's automatic gain control.
        // Quiet Bluetooth PCM needs more gain than the old 8x cap. Keep headroom
        // for every sample so transients do not clip or alter the transmitted audio.
        int maximum=0;
        for(int i=0;i+1<input.length;i+=2)maximum=Math.max(maximum,Math.abs((short)((input[i]&255)|(input[i+1]<<8))));
        double gain=Math.max(1.0,Math.min(Math.min(32.0,1500.0/level),30000.0/maximum));
        byte[] output=new byte[input.length];
        for(int i=0;i+1<input.length;i+=2){int value=(short)((input[i]&255)|(input[i+1]<<8));value=(int)Math.max(-32768,Math.min(32767,Math.round(value*gain)));output[i]=(byte)value;output[i+1]=(byte)(value>>8);}
        return output;
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
    synchronized void close() {
        if(closed)return;closed=true;enabled=false;incomingBatch.take();worker.getQueue().clear();
        worker.execute(() -> {probe.cancel();if(recognizer!=null)recognizer.close();if(model!=null)model.close();}); worker.shutdown();
    }
}
