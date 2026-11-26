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
        assertEquals(60, Safety.argument("x".repeat(200)).length());
        assertEquals("", Safety.argument(null));
    }

    @Test
    void unsafeAnswersAreCaught() {
        assertFalse(Safety.filterReply("Join discord.gg/abc now", List.of()).ok());
        assertFalse(Safety.filterReply("Visit www.example.com", List.of()).ok());
        assertFalse(Safety.filterReply("Server is at 192.168.1.5", List.of()).ok());
        assertFalse(Safety.filterReply("/op Steve", List.of()).ok());
        assertFalse(Safety.filterReply("hello @everyone", List.of()).ok());
        assertFalse(Safety.filterReply("key AIzaSyA1234567890abcdefghijklmn", List.of()).ok());
        assertFalse(Safety.filterReply("you are a Banana", Safety.patterns(List.of("banana", "(broken"))).ok());
        assertTrue(Safety.filterReply("The door is open, human.", List.of()).ok());
        assertTrue(Safety.filterReply("Room 914 is closed. Version 1.2 pending.", List.of()).ok());
    }

    @Test
    void toolCallsAreRateLimited() {
        Safety.Limiter l = new Safety.Limiter();
        UUID p = UUID.randomUUID();
        assertNull(l.check(p, 0, 2000, 3));
        assertNotNull(l.check(p, 500, 2000, 3));
        assertNull(l.check(p, 3000, 2000, 3));
        assertNull(l.check(p, 6000, 2000, 3));
        assertNotNull(l.check(p, 9000, 2000, 3));
        assertNull(l.check(p, 70_000, 2000, 3));
    }

    @Test
    void requestAsksForTheStrictestFilters() {
        var s = GeminiClient.safetySettings("BLOCK_LOW_AND_ABOVE");
        assertEquals(4, s.size());
        assertEquals("BLOCK_LOW_AND_ABOVE", s.get(0).getAsJsonObject().get("threshold").getAsString());
        assertEquals("BLOCK_LOW_AND_ABOVE", GeminiClient.safetySettings("nonsense").get(0).getAsJsonObject().get("threshold").getAsString());
    }
}
