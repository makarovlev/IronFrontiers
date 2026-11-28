package com.gachicraft.ironfrontiers.storage;

/** Persistent optional KPM threshold overrides, stored as integer tenths. */
public record SectorOverride(String sectorId, Integer captureTenths, Integer heldTenths) {
    public SectorOverride {
        if (sectorId == null || sectorId.isBlank()
                || (captureTenths != null && captureTenths <= 0)
                || (heldTenths != null && heldTenths <= 0)
                || (captureTenths == null && heldTenths == null)) {
            throw new IllegalArgumentException("Invalid sector override");
        }
    }
}
