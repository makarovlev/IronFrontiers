package com.gachicraft.ironfrontiers.config;

import java.util.List;

/** Result of a configuration reload, including restart-required changes. */
public record ReloadReport(List<String> restartRequired, List<String> warnings) {
    public ReloadReport {
        restartRequired = List.copyOf(restartRequired);
        warnings = List.copyOf(warnings);
    }
    public boolean hasRestartRequiredChanges() { return !restartRequired.isEmpty(); }
}
