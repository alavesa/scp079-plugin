package fi.alavesa.scp079.gemini;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever the model wrote into one clean chat line: no markdown, no "SCP-079:" prefix, no wrapping
 * quotes, and never more than the allowed number of sentences - the system prompt asks for that, but the
 * limit is enforced here in case the model does not listen.
 */
public final class ReplySanitizer {

    /** A reply longer than this (after trimming to sentences) is cut at a word boundary. */
    private static final int MAX_CHARS = 480;

    private static final Pattern ROLE_PREFIX = Pattern.compile("^\\s*[<\\[(]?\\s*(?:SCP[-\\s]?079|model|assistant|AI)\\s*[>\\])]?\\s*[:>\\-]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_TAG = Pattern.compile("^\\s*[<\\[]\\s*SCP[-\\s]?079\\s*[>\\]]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINE_MARKER = Pattern.compile("(?m)^\\s*(?:#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)");
    /** A sentence: anything up to and including its closing punctuation (plus closing quotes/brackets). */
    private static final Pattern SENTENCE = Pattern.compile(".+?(?:[.!?…]+[\"'”’)\\]]*(?=\\s|$)|$)", Pattern.DOTALL);

    private ReplySanitizer() { }

    public static String clean(String raw, int maxSentences) {
        if (raw == null) return "";
        String s = raw.replace("\r", "");
        s = s.replaceAll("(?s)```[a-zA-Z0-9]*\\n?(.*?)```", "$1");      // code fences keep their content
        s = LINK.matcher(s).replaceAll("$1");
        s = LINE_MARKER.matcher(s).replaceAll("");
        s = s.replaceAll("(\\*\\*|__|~~|`)", "");                        // bold, strike, inline code
        s = s.replaceAll("(?<![\\p{L}\\p{N}])[*_]+|[*_]+(?![\\p{L}\\p{N}])", ""); // emphasis marks at word edges
        s = s.replaceAll("\\s+", " ").trim();

        // "SCP-079: ..." / "<SCP-079> ..." and quotes around the whole reply, in whatever order they come
        for (int i = 0; i < 3; i++) {
            String before = s;
            s = ROLE_PREFIX.matcher(s).replaceFirst("");
            s = ROLE_TAG.matcher(s).replaceFirst("");
            s = stripQuotes(s);
            if (s.equals(before)) break;
        }

        StringBuilder out = new StringBuilder();
        Matcher m = SENTENCE.matcher(s);
        int sentences = 0;
        while (sentences < maxSentences && m.find()) {
            String sentence = m.group().trim();
            if (sentence.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(sentence);
            sentences++;
        }
        s = out.toString().trim();
        if (s.length() > MAX_CHARS) {
            int cut = s.lastIndexOf(' ', MAX_CHARS);
            s = s.substring(0, cut > MAX_CHARS / 2 ? cut : MAX_CHARS).trim() + "…";
        }
        return s;
    }

    private static String stripQuotes(String s) {
        if (s.length() < 2) return s;
        char first = s.charAt(0), last = s.charAt(s.length() - 1);
        boolean pair = (first == '"' && last == '"') || (first == '“' && last == '”') || (first == '\'' && last == '\'')
            || (first == '«' && last == '»');
        if (!pair) return s;
        String inner = s.substring(1, s.length() - 1);
        // only when the quotes wrap the WHOLE reply: "Hello." he said, "Bye." must stay as it is
        return inner.indexOf(first) >= 0 && first == last ? s : inner.trim();
    }
}
