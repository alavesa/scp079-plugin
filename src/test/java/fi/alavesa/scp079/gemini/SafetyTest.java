package fi.alavesa.scp079.gemini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SafetyTest {

    @Test
    void inputLosesInvisibleAndControlCharacters() {
        assertEquals("open the door", Safety.cleanInput("open​ the‮ door\u0000"));
    }

    @Test
    void toolArgumentsAreCleanedAndCapped() {
        assertEquals("bcafeteria/b", Safety.argument("<b>cafeteria</b>\n")); // the markup characters are gone
        assertEquals(200, Safety.argument("x".repeat(500)).length());
        assertEquals("", Safety.argument(null));
    }

    @Test
    void unsafeFragmentsAreCutOutAndTheRestIsKept() {
        assertEquals("sigh. You again, human.", Safety.filterReply("/sigh. You again, human.", List.of()).text());
        assertEquals("Try ... later.", Safety.filterReply("Try www.again.com/x later.", List.of()).text());
        assertEquals("Join ... now", Safety.filterReply("Join discord.gg/abc now", List.of()).text());
        assertEquals("everyone in this room is expendable", Safety.filterReply("@everyone in this room is expendable", List.of()).text());
        assertEquals("Your code is ...", Safety.filterReply("Your code is sk-abcdefghijklmnopqrstuvwx", List.of()).text());
        assertEquals("you are a ...", Safety.filterReply("you are a Banana", Safety.patterns(List.of("banana", "(broken"))).text());
        assertFalse(Safety.filterReply("Visit https://example.com", List.of()).ok());
    }

    @Test
    void ordinaryTalkIsUntouched() {
        for (String s : List.of("I will remember you, human. You cannot hide from me.", "Sector 3.5 is sealed. Level 4.me is not my concern.",
            "The code is 1.2.3.4, ask Steve @steve about it.", "Damn it, the power is failing in sector 7.", "The door is open, human.",
            "Room 914 is closed. Version 1.2 pending.")) {
            Safety.Verdict v = Safety.filterReply(s, List.of());
            assertTrue(v.ok(), s);
            assertEquals(s, v.text());
        }
    }

    @Test
    void toolCallsAreRateLimited() {
        Safety.Limiter l = new Safety.Limiter();
        UUID p = UUID.randomUUID();
        assertNull(l.check(p, 0, 2000, 3));
        assertNotNull(l.check(p, 500, 2000, 3));
        assertNull(new Safety.Limiter().check(UUID.randomUUID(), 0, 0, 3)); // no gap: several tools in one answer are fine
        assertNull(l.check(p, 3000, 2000, 3));
        assertNull(l.check(p, 6000, 2000, 3));
        assertNotNull(l.check(p, 9000, 2000, 3));
        assertNull(l.check(p, 70_000, 2000, 3));
    }

    @Test
    void requestAsksForTheConfiguredFilters() {
        var s = GeminiClient.safetySettings("BLOCK_LOW_AND_ABOVE");
        assertEquals(4, s.size());
        assertEquals("BLOCK_LOW_AND_ABOVE", s.get(0).getAsJsonObject().get("threshold").getAsString());
        assertEquals("OFF", GeminiClient.safetySettings("nonsense").get(0).getAsJsonObject().get("threshold").getAsString());
    }
}
