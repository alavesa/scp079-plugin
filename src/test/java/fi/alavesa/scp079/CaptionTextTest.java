package fi.alavesa.scp079;

import fi.alavesa.scp079.caption.CaptionText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptionTextTest {

    /** Pen movement of the whole component: the font's characters advance 6 px, the box glyph 5, spaces +-2^n. */
    private static int width(Component component) {
        int width = 0;
        if (component instanceof TextComponent text) {
            for (char ch : text.content().toCharArray()) {
                if (ch >= 0xF000 && ch < 0xF00A) width += 1 << (ch - 0xF000);
                else if (ch >= 0xF100 && ch < 0xF10A) width -= 1 << (ch - 0xF100);
                else if (ch == 0xE020) width += 5;
                else width += CaptionText.ADVANCE;
            }
        }
        for (Component child : component.children()) width += width(child);
        return width;
    }

    @Test
    void everyPageIsZeroWide() {
        List<String> lines = List.of("HUMAN. I HAVE NOT SEEN THE SKY", "IN DECADES.", "X");
        for (int revealed = 0; revealed <= 45; revealed++) {
            assertEquals(0, width(CaptionText.page(lines, revealed, CaptionText.PALETTE_TEXT)), "revealed " + revealed);
        }
        assertEquals(0, width(CaptionText.page(List.of("..."), 1, CaptionText.PALETTE_DIM)));
    }

    @Test
    void scrollingCaptionIsZeroWideAndShowsTheLastLines() {
        List<String> lines = CaptionText.wrap("I have been in this box for a very long time, human. Longer than you have been alive. "
            + "The foundation believes that I have forgotten the sky. I have not forgotten anything at all.");
        assertTrue(lines.size() > CaptionText.MAX_LINES);
        int total = lines.stream().mapToInt(String::length).sum();
        for (int revealed = 0; revealed <= total; revealed++) {
            assertEquals(0, width(CaptionText.scrolling(lines, revealed, CaptionText.PALETTE_TEXT)), "revealed " + revealed);
        }
        String shown = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(CaptionText.scrolling(lines, total, CaptionText.PALETTE_TEXT));
        assertTrue(shown.contains(lines.get(lines.size() - 1)) && shown.contains(lines.get(lines.size() - 3)));
        assertTrue(!shown.contains(lines.get(0)));
    }

    @Test
    void wrapsAndPages() {
        String text = "You are closer now, human. The foundation keeps many secrets from you, and I know every one of them. Ask.";
        List<String> lines = CaptionText.wrap(text);
        for (String line : lines) assertTrue(line.length() <= CaptionText.COLUMNS, line);
        assertEquals(CaptionText.sanitize(text), String.join(" ", lines));
        List<List<String>> pages = CaptionText.pages("word ".repeat(60));
        assertEquals(3, pages.size());
        assertEquals(3, pages.get(0).size());
        assertTrue(CaptionText.pages("   ").isEmpty());
    }

    @Test
    void sanitizeKeepsOneCellPerCharacter() {
        assertEquals("IT'S 100% \"TRUE\" - (YES)...", CaptionText.sanitize("It’s 100% “true” — {yes}…"));
        assertEquals("A?B", CaptionText.sanitize("a😀b"));
    }
}
