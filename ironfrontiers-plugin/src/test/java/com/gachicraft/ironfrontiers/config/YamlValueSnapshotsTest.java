package com.gachicraft.ironfrontiers.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlValueSnapshotsTest {
    @Test
    void equivalentNestedSectionsAreNotTreatedAsChanged() throws Exception {
        YamlConfiguration first = load("""
                sectors:
                  aa01:
                    replaces:
                      biome: "BADLANDS,FOREST"
                      blocks:
                        rule_1: "SOUL_SAND,GRASS_BLOCK"
                """);
        YamlConfiguration second = load("""
                sectors:
                  aa01:
                    replaces:
                      biome: "BADLANDS,FOREST"
                      blocks:
                        rule_1: "SOUL_SAND,GRASS_BLOCK"
                """);

        assertFalse(first.get("sectors.aa01.replaces")
                == second.get("sectors.aa01.replaces"));
        assertTrue(YamlValueSnapshots.equivalent(
                first.get("sectors.aa01.replaces"),
                second.get("sectors.aa01.replaces")));
    }

    @Test
    void changedNestedValueIsDetected() throws Exception {
        YamlConfiguration first = load("""
                sectors:
                  aa01:
                    replaces:
                      biome: "BADLANDS,FOREST"
                      blocks:
                        rule_1: "SOUL_SAND,GRASS_BLOCK"
                """);
        YamlConfiguration second = load("""
                sectors:
                  aa01:
                    replaces:
                      biome: "BADLANDS,FOREST"
                      blocks:
                        rule_1: "SOUL_SAND,DIRT"
                """);

        assertFalse(YamlValueSnapshots.equivalent(
                first.get("sectors.aa01.replaces"),
                second.get("sectors.aa01.replaces")));
    }

    private static YamlConfiguration load(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return configuration;
    }
}
