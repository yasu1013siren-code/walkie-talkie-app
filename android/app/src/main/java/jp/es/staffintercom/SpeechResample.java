package jp.es.staffintercom;

/** Downsample captured PCM to the recognizer's 16 kHz model rate before native decoding. */
final class SpeechResample {
    static byte[] to16k(byte[] pcm,int rate){
        if(rate==16000)return pcm;
        if(rate<8000 || rate>192000 || (pcm.length&1)!=0)return new byte[0];
        int inputFrames=pcm.length/2,outputFrames=(int)((long)inputFrames*16000/rate);
        byte[] output=new byte[outputFrames*2];
        for(int i=0;i<outputFrames;i++){
            int value;
            if(rate>16000 && rate%16000==0){
                int factor=rate/16000;long sum=0;
                for(int k=0;k<factor;k++)sum+=sample(pcm,i*factor+k);
                value=(int)(sum/factor);
            }else{
                double position=(double)i*rate/16000;int a=(int)position,b=Math.min(a+1,inputFrames-1);
                value=(int)Math.round(sample(pcm,a)+(sample(pcm,b)-sample(pcm,a))*(position-a));
            }
            output[i*2]=(byte)value;output[i*2+1]=(byte)(value>>8);
        }
        return output;
    }
    private static int sample(byte[] pcm,int frame){int p=frame*2;return (short)((pcm[p]&255)|(pcm[p+1]<<8));}
}
