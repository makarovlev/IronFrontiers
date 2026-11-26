package com.gachicraft.ironfrontiers.sector;

import org.bukkit.Server;

/** Local-server online-player count. */
public final class LocalPlayerCountSource implements PlayerCountSource {
    private final Server server;
    public LocalPlayerCountSource(Server server) { this.server = server; }
    @Override public int onlinePlayers() { return server.getOnlinePlayers().size(); }
}
