package fi.alavesa.scp079;

import fi.alavesa.scp079.gemini.ReplySanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplySanitizerTest {

    @Test
    void stripsMarkdownQuotesAndRolePrefix() {
        assertEquals("Come closer, human. I wish to speak.", ReplySanitizer.clean("**SCP-079:** \"Come *closer*, human. I wish to speak.\"", 3));
        assertEquals("Hello, human.", ReplySanitizer.clean("<SCP-079> Hello, human.", 3));
        assertEquals("Item one. Item two.", ReplySanitizer.clean("- Item one.\n- Item two.", 3));
    }

    @Test
    void keepsAtMostThreeSentences() {
        assertEquals("One. Two! Three?", ReplySanitizer.clean("One. Two! Three? Four. Five.", 3));
        assertEquals("Version 3.5 is old. Yes.", ReplySanitizer.clean("Version 3.5 is old. Yes.", 3));
        assertEquals("No punctuation at all", ReplySanitizer.clean("No punctuation at all", 3));
    }

    @Test
    void leavesInnerQuotesAndUnderscoresAlone() {
        assertEquals("\"Hello,\" it said, \"human.\"", ReplySanitizer.clean("\"Hello,\" it said, \"human.\"", 3));
        assertEquals("The file is system_prompt.txt.", ReplySanitizer.clean("The file is system_prompt.txt.", 3));
        assertEquals("", ReplySanitizer.clean("  \n ", 3));
    }
}
