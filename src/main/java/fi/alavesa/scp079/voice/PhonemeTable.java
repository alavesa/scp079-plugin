package fi.alavesa.scp079.voice;

import java.util.Set;

/**
 * How long each phoneme takes, in ticks (50 ms). The sounds themselves (tools/gen_voice.py) are a little longer than
 * their slot, so neighbours overlap slightly instead of leaving gaps - a crude stand-in for the way real speech
 * runs sounds together.
 */
final class PhonemeTable {

    static final Set<String> VOWELS = Set.of("AA", "AE", "AH", "AO", "EH", "ER", "IH", "IY", "UH", "UW");
    static final Set<String> DIPHTHONGS = Set.of("AW", "AY", "EY", "OW", "OY");
    /** Every phoneme there is a sound for (the 39 of ARPAbet). */
    static final Set<String> ALL = Set.of(
        "AA", "AE", "AH", "AO", "EH", "ER", "IH", "IY", "UH", "UW", "AW", "AY", "EY", "OW", "OY",
        "L", "R", "W", "Y", "M", "N", "NG", "S", "SH", "F", "TH", "Z", "ZH", "V", "DH", "HH",
        "P", "B", "T", "D", "K", "G", "CH", "JH");

    private PhonemeTable() { }

    /** @param stress 1 or 2 = stressed, 0 = unstressed, -1 = unknown (treated as stressed) */
    static int ticks(String phoneme, int stress) {
        if (VOWELS.contains(phoneme)) return stress == 0 ? 1 : 2;
        if (DIPHTHONGS.contains(phoneme)) return stress == 0 ? 2 : 3;
        return switch (phoneme) {
            case "S", "SH", "CH", "JH" -> 2;
            default -> 1;
        };
    }
}
