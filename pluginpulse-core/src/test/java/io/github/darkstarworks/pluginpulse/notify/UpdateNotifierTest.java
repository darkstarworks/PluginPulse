package io.github.darkstarworks.pluginpulse.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The plain-text fallback used on servers without Adventure (Spigot).
 */
class UpdateNotifierTest {

    @Test
    void stripsMiniMessageTagsToPlainText() {
        assertEquals("[TCP]", UpdateNotifier.stripTags("<gold>[TCP]</gold>"));
        assertEquals("Update available: 1.2.0",
                UpdateNotifier.stripTags("<green>Update available:</green> <yellow>1.2.0</yellow>"));
    }

    @Test
    void newlineTagBecomesSpaceAndLabelsSurvive() {
        String rich = "<gray>Line one<newline><click:open_url:'https://x'><aqua>[Download]</aqua></click>";
        String plain = UpdateNotifier.stripTags(rich);
        assertFalse(plain.contains("<"));
        // The visible label survives; the tags (including the URL inside click) are gone.
        assertEquals("Line one [Download]", plain);
    }
}
