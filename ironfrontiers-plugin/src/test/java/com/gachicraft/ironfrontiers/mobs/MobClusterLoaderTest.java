package com.gachicraft.ironfrontiers.mobs;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MobClusterLoaderTest {
    @Test
    void parsesVanillaClusterAndConvertsWeightToTenths() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                clusters:
                  zombies_a:
                    1:
                      type: VANILLA
                      mob: ZOMBIE
                      amount: 2
                      weight: 1.0
                """));

        MobClusterRegistry registry = new MobClusterLoader(Logger.getLogger("test")).load(yaml);
        MobCluster cluster = registry.get("ZOMBIES_A").orElseThrow();
        assertEquals(2, cluster.totalAmount());
        assertEquals(10, cluster.entries().getFirst().weightTenths());
        assertEquals(2, cluster.entries().getFirst().amount());
    }

    @Test
    void disablesOnlyMalformedClusterAndKeepsOtherClusters() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                clusters:
                  invalid:
                    1:
                      type: VANILLA
                      mob: NOT_A_REAL_ENTITY
                      amount: 1
                  valid:
                    1:
                      type: VANILLA
                      mob: SKELETON
                      amount: 1
                """));

        MobClusterRegistry registry = new MobClusterLoader(Logger.getLogger("test")).load(yaml);
        assertTrue(registry.get("invalid").isEmpty());
        assertTrue(registry.get("valid").isPresent());
    }

    @Test
    void enforcesClusterTotalAndWeightPrecision() {
        YamlConfiguration tooLarge = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                clusters:
                  too_many:
                    1: {type: VANILLA, mob: ZOMBIE, amount: 6}
                    2: {type: VANILLA, mob: SKELETON, amount: 5}
                """));
        YamlConfiguration badWeight = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                clusters:
                  bad:
                    1:
                      type: VANILLA
                      mob: ZOMBIE
                      amount: 1
                      weight: 0.25
                """));

        MobClusterLoader loader = new MobClusterLoader(Logger.getLogger("test"));
        assertTrue(loader.load(tooLarge).get("too_many").isEmpty());
        assertTrue(loader.load(badWeight).get("bad").isEmpty());
    }
}
