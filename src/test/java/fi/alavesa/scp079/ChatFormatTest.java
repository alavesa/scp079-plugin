package fi.alavesa.scp079;

import fi.alavesa.scp079.config.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatFormatTest {

    /** The default formats in config.yml must print literal angle brackets, and never parse tags in what was said. */
    @Test
    void defaultFormatsRenderAsIntended() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
            new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        var plain = PlainTextComponentSerializer.plainText();
        var line = Messages.format(config.getString("chat.reply-format"), Placeholder.unparsed("message", "Hello <red>human"));
        assertEquals("<SCP-079> Hello <red>human", plain.serialize(line));
        // plain, like a player's chat line: no colour anywhere
        assertEquals(null, line.color());
        assertEquals(true, line.children().stream().allMatch(child -> child.color() == null));
    }
}
