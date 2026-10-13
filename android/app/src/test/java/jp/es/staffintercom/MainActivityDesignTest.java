package jp.es.staffintercom;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityDesignTest {
    private TextView find(View root,String value){
        if(root instanceof TextView && value.equals(((TextView)root).getText().toString()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=find(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    @Test public void disconnectedScreenDisablesTransmissionAndSettingsExpandWithoutJoining() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).create().get();
        View root=activity.findViewById(android.R.id.content);
        assertFalse(find(root,"押しながら話す").isEnabled());
        assertFalse(find(root,"送信を開始").isEnabled());
        assertTrue(find(root,"通話に接続").isEnabled());
        TextView toggle=find(root,"設定・イヤホン操作  ＋");assertNotNull(toggle);
        View settings=(View)find(root,"表示名").getParent();assertEquals(View.GONE,settings.getVisibility());
        render(root,360,920,"design-offline.png");
        toggle.performClick();assertEquals(View.VISIBLE,settings.getVisibility());
        toggle.performClick();assertEquals(View.GONE,settings.getVisibility());
        render(root,320,740,"design-narrow.png");
        IntercomService session=new IntercomService();
        set(session,"joined",true);set(session,"connected",true);set(session,"status","受信待機中　ルーム main（2人）");set(session,"route","音声出力：S10");
        set(activity,"service",session);java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("render");update.setAccessible(true);update.invoke(activity);
        assertNotNull(find(root,"2人で接続中"));assertTrue(find(root,"押しながら話す").isEnabled());assertFalse(find(root,"通話に接続").isEnabled());
        render(root,360,920,"design-connected.png");
        set(session,"talking",true);update.invoke(activity);assertNotNull(find(root,"送信を停止"));assertNotNull(find(root,"送信中"));
        render(root,360,920,"design-transmitting.png");
    }
    private void set(Object target,String name,Object value) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private void render(View view,int width,int height,String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        File directory=new File("build/design-preview");assertTrue(directory.isDirectory() || directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
}
