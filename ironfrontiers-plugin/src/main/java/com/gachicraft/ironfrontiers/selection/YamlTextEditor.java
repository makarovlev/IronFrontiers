package com.gachicraft.ironfrontiers.selection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text-preserving editor for top-level entries in sectors.yml. It intentionally
 * avoids parse/save cycles so administrator comments and formatting survive.
 */
public final class YamlTextEditor {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]+");
    private YamlTextEditor() { }

    public static String appendSectorBlock(String source, String sectorId, String body) {
        validateId(sectorId);
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Sector YAML body is empty");
        String[] lines = source.split("\n", -1);
        int root = findRoot(lines, "sectors");
        int end = findSectionEnd(lines, root);
        for (int i = root + 1; i < end; i++) {
            String key = childKey(lines[i]);
            if (key != null && key.equalsIgnoreCase(sectorId)) {
                throw new IllegalArgumentException("Sector already exists: " + sectorId);
            }
        }
        List<String> result = new ArrayList<>(List.of(lines));
        int insertion = end;
        while (insertion > root + 1 && result.get(insertion - 1).isBlank()) insertion--;
        List<String> block = new ArrayList<>();
        block.add("  " + sectorId + ":");
        for (String line : body.split("\n", -1)) if (!line.isEmpty()) block.add("    " + line);
        if (insertion > 0 && !result.get(insertion - 1).isEmpty()) block.add(0, "");
        result.addAll(insertion, block);
        return String.join("\n", result);
    }

    public static String removeSectorBlock(String source, String sectorId) {
        validateId(sectorId);
        String[] lines = source.split("\n", -1);
        int root = findRoot(lines, "sectors");
        int end = findSectionEnd(lines, root);
        int start = -1;
        for (int i = root + 1; i < end; i++) {
            String key = childKey(lines[i]);
            if (key != null && key.equalsIgnoreCase(sectorId)) { start = i; break; }
        }
        if (start < 0) throw new IllegalArgumentException("Sector does not exist: " + sectorId);
        int blockEnd = start + 1;
        while (blockEnd < end) {
            // A sibling-level comment may be a note for the next sector; preserve it.
            if (lines[blockEnd].startsWith("  #") || childKey(lines[blockEnd]) != null) break;
            blockEnd++;
        }
        List<String> result = new ArrayList<>(List.of(lines));
        result.subList(start, blockEnd).clear();
        return String.join("\n", result);
    }

    private static String childKey(String line) {
        if (!line.startsWith("  ") || line.startsWith("   ") || line.stripLeading().startsWith("#")) return null;
        String trimmed = line.substring(2);
        int colon = trimmed.indexOf(':');
        return colon <= 0 ? null : trimmed.substring(0, colon).trim();
    }

    private static int findRoot(String[] lines, String rootKey) {
        for (int i = 0; i < lines.length; i++) if (lines[i].equals(rootKey + ":")) return i;
        throw new IllegalArgumentException("Missing top-level YAML section: " + rootKey);
    }

    private static int findSectionEnd(String[] lines, int root) {
        for (int i = root + 1; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) return i;
        }
        return lines.length;
    }

    private static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid sector id: " + id);
    }
}
