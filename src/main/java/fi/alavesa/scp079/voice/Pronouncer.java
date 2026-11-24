package fi.alavesa.scp079.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Text -> the phonemes SCP-079's voice plays, with how many ticks each one takes.
 * <p>
 * Words are looked up in the CMU Pronouncing Dictionary (bundled as voice/cmudict.txt.gz, BSD licence in
 * voice/CMUDICT-LICENSE.txt; the first pronunciation of every plain word). Numbers are read digit by digit, short
 * all-capital words that are not in the dictionary are spelled, and anything else falls back to simple
 * letter-to-sound rules. Immutable after construction; safe to share between threads.
 */
public final class Pronouncer {

    /**
     * One sound, or a pause.
     *
     * @param phoneme ARPAbet in lower case ("aa", "sh"), or null for silence
     * @param ticks   how long until the next step starts (at least 1)
     */
    public record Step(String phoneme, int ticks) { }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z']+|[0-9]|[.!?]+|[,;:]|\\s-\\s|--");
    private static final String[] DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
    private static final Map<Character, String> LETTER_NAMES = Map.ofEntries(
        Map.entry('a', "EY1"), Map.entry('b', "B IY1"), Map.entry('c', "S IY1"), Map.entry('d', "D IY1"), Map.entry('e', "IY1"),
        Map.entry('f', "EH1 F"), Map.entry('g', "JH IY1"), Map.entry('h', "EY1 CH"), Map.entry('i', "AY1"), Map.entry('j', "JH EY1"),
        Map.entry('k', "K EY1"), Map.entry('l', "EH1 L"), Map.entry('m', "EH1 M"), Map.entry('n', "EH1 N"), Map.entry('o', "OW1"),
        Map.entry('p', "P IY1"), Map.entry('q', "K Y UW1"), Map.entry('r', "AA1 R"), Map.entry('s', "EH1 S"), Map.entry('t', "T IY1"),
        Map.entry('u', "Y UW1"), Map.entry('v', "V IY1"), Map.entry('w', "D AH1 B AH0 L Y UW0"), Map.entry('x', "EH1 K S"),
        Map.entry('y', "W AY1"), Map.entry('z', "Z IY1"));

    /** Pauses in ticks: between words, after a comma-like mark, after a sentence. */
    static final int WORD_GAP = 1, COMMA_PAUSE = 4, SENTENCE_PAUSE = 7;

    private final String[] words;
    private final String[] pronunciations;

    private Pronouncer(String[] words, String[] pronunciations) {
        this.words = words;
        this.pronunciations = pronunciations;
    }

    /** Reads the bundled dictionary (about a second; do it off the main thread). */
    public static Pronouncer load() throws IOException {
        try (InputStream raw = Pronouncer.class.getClassLoader().getResourceAsStream("voice/cmudict.txt.gz")) {
            if (raw == null) throw new IOException("voice/cmudict.txt.gz is missing from the plugin jar");
            return read(new GZIPInputStream(raw));
        }
    }

    /** Lines "word PH0 PH1 ...", sorted by word. */
    static Pronouncer read(InputStream in) throws IOException {
        List<String> w = new ArrayList<>(130_000), p = new ArrayList<>(130_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                w.add(line.substring(0, space));
                p.add(line.substring(space + 1));
            }
        }
        String[] words = w.toArray(String[]::new), prons = p.toArray(String[]::new);
        for (int i = 1; i < words.length; i++) {
            if (words[i - 1].compareTo(words[i]) > 0) throw new IOException("dictionary is not sorted at '" + words[i] + "'");
        }
        return new Pronouncer(words, prons);
    }

    /** For tests: a dictionary from "word PH PH" lines in any order. */
    static Pronouncer of(String... lines) {
        String[] sorted = lines.clone();
        Arrays.sort(sorted);
        String[] w = new String[sorted.length], p = new String[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            int space = sorted[i].indexOf(' ');
            w[i] = sorted[i].substring(0, space);
            p[i] = sorted[i].substring(space + 1);
        }
        return new Pronouncer(w, p);
    }

    /** ARPAbet with stress digits ("HH AH0 L OW1"), or null. */
    String lookup(String word) {
        int i = Arrays.binarySearch(words, word);
        return i >= 0 ? pronunciations[i] : null;
    }

    /** The whole text as steps, in order. Empty for text with nothing to say. */
    public List<Step> steps(String text) {
        List<Step> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String token = m.group();
            char first = token.charAt(0);
            if (Character.isLetter(first) || first == '\'') {
                word(token, out);
            } else if (Character.isDigit(first)) {
                word(DIGITS[first - '0'], out);
            } else if (first == '.' || first == '!' || first == '?') {
                pause(out, SENTENCE_PAUSE);
            } else {
                pause(out, COMMA_PAUSE);
            }
        }
        // no pause at the very end: nothing follows it
        while (!out.isEmpty() && out.getLast().phoneme() == null) out.removeLast();
        return out;
    }

    private void word(String token, List<Step> out) {
        String lower = token.toLowerCase(Locale.ROOT);
        String arpabet = lookup(lower);
        if (arpabet == null && lower.startsWith("'")) arpabet = lookup(lower.substring(1));
        if (arpabet == null && token.length() <= 4 && token.equals(token.toUpperCase(Locale.ROOT))) arpabet = spell(lower);
        if (arpabet == null) arpabet = LetterRules.toArpabet(lower);
        if (arpabet.isBlank()) return;
        for (String phone : arpabet.split(" ")) {
            if (phone.isEmpty()) continue;
            char stress = phone.charAt(phone.length() - 1);
            String bare = Character.isDigit(stress) ? phone.substring(0, phone.length() - 1) : phone;
            out.add(new Step(bare.toLowerCase(Locale.ROOT), PhonemeTable.ticks(bare, Character.isDigit(stress) ? stress - '0' : -1)));
        }
        pause(out, WORD_GAP);
    }

    private static String spell(String lower) {
        StringBuilder b = new StringBuilder();
        for (char c : lower.toCharArray()) {
            String name = LETTER_NAMES.get(c);
            if (name == null) return null;
            if (!b.isEmpty()) b.append(' ');
            b.append(name);
        }
        return b.toString();
    }

    /** A pause; next to another pause only the longer one counts. */
    private static void pause(List<Step> out, int ticks) {
        if (out.isEmpty()) return;
        Step last = out.getLast();
        if (last.phoneme() == null) {
            if (last.ticks() < ticks) out.set(out.size() - 1, new Step(null, ticks));
            return;
        }
        out.add(new Step(null, ticks));
    }
}
