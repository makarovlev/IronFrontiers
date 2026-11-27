package com.gachicraft.ironfrontiers.config;

import com.gachicraft.ironfrontiers.integration.MythicBridge;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

/** Loads, creates and validates the six documented YAML files. */
public final class ConfigManager {
    private static final List<String> RESOURCE_FILES = List.of(
            "config.yml", "messages.yml", "sectors.yml", "mobs.yml", "blueprints.yml", "menus.yml");
    private static final List<String> RESTART_PREFIXES = List.of("database.", "multiserver.", "transform.", "stronghold.npc_parking.");
    private static final Set<String> RESTART_EXACT = Set.of("war_worlds");
    private static final Set<String> RELOADABLE_TRANSFORM_KEYS = Set.of("transform.tick_budget_ms", "transform.min_tps");
    private final JavaPlugin plugin;
    private final MythicBridge mythicBridge;
    private YamlConfiguration config, messages, sectors, mobs, blueprints, menus;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.mythicBridge = new MythicBridge(plugin.getServer().getPluginManager());
    }

    public ReloadReport loadInitial() {
        createResources();
        List<String> warnings = new ArrayList<>();
        config = load("config.yml", warnings);
        messages = load("messages.yml", warnings);
        sectors = load("sectors.yml", warnings);
        mobs = load("mobs.yml", warnings);
        blueprints = load("blueprints.yml", warnings);
        menus = load("menus.yml", warnings);
        validateConfig(config, warnings);
        validateMessages(messages, warnings);
        validateMobs(mobs, warnings);
        logWarnings(warnings);
        return new ReloadReport(List.of(), warnings);
    }

    public ReloadReport reload() {
        List<String> warnings = new ArrayList<>();
        YamlConfiguration nextConfig = load("config.yml", warnings);
        YamlConfiguration nextMessages = load("messages.yml", warnings);
        YamlConfiguration nextSectors = load("sectors.yml", warnings);
        YamlConfiguration nextMobs = load("mobs.yml", warnings);
        YamlConfiguration nextBlueprints = load("blueprints.yml", warnings);
        YamlConfiguration nextMenus = load("menus.yml", warnings);
        validateConfig(nextConfig, warnings);
        validateMessages(nextMessages, warnings);
        validateMobs(nextMobs, warnings);
        List<String> restart = new ArrayList<>();
        preserveRestartValues(config, nextConfig, restart);
        preserveSectorRestartValues(sectors, nextSectors, restart);
        config = nextConfig; messages = nextMessages; sectors = nextSectors;
        mobs = nextMobs; blueprints = nextBlueprints; menus = nextMenus;
        logWarnings(warnings);
        return new ReloadReport(restart, warnings);
    }

    private void createResources() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Cannot create plugin data directory");
        }
        for (String resource : RESOURCE_FILES) if (!new File(plugin.getDataFolder(), resource).exists()) {
            try { plugin.saveResource(resource, false); }
            catch (IllegalArgumentException exception) { throw new IllegalStateException("Missing embedded resource " + resource, exception); }
        }
        for (String directory : List.of("blueprints", "data")) {
            File folder = new File(plugin.getDataFolder(), directory);
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create " + folder);
        }
    }

    private YamlConfiguration load(String name, List<String> warnings) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(new File(plugin.getDataFolder(), name)); }
        catch (Exception exception) { warnings.add(name + ": " + exception.getMessage() + "; embedded defaults are being used"); }
        try (var stream = plugin.getResource(name)) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                yaml.options().copyDefaults(true);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Cannot load embedded defaults for " + name, exception);
        }
        return yaml;
    }

    private void validateConfig(YamlConfiguration y, List<String> w) {
        validateInt(y,"database.port",1,65535,3306,w); validateInt(y,"database.pool_size",1,64,6,w);
        validateInt(y,"kpm.update_interval_seconds",1,60,1,w); validateInt(y,"kpm.window_seconds",1,3600,60,w);
        validateInt(y,"spawn.max_alive_clusters_per_sector",1,100,3,w); validateInt(y,"spawn.max_alive_clusters_total",1,200,6,w);
        validateInt(y,"spawn.despawn_spread_ticks",5,10,8,w); validateInt(y,"transform.scan_threads",1,32,2,w);
        validateInt(y,"stronghold.max_blocks_per_tick",1,100000,2000,w); validateInt(y,"placeholders.kpm_decimals",0,3,1,w);
        validateDouble(y,"sector_defaults.kpm_capture",0.1,100000,45,w); validateDouble(y,"sector_defaults.kpm_held",0.1,100000,23,w);
        validateDouble(y,"transform.tick_budget_ms",0.1,50,2,w); validateDouble(y,"transform.min_tps",1,20,18,w);
        validateString(y,"database.host","127.0.0.1",w); validateString(y,"database.name","ironfrontiers",w);
        validateString(y,"database.user","root",w); validateString(y,"database.table_prefix","if_",w);
        String prefix=y.getString("database.table_prefix","if_");
        if(prefix==null||!prefix.matches("[A-Za-z0-9_]{1,24}")){w.add("database.table_prefix: invalid SQL identifier; using if_");y.set("database.table_prefix","if_");}
        validateString(y,"multiserver.server_id","server_1",w);
        if(!y.isList("war_worlds")||y.getStringList("war_worlds").isEmpty()){w.add("war_worlds: expected non-empty list; using [world_war]");y.set("war_worlds",List.of("world_war"));}
        String direction=y.getString("transform.direction","MIN_X_TO_MAX_X");
        if(!Set.of("MIN_X_TO_MAX_X","MAX_X_TO_MIN_X","MIN_Z_TO_MAX_Z","MAX_Z_TO_MIN_Z").contains(direction)){w.add("transform.direction: unknown value; using MIN_X_TO_MAX_X");y.set("transform.direction","MIN_X_TO_MAX_X");}
    }

    private void validateMessages(YamlConfiguration yaml, List<String> warnings) {
        for (String path : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(path)) {
                continue;
            }
            if (!yaml.isString(path)) {
                warnings.add("messages." + path + ": expected string; embedded default restored");
                Object defaultValue = yaml.getDefaults() == null ? null : yaml.getDefaults().get(path);
                yaml.set(path, defaultValue instanceof String ? defaultValue : "");
            }
        }

        for (String required : List.of("general.prefix", "general.no_permission",
                "general.unknown_subcommand", "general.reload_done",
                "general.reload_restart_required")) {
            if (!yaml.isString(required)) {
                warnings.add("messages." + required + ": required message is missing or invalid");
            }
        }
    }

    private void validateMobs(YamlConfiguration yaml, List<String> warnings) {
        ConfigurationSection clusters = yaml.getConfigurationSection("clusters");
        if (clusters == null) {
            warnings.add("clusters: section missing; no mob clusters will be loaded");
            return;
        }

        Map<String, MythicBridge.MobLookup> mythicLookups = new HashMap<>();
        for (String id : clusters.getKeys(false)) {
            ConfigurationSection cluster = clusters.getConfigurationSection(id);
            if (cluster == null) {
                warnings.add("clusters." + id + ": not a section; ignored");
                continue;
            }

            int total = 0;
            boolean invalid = false;
            for (String entryId : cluster.getKeys(false)) {
                String path = "clusters." + id + "." + entryId;
                ConfigurationSection entry = cluster.getConfigurationSection(entryId);
                if (entry == null) {
                    invalid = true;
                    warnings.add(path + ": expected section");
                    continue;
                }

                String type = entry.getString("type", "").toUpperCase(Locale.ROOT);
                if (!Set.of("VANILLA", "MYTHICMOBS").contains(type)) {
                    invalid = true;
                    warnings.add(path + ".type: expected VANILLA or MYTHICMOBS");
                }

                String mobId = entry.getString("mob", "");
                if (mobId.isBlank()) {
                    invalid = true;
                    warnings.add(path + ".mob: missing id");
                }

                int amount = entry.getInt("amount", 0);
                if (amount < 1 || amount > 10) {
                    invalid = true;
                    warnings.add(path + ".amount: expected 1..10");
                } else {
                    total += amount;
                }

                if ("MYTHICMOBS".equals(type)) {
                    ConfigurationSection level = entry.getConfigurationSection("level");
                    if (level == null || !level.isInt("min") || level.getInt("min") < 1) {
                        invalid = true;
                        warnings.add(path + ".level.min: required positive integer for MYTHICMOBS");
                    }

                    if (!mobId.isBlank()) {
                        MythicBridge.MobLookup lookup = mythicLookups.computeIfAbsent(
                                mobId, mythicBridge::validateMobId);
                        switch (lookup) {
                            case NOT_FOUND -> {
                                invalid = true;
                                warnings.add(path + ".mob: MythicMobs mob id '" + mobId + "' was not found");
                            }
                            case PLUGIN_MISSING -> {
                                invalid = true;
                                warnings.add(path + ".mob: MythicMobs is not installed; this cluster is disabled");
                            }
                            case API_UNSUPPORTED -> warnings.add(
                                    path + ".mob: MythicMobs API lookup is unavailable for the installed version; id validation was skipped");
                            case FOUND -> { }
                        }
                    }
                }

                if (entry.contains("weight")) {
                    double weight = entry.getDouble("weight", -1);
                    if (!Double.isFinite(weight) || weight < 0
                            || Math.abs(weight * 10 - Math.rint(weight * 10)) > 1.0E-7) {
                        invalid = true;
                        warnings.add(path + ".weight: expected non-negative with at most one decimal");
                    }
                }
            }

            if (total > 10) {
                invalid = true;
                warnings.add("clusters." + id + ": total amount exceeds 10");
            }
            if (invalid) {
                warnings.add("clusters." + id + ": invalid cluster will be disabled; other clusters remain available");
            }
        }
    }
    private void validateInt(YamlConfiguration y,String p,int min,int max,int fallback,List<String>w){if(!y.isInt(p)||y.getInt(p)<min||y.getInt(p)>max){w.add(p+": invalid value; using "+fallback);y.set(p,fallback);}}
    private void validateDouble(YamlConfiguration y,String p,double min,double max,double fallback,List<String>w){
        if(!y.isDouble(p)&&!y.isInt(p)){w.add(p+": expected number; using "+fallback);y.set(p,fallback);return;}
        double v=y.getDouble(p);if(!Double.isFinite(v)||v<min||v>max){w.add(p+": out of range; using "+fallback);y.set(p,fallback);}
    }
    private void validateString(YamlConfiguration y,String p,String fallback,List<String>w){if(!y.isString(p)||y.getString(p,"").isBlank()){w.add(p+": expected non-empty string; using default");y.set(p,fallback);}}

    private void preserveRestartValues(YamlConfiguration old,YamlConfiguration next,List<String>changed){
        Set<String>paths=new TreeSet<>();collectPaths(old,"",paths);collectPaths(next,"",paths);
        for(String path:paths){
            boolean restart=RESTART_EXACT.contains(path)||RESTART_PREFIXES.stream().anyMatch(path::startsWith)
                    ||(path.startsWith("transform.")&&!RELOADABLE_TRANSFORM_KEYS.contains(path));
            if(restart&&!Objects.equals(old.get(path),next.get(path))){next.set(path,old.get(path));changed.add(path);}
        }
    }

    private void preserveSectorRestartValues(YamlConfiguration old,YamlConfiguration next,List<String>changed){
        if(old==null)return;
        ConfigurationSection oldRoot=old.getConfigurationSection("sectors"),newRoot=next.getConfigurationSection("sectors");
        Set<String>ids=new TreeSet<>();
        if(oldRoot!=null)ids.addAll(oldRoot.getKeys(false));if(newRoot!=null)ids.addAll(newRoot.getKeys(false));
        for(String id:ids){
            ConfigurationSection before=oldRoot==null?null:oldRoot.getConfigurationSection(id);
            ConfigurationSection after=newRoot==null?null:newRoot.getConfigurationSection(id);
            String base="sectors."+id;
            if(before==null&&after!=null){next.set(base,null);changed.add(base);continue;}
            if(before!=null&&after==null){next.set(base,before.getValues(true));changed.add(base);continue;}
            for(String field:List.of("world","coords","replaces","stronghold.blueprint","stronghold.center","stronghold.npc","stronghold.npc_coord")){
                String path=base+"."+field;
                if(!Objects.equals(old.get(path),next.get(path))){next.set(path,old.get(path));changed.add(path);}
            }
        }
    }
    private void collectPaths(ConfigurationSection s,String prefix,Set<String>paths){
        for(String key:s.getKeys(false)){String path=prefix.isEmpty()?key:prefix+"."+key;if(s.isConfigurationSection(key))collectPaths(s.getConfigurationSection(key),path,paths);else paths.add(path);}
    }
    private void logWarnings(List<String>warnings){for(String warning:warnings)plugin.getLogger().warning("Configuration: "+warning);}
    public YamlConfiguration config(){return config;} public YamlConfiguration messages(){return messages;}
    public YamlConfiguration sectors(){return sectors;} public YamlConfiguration mobs(){return mobs;}
    public YamlConfiguration blueprints(){return blueprints;} public YamlConfiguration menus(){return menus;}
}
