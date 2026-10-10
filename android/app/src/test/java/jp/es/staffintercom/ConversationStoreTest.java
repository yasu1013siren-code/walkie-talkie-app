package jp.es.staffintercom;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ConversationStoreTest {
    @Test public void finalTextSurvivesReopenAndUserCanClearIt(){
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("conversation.db");
        ConversationStore store=new ConversationStore(c);store.add("端末A","聞こえています");store.close();
        store=new ConversationStore(c);assertTrue(store.text(100).contains("聞こえています"));
        store.clear();assertEquals("",store.text(100));store.close();
    }
}
