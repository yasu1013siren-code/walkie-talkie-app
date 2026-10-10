package jp.es.staffintercom;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechRulesTest {
    @Test public void onlyCompleteLocalPhrasesTriggerTransmission() {
        assertEquals(1,SpeechRules.command("インカム 開始。","インカム開始","インカム停止"));
        assertEquals(-1,SpeechRules.command("インカム停止","インカム開始","インカム停止"));
        assertEquals(0,SpeechRules.command("インカム開始と伝えて","インカム開始","インカム停止"));
        assertEquals(0,SpeechRules.command("開始","開始","停止"));
        assertEquals(0,SpeechRules.command("同じ言葉","同じ言葉","同じ言葉"));
    }
}
