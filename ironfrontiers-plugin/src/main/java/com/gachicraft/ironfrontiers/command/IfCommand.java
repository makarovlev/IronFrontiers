package com.gachicraft.ironfrontiers.command;

import com.gachicraft.ironfrontiers.config.ConfigManager;
import com.gachicraft.ironfrontiers.config.ReloadReport;
import com.gachicraft.ironfrontiers.message.Messages;
import org.bukkit.command.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Root administrative command; subcommands are added phase by phase. */
public final class IfCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configs;

    public IfCommand(ConfigManager configs) {
        this.configs = configs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = new Messages(configs.messages());
        if (!sender.hasPermission("ironfrontiers.admin")) {
            messages.send(sender, "general.no_permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "general.usage.root");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            ReloadReport report = configs.reload();
            Messages updated = new Messages(configs.messages());
            updated.send(sender, "general.reload_done");
            for (String path : report.restartRequired()) {
                updated.send(sender, "general.reload_restart_required", Map.of("param", path));
            }
            return true;
        }
        messages.send(sender, "general.unknown_subcommand");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ironfrontiers.admin")) return List.of();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }
        return List.of();
    }
}
