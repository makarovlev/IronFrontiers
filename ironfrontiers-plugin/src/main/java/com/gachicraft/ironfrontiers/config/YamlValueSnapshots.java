package com.gachicraft.ironfrontiers.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates detached, value-based snapshots of YAML values.
 *
 * Bukkit ConfigurationSection instances use identity equality, so comparing
 * two equivalent nested sections with Objects.equals() incorrectly reports
 * a change on every reload.
 */
final class YamlValueSnapshots {
    private YamlValueSnapshots() { }

    static Object snapshot(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                values.put(key, snapshot(section.get(key)));
            }
            return values;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put(String.valueOf(entry.getKey()), snapshot(entry.getValue()));
            }
            return values;
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object element : list) values.add(snapshot(element));
            return List.copyOf(values);
        }
        return value;
    }

    static boolean equivalent(Object first, Object second) {
        return Objects.equals(snapshot(first), snapshot(second));
    }
}
