package de.derjannik.timberfall;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class TimberListener implements Listener {

    private final TimberFall plugin;
    private boolean probing;

    TimberListener(TimberFall plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (probing) {
            return;
        }
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        FileConfiguration config = plugin.getConfig();
        if (!TreeScanner.isLog(origin.getType()) || !isActive(player, config)) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (config.getBoolean("require-axe") && !Tag.ITEMS_AXES.isTagged(tool.getType())) {
            return;
        }
        TreeScanner.Tree tree = TreeScanner.scan(origin,
                config.getInt("tree.max-logs"), config.getInt("tree.max-leaves"),
                config.getInt("tree.min-leaves"), config.getInt("tree.leaf-radius"));
        if (tree == null) {
            return;
        }
        Material originType = origin.getType();
        Vector direction = fallDirection(player, origin);

        // The origin log breaks after this event, so the tree falls one tick later
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (origin.getType() == originType || !player.isOnline()) {
                return;
            }
            fell(player, origin, originType, tree, direction);
        });
    }

    private boolean isActive(Player player, FileConfiguration config) {
        if (!player.hasPermission("timberfall.use") || !plugin.isEnabledFor(player)) {
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !config.getBoolean("allow-creative")) {
            return false;
        }
        if (config.getStringList("disabled-worlds").contains(player.getWorld().getName())) {
            return false;
        }
        return switch (config.getString("activation", "NOT_SNEAK").toUpperCase()) {
            case "SNEAK" -> player.isSneaking();
            case "NOT_SNEAK" -> !player.isSneaking();
            default -> true;
        };
    }

    // The tree falls away from the player
    private Vector fallDirection(Player player, Block origin) {
        Location center = origin.getLocation().add(0.5, 0, 0.5);
        Vector direction = center.toVector().subtract(player.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 0.01) {
            direction = player.getLocation().getDirection().setY(0);
        }
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(1, 0, 0);
        }
        return direction.normalize();
    }

    private void fell(Player player, Block origin, Material originType, TreeScanner.Tree tree, Vector direction) {
        FileConfiguration config = plugin.getConfig();
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean survival = player.getGameMode() != GameMode.CREATIVE;

        List<Block> logs = new ArrayList<>();
        for (Block log : tree.logs()) {
            if (TreeScanner.isLog(log.getType()) && mayBreak(player, log, config)) {
                logs.add(log);
            }
        }
        if (logs.isEmpty()) {
            return;
        }

        List<FallingTree.Piece> pieces = new ArrayList<>();
        for (Block log : logs) {
            pieces.add(FallingTree.Piece.of(origin, log, true, survival ? log.getDrops(tool, player) : List.of()));
        }
        for (Block leaf : tree.leaves()) {
            if (leaf.getType().isAir()) {
                continue;
            }
            pieces.add(FallingTree.Piece.of(origin, leaf, false, survival ? leaf.getDrops(tool, player) : List.of()));
        }
        for (Block log : logs) {
            log.setType(Material.AIR, false);
        }
        for (Block leaf : tree.leaves()) {
            leaf.setType(Material.AIR, false);
        }

        if (survival && config.getBoolean("damage-per-log")) {
            damageTool(player, tool, logs.size());
        }
        new FallingTree(plugin, player, origin, originType, direction, pieces).start();
    }

    private boolean mayBreak(Player player, Block block, FileConfiguration config) {
        if (!config.getBoolean("fire-break-events")) {
            return true;
        }
        probing = true;
        try {
            BlockBreakEvent probe = new BlockBreakEvent(block, player);
            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } finally {
            probing = false;
        }
    }

    private void damageTool(Player player, ItemStack tool, int logs) {
        if (tool.getType().getMaxDurability() <= 0 || !(tool.getItemMeta() instanceof Damageable meta)
                || meta.isUnbreakable()) {
            return;
        }
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int damage = 0;
        for (int i = 0; i < logs; i++) {
            if (ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) {
                damage++;
            }
        }
        meta.setDamage(meta.getDamage() + damage);
        if (meta.getDamage() >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return;
        }
        tool.setItemMeta(meta);
    }
}
