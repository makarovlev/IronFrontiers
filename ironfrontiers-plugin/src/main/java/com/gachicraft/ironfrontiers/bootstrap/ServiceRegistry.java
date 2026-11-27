package com.gachicraft.ironfrontiers.bootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small explicit dependency registry used during plugin bootstrap. */
public final class ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    public <T> void register(Class<T> type, T service) {
        if (services.putIfAbsent(type, type.cast(service)) != null) throw new IllegalStateException("Service already registered: " + type.getName());
    }
    public <T> T require(Class<T> type) {
        Object service = services.get(type);
        if (service == null) throw new IllegalStateException("Service not registered: " + type.getName());
        return type.cast(service);
    }
    public <T> boolean has(Class<T> type) { return services.containsKey(type); }
    public void clear() { services.clear(); }
}
