package jp.es.staffintercom;

import java.text.Normalizer;

/** Only a complete locally recognized command may change transmit state. */
final class SpeechRules {
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .replaceAll("[\\s\\p{Punct}。、！？]", "");
    }
    static int command(String text, String start, String stop) {
        String n = normalize(text), a = normalize(start), b = normalize(stop);
        if (a.length() < 3 || b.length() < 3 || a.equals(b)) return 0;
        if (n.equals(b)) return -1;
        return n.equals(a) ? 1 : 0;
    }
}
