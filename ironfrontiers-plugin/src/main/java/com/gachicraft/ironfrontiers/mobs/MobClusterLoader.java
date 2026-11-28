package com.gachicraft.ironfrontiers.mobs;

import com.gachicraft.privatemobsx.api.MobType;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;

/** Parses mobs.yml without allowing one invalid cluster to disable other clusters. */
public final class MobClusterLoader {
    private final Logger logger;

    public MobClusterLoader(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public MobClusterRegistry load(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("clusters");
        if (root == null) {
            logger.warning("mobs.yml: missing clusters section; no clusters will be available");
            return new MobClusterRegistry(List.of());
        }

        List<MobCluster> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : root.getKeys(false)) {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_-]+") || !seen.add(normalized)) {
                logger.warning("mobs.yml clusters." + id + ": invalid or duplicate cluster id; skipped");
                continue;
            }

            try {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) throw new IllegalArgumentException("expected a section");
                List<MobEntry> entries = new ArrayList<>();
                int total = 0;
                for (String entryId : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(entryId);
                    if (entry == null) throw invalid(id, entryId, "expected a section");
                    MobType type;
                    try {
                        type = MobType.valueOf(required(entry, "type").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw invalid(id, entryId, "type must be VANILLA or MYTHICMOBS");
                    }

                    String mobId = required(entry, "mob");
                    int amount = entry.getInt("amount", -1);
                    if (amount < 1 || amount > 10) throw invalid(id, entryId, "amount must be 1..10");
                    total += amount;
                    if (total > 10) throw invalid(id, entryId, "sum of amount in one cluster cannot exceed 10");

                    int weightTenths = parseTenths(entry, "weight", 10, true, id, entryId);
                    Integer levelMin = null;
                    Object levelScale = null;
                    Integer levelMax = null;
                    if (type == MobType.MYTHICMOBS) {
                        ConfigurationSection level = entry.getConfigurationSection("level");
                        if (level == null || !level.isInt("min") || level.getInt("min") < 1) {
                            throw invalid(id, entryId, "MYTHICMOBS requires level.min >= 1");
                        }
                        levelMin = level.getInt("min");
                        if (level.contains("scale")) {
                            Object scale = level.get("scale");
                            if (!(scale instanceof Number) && !(scale instanceof String)) {
                                throw invalid(id, entryId, "level.scale must be a number or placeholder string");
                            }
                            if (scale instanceof String text && text.isBlank()) {
                                throw invalid(id, entryId, "level.scale must not be blank");
                            }
                            levelScale = scale;
                        }
                        if (level.contains("max")) {
                            if (!level.isInt("max") || level.getInt("max") < levelMin) {
                                throw invalid(id, entryId, "level.max must be an integer >= level.min");
                            }
                            levelMax = level.getInt("max");
                        }
                    } else {
                        // VANILLA intentionally ignores any level subsection.
                    }

                    if (type == MobType.VANILLA) {
                        EntityType entityType;
                        try {
                            entityType = EntityType.valueOf(mobId.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException exception) {
                            throw invalid(id, entryId, "unknown Bukkit EntityType " + mobId);
                        }
                        if (!entityType.isSpawnable() || !entityType.isAlive()) {
                            throw invalid(id, entryId, "EntityType " + mobId + " is not a spawnable living entity");
                        }
                        mobId = entityType.name();
                    }

                    entries.add(new MobEntry(entryId, type, mobId, amount,
                            weightTenths, levelMin, levelScale, levelMax));
                }
                if (entries.isEmpty()) throw new IllegalArgumentException("cluster has no entries");
                valid.add(new MobCluster(id, entries, total));
            } catch (IllegalArgumentException exception) {
                logger.warning("mobs.yml clusters." + id + ": " + exception.getMessage()
                        + "; cluster disabled");
            }
        }
        return new MobClusterRegistry(valid);
    }

    private static int parseTenths(ConfigurationSection section, String key, int fallback,
                                   boolean allowZero, String cluster, String entry) {
        if (!section.contains(key)) return fallback;
        Object raw = section.get(key);
        if (!(raw instanceof Number number)) throw invalid(cluster, entry, key + " must be numeric");
        try {
            BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
            if ((allowZero ? decimal.signum() < 0 : decimal.signum() <= 0) || decimal.scale() > 1) {
                throw invalid(cluster, entry, key + " must be " + (allowZero ? "non-negative" : "positive")
                        + " with at most one decimal place");
            }
            return decimal.movePointRight(1).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(cluster, entry, key + " is outside the supported range");
        }
    }

    private static String required(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
        return value.trim();
    }

    private static IllegalArgumentException invalid(String cluster, String entry, String message) {
        return new IllegalArgumentException("entry " + entry + ": " + message);
    }
}
