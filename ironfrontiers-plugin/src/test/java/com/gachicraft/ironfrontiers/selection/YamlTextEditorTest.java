package com.gachicraft.ironfrontiers.selection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YamlTextEditorTest {
    private static final String SOURCE = "# Header comment\nsectors:\n  aa01:\n    display_name: AA01\n    coords: [\"0,0\"]\n  # Keep this admin note\n  bb02:\n    display_name: BB02\nother_section:\n  value: true\n";

    @Test
    void appendPreservesExistingCommentsAndOtherSections() {
        String result = YamlTextEditor.appendSectorBlock(SOURCE, "cc03", "display_name: CC03\ncoords: [\"1,1\"]");
        assertTrue(result.startsWith("# Header comment"));
        assertTrue(result.contains("  # Keep this admin note"));
        assertTrue(result.contains("  cc03:\n    display_name: CC03"));
        assertTrue(result.contains("other_section:\n  value: true"));
    }

    @Test
    void duplicateIdsAreRejectedCaseInsensitively() {
        assertThrows(IllegalArgumentException.class,
                () -> YamlTextEditor.appendSectorBlock(SOURCE, "AA01", "display_name: duplicate"));
    }

    @Test
    void removesOnlyRequestedEntryAndKeepsSiblingComment() {
        String result = YamlTextEditor.removeSectorBlock(SOURCE, "aa01");
        assertFalse(result.contains("  aa01:"));
        assertTrue(result.contains("  # Keep this admin note"));
        assertTrue(result.contains("  bb02:"));
        assertTrue(result.contains("other_section:\n  value: true"));
    }

    @Test
    void validatesSectorIds() {
        assertThrows(IllegalArgumentException.class,
                () -> YamlTextEditor.appendSectorBlock(SOURCE, "bad id", "display_name: invalid"));
    }
}
