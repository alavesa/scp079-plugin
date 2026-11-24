package fi.alavesa.scp079.voice;

/**
 * Spelling -> ARPAbet for words the dictionary does not have (names, made-up words). Deliberately simple: longest
 * matching letter group first, a handful of context rules (soft c and g, silent final e, y as a vowel). Good enough
 * for a machine voice; real words come from the dictionary.
 */
final class LetterRules {

    /** Letter groups, longest first within each starting letter. */
    private static final String[][] GROUPS = {
        {"tch", "CH"}, {"igh", "AY1"}, {"sch", "S K"},
        {"ch", "CH"}, {"sh", "SH"}, {"th", "TH"}, {"ph", "F"}, {"ng", "NG"}, {"ck", "K"}, {"qu", "K W"}, {"wh", "W"},
        {"ee", "IY1"}, {"ea", "IY1"}, {"oo", "UW1"}, {"ou", "AW1"}, {"ow", "OW1"}, {"ai", "EY1"}, {"ay", "EY1"},
        {"oi", "OY1"}, {"oy", "OY1"}, {"au", "AO1"}, {"aw", "AO1"}, {"er", "ER0"}, {"ir", "ER1"}, {"ur", "ER1"},
        {"ar", "AA1 R"}, {"or", "AO1 R"}, {"kn", "N"}, {"wr", "R"}, {"gh", ""},
    };

    private LetterRules() { }

    static String toArpabet(String word) {
        StringBuilder out = new StringBuilder();
        String w = word.replace("'", "");
        int n = w.length();
        for (int i = 0; i < n; ) {
            String hit = null;
            int len = 0;
            for (String[] group : GROUPS) {
                if (w.startsWith(group[0], i) && group[0].length() > len) {
                    hit = group[1];
                    len = group[0].length();
                }
            }
            if (hit == null) {
                char c = w.charAt(i);
                char next = i + 1 < n ? w.charAt(i + 1) : 0;
                hit = single(c, next, i, n);
                len = 1;
            }
            if (!hit.isEmpty()) {
                if (!out.isEmpty()) out.append(' ');
                out.append(hit);
            }
            i += len;
        }
        return out.toString();
    }

    private static String single(char c, char next, int i, int n) {
        boolean soft = next == 'e' || next == 'i' || next == 'y';
        return switch (c) {
            case 'a' -> "AE1";
            case 'b' -> "B";
            case 'c' -> soft ? "S" : "K";
            case 'd' -> "D";
            case 'e' -> i == n - 1 && n > 2 ? "" : "EH1"; // a final e is silent
            case 'f' -> "F";
            case 'g' -> soft ? "JH" : "G";
            case 'h' -> "HH";
            case 'i' -> "IH1";
            case 'j' -> "JH";
            case 'k' -> "K";
            case 'l' -> "L";
            case 'm' -> "M";
            case 'n' -> "N";
            case 'o' -> "AA1";
            case 'p' -> "P";
            case 'q' -> "K";
            case 'r' -> "R";
            case 's' -> "S";
            case 't' -> "T";
            case 'u' -> "AH1";
            case 'v' -> "V";
            case 'w' -> "W";
            case 'x' -> "K S";
            case 'y' -> i == 0 ? "Y" : "IY0";
            case 'z' -> "Z";
            default -> "";
        };
    }
}
