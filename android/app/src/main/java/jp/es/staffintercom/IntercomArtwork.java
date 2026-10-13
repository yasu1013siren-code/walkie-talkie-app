package jp.es.staffintercom;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Static vector artwork: draws only when the screen is redrawn, with no background animation. */
final class IntercomArtwork extends Drawable {
    static final int AMBIENT=0, HALO=1, DIAL=2, WAVE=3;
    private final int kind;private final boolean transmitting;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    IntercomArtwork(int kind,boolean transmitting){this.kind=kind;this.transmitting=transmitting;}
    @Override public void draw(Canvas canvas){
        Rect b=getBounds();if(b.isEmpty())return;
        canvas.save();canvas.translate(b.left,b.top);float w=b.width(),h=b.height(),s=Math.min(w,h),x=w/2,y=h/2;
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);
        if(kind==AMBIENT){
            paint.setColor(0xff0b1025);canvas.drawRect(0,0,w,h,paint);
            glow(canvas,w*.08f,h*.07f,w*.9f,0x553b3d8e);
            glow(canvas,w*1.05f,h*.39f,w*.72f,0x4430b9cf);
            glow(canvas,w*.02f,h*.76f,w*.7f,0x444d277d);
        }else if(kind==HALO){
            glow(canvas,x*.65f,y*.75f,s*.53f,transmitting?0x66ff6f9d:0x667f65f5);
            glow(canvas,x*1.35f,y*1.12f,s*.5f,transmitting?0x55ffb486:0x5578e4f4);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s*.004f);paint.setShader(new SweepGradient(x,y,new int[]{0xff8beaf7,0xffb89bff,0xffffa8c8,0xff8beaf7},null));
            canvas.drawCircle(x,y,s*.44f,paint);
            paint.setShader(null);paint.setColor(0x405e75a4);paint.setStrokeWidth(s*.003f);canvas.drawCircle(x,y,s*.49f,paint);
        }else if(kind==DIAL){
            paint.setShader(new LinearGradient(0,0,w,h,transmitting?new int[]{0xff794878,0xff49325f,0xff713f50}:new int[]{0xff483879,0xff292851,0xff17475e},null,Shader.TileMode.CLAMP));
            canvas.drawCircle(x,y,s*.48f,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s*.004f);
            paint.setShader(new SweepGradient(x,y,new int[]{0xff94f5fa,0xffbda7ff,transmitting?0xffffb89b:0xffe3b1ef,0xff94f5fa},null));canvas.drawCircle(x,y,s*.48f,paint);
            paint.setShader(null);paint.setStrokeWidth(s*.003f);paint.setColor(0x40f0eaff);canvas.drawCircle(x,y,s*.43f,paint);
            for(int i=0;i<40;i++){double a=2*Math.PI*i/40;float r=s*.403f;paint.setColor(i%5==0?0x80b6cfff:0x35b6cfff);canvas.drawLine(x+(float)Math.cos(a)*r,y+(float)Math.sin(a)*r,x+(float)Math.cos(a)*(r-s*.013f),y+(float)Math.sin(a)*(r-s*.013f),paint);}
        }else{
            int[] lengths={3,7,12,6,16,10,19,8,13,6,17,9,14,7,3};float bar=w/lengths.length*.35f,step=w/lengths.length;
            paint.setShader(new LinearGradient(0,0,w,0,new int[]{0xff78e8f4,0xffb99eff,0xffffb2ce},null,Shader.TileMode.CLAMP));
            for(int i=0;i<lengths.length;i++){float height=h*lengths[i]/20f;canvas.drawRoundRect(i*step+(step-bar)/2,(h-height)/2,i*step+(step+bar)/2,(h+height)/2,bar,bar,paint);}
        }
        paint.setShader(null);canvas.restore();
    }
    private void glow(Canvas canvas,float x,float y,float radius,int color){paint.setStyle(Paint.Style.FILL);paint.setShader(new RadialGradient(x,y,radius,new int[]{color,color&0x00ffffff},null,Shader.TileMode.CLAMP));canvas.drawCircle(x,y,radius,paint);paint.setShader(null);}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
