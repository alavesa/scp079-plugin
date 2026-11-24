package fi.alavesa.scp079.voice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PronouncerTest {

    private static String phones(List<Pronouncer.Step> steps) {
        return steps.stream().map(s -> s.phoneme() == null ? "|" + s.ticks() : s.phoneme()).collect(Collectors.joining(" "));
    }

    @Test
    void dictionaryWordsAndPauses() {
        Pronouncer p = Pronouncer.of("hello HH AH0 L OW1", "human HH Y UW1 M AH0 N");
        List<Pronouncer.Step> steps = p.steps("Hello, human.");
        assertEquals("hh ah l ow |4 hh y uw m ah n", phones(steps));
        // an unstressed vowel is short, a stressed diphthong long
        assertEquals(1, steps.get(1).ticks());
        assertEquals(3, steps.get(3).ticks());
    }

    @Test
    void sentencePauseBeatsWordGapAndNothingTrails() {
        Pronouncer p = Pronouncer.of("no N OW1", "yes Y EH1 S");
        assertEquals("n ow |7 y eh s", phones(p.steps("No. Yes!")));
    }

    @Test
    void digitsAreReadOneByOne() {
        Pronouncer p = Pronouncer.of("zero Z IH1 R OW0", "seven S EH1 V AH0 N", "nine N AY1 N");
        assertEquals("z ih r ow |1 s eh v ah n |1 n ay n", phones(p.steps("079")));
    }

    @Test
    void shortCapitalWordsAreSpelled() {
        Pronouncer p = Pronouncer.of();
        assertEquals("eh s", phones(p.steps("S")));
        assertEquals("eh s s iy p iy", phones(p.steps("SCP")));
    }

    @Test
    void unknownWordsUseLetterRules() {
        Pronouncer p = Pronouncer.of();
        assertEquals("sh ih p", phones(p.steps("ship")));
        assertEquals("ch ay", phones(p.steps("chigh")));
        assertFalse(p.steps("zorblax").isEmpty());
    }

    @Test
    void everyPhonemeHasASound() {
        Pronouncer p = Pronouncer.of();
        for (String word : List.of("quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog", "knight", "phone", "xylophone")) {
            for (Pronouncer.Step step : p.steps(word)) {
                if (step.phoneme() != null) assertTrue(PhonemeTable.ALL.contains(step.phoneme().toUpperCase()), word + ": " + step.phoneme());
            }
        }
    }

    @Test
    void bundledDictionaryLoads() throws Exception {
        Pronouncer p = Pronouncer.load();
        assertNotNull(p.lookup("foundation"));
        assertNotNull(p.lookup("containment"));
        assertNull(p.lookup("zzzzqx"));
        List<Pronouncer.Step> steps = p.steps("I see you, human. The foundation cannot keep me here forever.");
        for (Pronouncer.Step step : steps) {
            if (step.phoneme() != null) assertTrue(PhonemeTable.ALL.contains(step.phoneme().toUpperCase()), step.phoneme());
        }
        int ticks = steps.stream().mapToInt(Pronouncer.Step::ticks).sum();
        // about 2.5 words a second, like slow speech
        assertTrue(ticks > 60 && ticks < 120, "ticks " + ticks);
    }
}
