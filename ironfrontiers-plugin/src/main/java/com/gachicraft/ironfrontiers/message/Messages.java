package com.gachicraft.ironfrontiers.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders MiniMessage and legacy ampersand colors from messages.yml. */
public final class Messages {
    private static final Pattern LEGACY = Pattern.compile("(?i)&([0-9a-fk-or])");
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final YamlConfiguration source;

    public Messages(YamlConfiguration source) { this.source = Objects.requireNonNull(source); }

    public Component component(String key, Map<String, String> placeholders) {
        String text = source.getString(key, "");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        Component rendered = miniMessage.deserialize(convertLegacyCodes(text));
        if ("general.prefix".equals(key) || text.isEmpty()) return rendered;
        String prefix = source.getString("general.prefix", "");
        return miniMessage.deserialize(convertLegacyCodes(prefix)).append(rendered);
    }

    private String convertLegacyCodes(String text) {
        Matcher matcher = LEGACY.matcher(text);
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(converted,
                Matcher.quoteReplacement(legacyTag(matcher.group(1).charAt(0))));
        matcher.appendTail(converted);
        return converted.toString();
    }

    private String legacyTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>"; case '1' -> "<dark_blue>"; case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>"; case '4' -> "<dark_red>"; case '5' -> "<dark_purple>";
            case '6' -> "<gold>"; case '7' -> "<gray>"; case '8' -> "<dark_gray>";
            case '9' -> "<blue>"; case 'a' -> "<green>"; case 'b' -> "<aqua>";
            case 'c' -> "<red>"; case 'd' -> "<light_purple>"; case 'e' -> "<yellow>";
            case 'f' -> "<white>"; case 'k' -> "<obfuscated>"; case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>"; case 'n' -> "<underlined>"; case 'o' -> "<italic>";
            case 'r' -> "<reset>"; default -> "";
        };
    }

    public String raw(String key) { return source.getString(key, ""); }

    public Component component(String key) { return component(key, Map.of()); }
    public void send(CommandSender sender, String key) { sender.sendMessage(component(key)); }
    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(key, placeholders));
    }
}
