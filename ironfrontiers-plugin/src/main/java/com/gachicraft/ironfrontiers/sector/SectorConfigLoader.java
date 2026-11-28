package com.gachicraft.ironfrontiers.sector;

import com.gachicraft.ironfrontiers.geometry.AxisAlignedPolygon;
import com.gachicraft.ironfrontiers.geometry.GridPoint;
import com.gachicraft.ironfrontiers.geometry.RasterizedSector;
import com.gachicraft.ironfrontiers.geometry.SectorGeometryIndex;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bukkit-facing parser for sectors.yml. Every sector is validated independently:
 * one malformed entry is logged and skipped without disabling the plugin.
 */
public final class SectorConfigLoader {
    private static final Pattern SECTOR_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern COORDINATE = Pattern.compile("\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*");

    private final Server server;
    private final Logger logger;

    public SectorConfigLoader(Server server, Logger logger) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LoadResult load(YamlConfiguration source, YamlConfiguration mainConfig) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mainConfig, "mainConfig");

        ConfigurationSection root = source.getConfigurationSection("sectors");
        if (root == null) {
            logger.warning("sectors.yml: missing 'sectors' section; no sectors were loaded");
            return new LoadResult(new SectorRegistry(List.of(),
                    new SectorGeometryIndex.Builder().build()), List.of("sectors section is missing"));
        }

        Set<String> warWorlds = new HashSet<>();
        for (String worldName : mainConfig.getStringList("war_worlds")) {
            warWorlds.add(worldName.toLowerCase(Locale.ROOT));
        }

        SectorDefaults defaults = readDefaults(mainConfig);
        SectorGeometryIndex.Builder indexBuilder = new SectorGeometryIndex.Builder();
        List<SectorDefinition> valid = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (String id : root.getKeys(false)) {
            String normalizedId = id.toLowerCase(Locale.ROOT);
            if (!SECTOR_ID.matcher(id).matches()) {
                warn(warnings, "sectors." + id + ": invalid id; expected [A-Za-z0-9_-]+");
                continue;
            }
            if (!seenIds.add(normalizedId)) {
                warn(warnings, "sectors." + id + ": duplicate id ignoring case; sector skipped");
                continue;
            }

            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                warn(warnings, "sectors." + id + ": expected a YAML section");
                continue;
            }

            try {
                SectorDefinition definition = parseSector(id, section, defaults, warWorlds);
                try {
                    indexBuilder.add(definition.worldId(), definition.geometry());
                } catch (SectorGeometryIndex.OverlapException exception) {
                    throw new SectorValidationException(exception.getMessage());
                }
                valid.add(definition);
            } catch (IllegalArgumentException exception) {
                warn(warnings, "sectors." + id + ": " + exception.getMessage() + "; sector disabled");
            }
        }

        return new LoadResult(new SectorRegistry(valid, indexBuilder.build()), warnings);
    }

    private SectorDefinition parseSector(String id, ConfigurationSection section,
                                          SectorDefaults defaults, Set<String> warWorlds) {
        String worldName = requiredString(section, "world");
        if (!warWorlds.contains(worldName.toLowerCase(Locale.ROOT))) {
            throw new SectorValidationException("world '" + worldName + "' is not listed in config.war_worlds");
        }

        World world = server.getWorld(worldName);
        if (world == null) {
            throw new SectorValidationException("world '" + worldName + "' is not loaded");
        }

        List<GridPoint> vertices = parseCoordinates(section.getList("coords"));
        for (GridPoint vertex : vertices) {
            Location columnCenter = new Location(world, vertex.x() + 0.5,
                    world.getMinHeight(), vertex.z() + 0.5);
            if (!world.getWorldBorder().isInside(columnCenter)) {
                throw new SectorValidationException("vertex " + vertex.x() + "," + vertex.z()
                        + " is outside WorldBorder");
            }
        }

        RasterizedSector geometry;
        try {
            geometry = AxisAlignedPolygon.rasterize(vertices);
        } catch (IllegalArgumentException exception) {
            throw new SectorValidationException(exception.getMessage());
        }

        String displayName = section.getString("display_name", id);
        if (displayName == null || displayName.isBlank()) {
            throw new SectorValidationException("display_name must not be blank");
        }

        int capture = decimalTenths(section, "kpm_capture", defaults.kpmCaptureTenths());
        int held = decimalTenths(section, "kpm_held", defaults.kpmHeldTenths());
        if (held > capture) {
            logger.warning("sectors." + id + ": kpm_held is greater than kpm_capture");
        }

        int safeTime = positiveSeconds(section, "safe_time", defaults.safeTimeSeconds());
        int destructionTime = positiveSeconds(section, "destruction_time", defaults.destructionTimeSeconds());
        int occupationTime = nonNegativeSeconds(section, "occupation_time", defaults.occupationTimeSeconds());
        boolean infiniteSafe = section.getBoolean("infinite_safe", false);
        List<SpawnAnchor> anchors = parseAnchors(id, section.getConfigurationSection("mobs"));

        return new SectorDefinition(id, displayName, world.getName(), world.getUID(), vertices,
                geometry, capture, held, safeTime, destructionTime, occupationTime,
                infiniteSafe, anchors);
    }

    private List<GridPoint> parseCoordinates(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new SectorValidationException("coords must contain at least one x,z vertex");
        }
        List<GridPoint> points = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Object value = raw.get(index);
            if (!(value instanceof String text)) {
                throw new SectorValidationException("coords[" + index + "] must be a quoted 'x,z' string");
            }
            Matcher matcher = COORDINATE.matcher(text);
            if (!matcher.matches()) {
                throw new SectorValidationException("coords[" + index + "] must use the 'x,z' format");
            }
            try {
                points.add(new GridPoint(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))));
            } catch (NumberFormatException exception) {
                throw new SectorValidationException("coords[" + index + "] is outside the integer coordinate range");
            }
        }
        return List.copyOf(points);
    }

    private List<SpawnAnchor> parseAnchors(String sectorId, ConfigurationSection mobs) {
        if (mobs == null) {
            return List.of();
        }
        List<SpawnAnchor> result = new ArrayList<>();
        for (String slotId : mobs.getKeys(false)) {
            String path = "sectors." + sectorId + ".mobs." + slotId;
            ConfigurationSection anchor = mobs.getConfigurationSection(slotId);
            if (anchor == null) {
                logger.warning(path + ": expected section; spawn point skipped");
                continue;
            }

            try {
                String clusterId = requiredString(anchor, "cluster");
                double x = finiteNumber(anchor, "x");
                double y = finiteNumber(anchor, "y");
                double z = finiteNumber(anchor, "z");
                int respawn = nonNegativeSeconds(anchor, "respawn", 0);
                result.add(new SpawnAnchor(slotId, clusterId, x, y, z, respawn));
            } catch (IllegalArgumentException exception) {
                logger.warning(path + ": " + exception.getMessage() + "; spawn point skipped");
            }
        }
        return List.copyOf(result);
    }

    private SectorDefaults readDefaults(YamlConfiguration config) {
        int capture = configTenths(config, "sector_defaults.kpm_capture", 45.0);
        int held = configTenths(config, "sector_defaults.kpm_held", 23.0);
        int safe = configPositiveSeconds(config, "sector_defaults.safe_time_seconds", 3600);
        int destruction = configPositiveSeconds(config, "sector_defaults.destruction_time_seconds", 300);
        int occupation = configNonNegativeSeconds(config, "sector_defaults.occupation_time_seconds", 300);
        return new SectorDefaults(capture, held, safe, destruction, occupation);
    }

    private int decimalTenths(ConfigurationSection section, String key, int fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new SectorValidationException(key + " must be a number");
        }
        return toTenths(number, key);
    }

    private int configTenths(YamlConfiguration config, String path, double fallback) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            logger.warning(path + ": expected a number; default " + fallback + " is used");
            return toTenths(BigDecimal.valueOf(fallback), path);
        }
        try {
            return toTenths(number, path);
        } catch (SectorValidationException exception) {
            logger.warning(path + ": " + exception.getMessage() + "; default " + fallback + " is used");
            return toTenths(BigDecimal.valueOf(fallback), path);
        }
    }

    private int toTenths(Number number, String key) {
        try {
            BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
            if (decimal.signum() <= 0 || decimal.scale() > 1) {
                throw new SectorValidationException(key + " must be > 0 and have at most one decimal place");
            }
            return decimal.movePointRight(1).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new SectorValidationException(key + " is outside the supported numeric range");
        }
    }

    private int positiveSeconds(ConfigurationSection section, String key, int fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        int value = section.getInt(key, Integer.MIN_VALUE);
        if (value <= 0) {
            throw new SectorValidationException(key + " must be a positive number of seconds");
        }
        return value;
    }

    private int nonNegativeSeconds(ConfigurationSection section, String key, int fallback) {
        if (!section.contains(key)) {
            return fallback;
        }
        int value = section.getInt(key, Integer.MIN_VALUE);
        if (value < 0) {
            throw new SectorValidationException(key + " must not be negative");
        }
        return value;
    }

    private int configPositiveSeconds(YamlConfiguration config, String path, int fallback) {
        int value = config.getInt(path, fallback);
        if (value <= 0) {
            logger.warning(path + ": value must be positive; default " + fallback + " is used");
            return fallback;
        }
        return value;
    }

    private int configNonNegativeSeconds(YamlConfiguration config, String path, int fallback) {
        int value = config.getInt(path, fallback);
        if (value < 0) {
            logger.warning(path + ": value must be non-negative; default " + fallback + " is used");
            return fallback;
        }
        return value;
    }

    private double finiteNumber(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new SectorValidationException(key + " must be numeric");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new SectorValidationException(key + " must be finite");
        }
        return result;
    }

    private static String requiredString(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new SectorValidationException(key + " must be a non-empty string");
        }
        return value.trim();
    }

    private void warn(List<String> warnings, String message) {
        warnings.add(message);
        logger.warning(message);
    }

    public record LoadResult(SectorRegistry registry, List<String> warnings) {
        public LoadResult {
            Objects.requireNonNull(registry);
            warnings = List.copyOf(warnings);
        }
    }

    private record SectorDefaults(int kpmCaptureTenths, int kpmHeldTenths,
                                  int safeTimeSeconds, int destructionTimeSeconds,
                                  int occupationTimeSeconds) { }

    private static final class SectorValidationException extends IllegalArgumentException {
        private SectorValidationException(String message) {
            super(message);
        }
    }
}
