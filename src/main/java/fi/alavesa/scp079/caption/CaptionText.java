package fi.alavesa.scp079.caption;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lays one caption page out inside ONE text component (the name of a boss bar). Pure: no Bukkit.
 * <p>
 * How it works (font scp079:caption; shaders/include/scp079_text_*.glsl in the pack):
 * <ul>
 *   <li>every character of the font advances exactly {@link #ADVANCE} px (monospace by construction), and the
 *       font has signed spaces of +-2^n px, so the pen can be put anywhere;</li>
 *   <li>the component always ends with the pen back at 0: its width is 0, so the client's centring puts pen 0
 *       at the screen centre, and the text shader moves every glyph from there to above the hotbar;</li>
 *   <li>the text colour is the marker #FB79xx, whose blue byte tells the shader what the glyph is: a text
 *       line (counted from the bottom) or the background box and its size. Shadows are off, or a dark copy
 *       of every glyph would stay behind at the top of the screen.</li>
 * </ul>
 * The font is a terminal font: capitals, digits and punctuation. Lower case is shown as upper case.
 */
public final class CaptionText {

    public static final Key FONT = Key.key("scp079", "caption");
    public static final int ADVANCE = 6;
    /** Characters per line, and lines per page (the marker has two bits for the line). */
    public static final int COLUMNS = 40, MAX_LINES = 3;
    public static final int PALETTE_TEXT = 0, PALETTE_WHITE = 1, PALETTE_DIM = 2;

    private static final char CHAR_BACKGROUND = '';
    /** Advance of the background glyph: its 4 px bitmap plus the 1 px every bitmap glyph gets. */
    private static final int BACKGROUND_ADVANCE = 5;
    private static final int BOX_PADDING_X = 5;
    private static final char SPACE_POS = '', SPACE_NEG = '';
    /** What the font sheet has besides the ASCII range 0x20-0x5F. */
    private static final String EXTRA = "ÄÖÅÜÉ";

    private CaptionText() { }

    /**
     * @param lines    the whole page, top line first; at most {@link #MAX_LINES} lines of at most
     *                 {@link #COLUMNS} characters, already {@link #sanitize}d
     * @param revealed how many characters of the page are shown yet (the box has its final size from the start)
     */
    public static Component page(List<String> lines, int revealed, int palette) {
        int count = Math.min(lines.size(), MAX_LINES);
        int longest = 1;
        for (int i = 0; i < count; i++) longest = Math.max(longest, lines.get(i).length());

        TextComponent.Builder out = Component.text();
        int pen = 0;

        // the box: the shader ignores where this glyph is and draws the box centred, sized by the marker
        int halfWidthCode = Math.max(0, Math.min(15, (int) Math.ceil((longest * ADVANCE / 2.0 + BOX_PADDING_X) / 8.0) - 1));
        out.append(Component.text(String.valueOf(CHAR_BACKGROUND), TextColor.color(251, 121, 3 | ((count - 1) << 2) | (halfWidthCode << 4)))
            .font(FONT).shadowColor(ShadowColor.none()));
        pen += BACKGROUND_ADVANCE;

        int left = revealed;
        for (int i = 0; i < count && left > 0; i++) {
            String line = lines.get(i);
            String shown = line.substring(0, Math.min(left, line.length()));
            left -= shown.length();
            if (shown.isBlank()) continue;
            // every line is centred on the full line, so letters do not move while the rest is typed
            int x = -(line.length() * ADVANCE) / 2;
            int fromBottom = count - 1 - i;
            out.append(Component.text(shift(x - pen) + shown, TextColor.color(251, 121, fromBottom | ((palette & 7) << 4)))
                .font(FONT).shadowColor(ShadowColor.none()));
            pen = x + shown.length() * ADVANCE;
        }
        // back to pen 0: the whole component is then 0 px wide (see the class comment)
        out.append(Component.text(shift(-pen)).font(FONT));
        return out.build();
    }

    /** Signed spaces that move the pen by {@code px}. */
    static String shift(int px) {
        StringBuilder out = new StringBuilder();
        char base = px < 0 ? SPACE_NEG : SPACE_POS;
        int n = Math.abs(px);
        for (int bit = 9; bit >= 0; bit--) if ((n & (1 << bit)) != 0) out.append((char) (base + bit));
        return out.toString();
    }

    /**
     * What the caption font can show: capitals, digits, common punctuation and a few accented capitals. Lower
     * case becomes upper case; anything else becomes a near equivalent or '?'. So every character is exactly
     * one cell wide.
     */
    public static String sanitize(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char ch = upper.charAt(i);
            if (ch >= 0x20 && ch < 0x60 || EXTRA.indexOf(ch) >= 0) out.append(ch);
            else if (ch == '{') out.append('(');
            else if (ch == '}') out.append(')');
            else if (ch == '`' || ch == '´' || ch == '’' || ch == '‘') out.append('\'');
            else if (ch == '~' || ch == '–' || ch == '—') out.append('-');
            else if (ch == '“' || ch == '”') out.append('"');
            else if (ch == '…') out.append("...");
            else if (ch == '|') out.append('!');
            else if (Character.isWhitespace(ch)) out.append(' ');
            else if (!Character.isLowSurrogate(ch)) out.append('?');
        }
        return out.toString();
    }

    /** Breaks text into lines of at most {@link #COLUMNS} characters at spaces; a longer word is cut. */
    public static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        String rest = sanitize(text).strip().replaceAll(" {2,}", " ");
        while (!rest.isEmpty()) {
            if (rest.length() <= COLUMNS) {
                lines.add(rest);
                break;
            }
            int cut = rest.lastIndexOf(' ', COLUMNS);
            if (cut < COLUMNS / 2) cut = COLUMNS; // a very long word: cut it
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        return lines;
    }

    /** {@link #wrap}ped lines grouped into pages of {@link #MAX_LINES}. */
    public static List<List<String>> pages(String text) {
        List<String> lines = wrap(text);
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += MAX_LINES) pages.add(List.copyOf(lines.subList(i, Math.min(lines.size(), i + MAX_LINES))));
        return pages;
    }
}
