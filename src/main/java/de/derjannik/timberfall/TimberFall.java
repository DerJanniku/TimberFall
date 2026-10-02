package de.derjannik.timberfall;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TimberFall extends JavaPlugin {

    private final Set<FallingTree> falling = new HashSet<>();
    private NamespacedKey disabledKey;

    @Override
    public void onEnable() {
        Banner.print(this);
        saveDefaultConfig();
        // Add keys from newer versions to an existing config
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
        this.disabledKey = new NamespacedKey(this, "disabled");

        // Register listeners
        Bukkit.getPluginManager().registerEvents(new TimberListener(this), this);
    }

    @Override
    public void onDisable() {
        for (FallingTree tree : new ArrayList<>(falling)) {
            tree.finish();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("timberfall.admin")) {
                    sender.sendMessage(message("no-permission"));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(message("reloaded"));
            }
            case "info" -> {
                sender.sendMessage(ChatColor.GOLD + "TimberFall " + ChatColor.GRAY + "v" + getDescription().getVersion()
                        + " by " + ChatColor.YELLOW + "DerJannik");
                sender.sendMessage(ChatColor.GRAY + "Custom plugins: " + ChatColor.AQUA + Banner.FIVERR);
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Usage: /timberfall <reload|info>");
                    return true;
                }
                boolean nowDisabled = isEnabledFor(player);
                if (nowDisabled) {
                    player.getPersistentDataContainer().set(disabledKey, PersistentDataType.BYTE, (byte) 1);
                } else {
                    player.getPersistentDataContainer().remove(disabledKey);
                }
                player.sendMessage(message(nowDisabled ? "disabled" : "enabled"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("toggle", "info", "reload")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    out.add(option);
                }
            }
        }
        return out;
    }

    boolean isEnabledFor(Player player) {
        return !player.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE);
    }

    Set<FallingTree> falling() {
        return falling;
    }

    String message(String key) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, ""));
    }
}
