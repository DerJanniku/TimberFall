package de.derjannik.timberfall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class FallingTree extends BukkitRunnable {

    private static final int STEP = 2;
    private static final float MIN_ANGLE = 35f;

    static final class Piece {
        final Vector3f offset;
        final BlockData data;
        final boolean log;
        final Collection<ItemStack> drops;
        BlockDisplay display;

        private Piece(Vector3f offset, BlockData data, boolean log, Collection<ItemStack> drops) {
            this.offset = offset;
            this.data = data;
            this.log = log;
            this.drops = drops;
        }

        // The offset is the block corner relative to the bottom center of the chopped log
        static Piece of(Block origin, Block block, boolean log, Collection<ItemStack> drops) {
            Vector3f offset = new Vector3f(block.getX() - origin.getX() - 0.5f, block.getY() - origin.getY(),
                    block.getZ() - origin.getZ() - 0.5f);
            return new Piece(offset, block.getBlockData(), log, new ArrayList<>(drops));
        }
    }

    private final TimberFall plugin;
    private final Player player;
    private final Block origin;
    private final Material originType;
    private final Location pivot;
    private final Vector3f axis;
    private final List<Piece> pieces;
    private final int duration;
    private final float maxAngle;
    private int tick;
    private boolean finished;

    FallingTree(TimberFall plugin, Player player, Block origin, Material originType, Vector direction,
                List<Piece> pieces) {
        this.plugin = plugin;
        this.player = player;
        this.origin = origin;
        this.originType = originType;
        this.pivot = origin.getLocation().add(0.5, 0, 0.5);
        this.pivot.setYaw(0);
        this.pivot.setPitch(0);
        // Rotating around up x direction tips the trunk towards the direction
        this.axis = new Vector3f((float) direction.getZ(), 0f, (float) -direction.getX()).normalize();
        this.pieces = pieces;
        this.duration = Math.max(6, plugin.getConfig().getInt("animation.duration", 28));
        this.maxAngle = plugin.getConfig().getBoolean("animation.terrain-collision") ? restingAngle() : 90f;
    }

    void start() {
        World world = pivot.getWorld();
        for (Piece piece : pieces) {
            piece.display = world.spawn(pivot, BlockDisplay.class, display -> {
                display.setBlock(piece.data);
                display.setPersistent(false);
                display.addScoreboardTag("timberfall");
                display.setTransformation(transformation(piece, 0f));
            });
        }
        if (plugin.getConfig().getBoolean("animation.sounds")) {
            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.5f);
        }
        plugin.falling().add(this);
        runTaskTimer(plugin, 2L, STEP);
    }

    @Override
    public void run() {
        if (tick >= duration) {
            finish();
            return;
        }
        tick = Math.min(duration, tick + STEP);
        float progress = tick / (float) duration;
        // Slow start, fast end, like a real falling trunk
        float angle = maxAngle * progress * progress;
        for (Piece piece : pieces) {
            piece.display.setInterpolationDelay(0);
            piece.display.setInterpolationDuration(STEP);
            piece.display.setTransformation(transformation(piece, angle));
        }
    }

    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (!isCancelled()) {
            cancel();
        }
        plugin.falling().remove(this);

        World world = pivot.getWorld();
        boolean particles = plugin.getConfig().getBoolean("animation.particles");
        boolean toInventory = plugin.getConfig().getBoolean("drops-to-inventory") && player.isOnline();
        boolean replant = plugin.getConfig().getBoolean("replant");
        Quaternionf rotation = rotation(maxAngle);
        int index = 0;

        for (Piece piece : pieces) {
            if (piece.display != null) {
                piece.display.remove();
            }
            Location landed = landed(piece, rotation);
            if (particles && (pieces.size() < 150 || index++ % 3 == 0)) {
                world.spawnParticle(Particle.BLOCK, landed, 6, 0.3, 0.3, 0.3, piece.data);
            }
            for (ItemStack drop : piece.drops) {
                if (replant && tryReplant(drop)) {
                    replant = false;
                }
                if (drop.getAmount() <= 0) {
                    continue;
                }
                if (toInventory) {
                    for (ItemStack rest : player.getInventory().addItem(drop).values()) {
                        world.dropItemNaturally(player.getLocation(), rest);
                    }
                } else {
                    world.dropItemNaturally(free(landed), drop);
                }
            }
        }
        if (plugin.getConfig().getBoolean("animation.sounds")) {
            Location impact = landed(pieces.get(0), rotation);
            world.playSound(impact, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 0.6f);
            world.playSound(impact, Sound.BLOCK_GRASS_BREAK, 1.5f, 0.6f);
        }
    }

    // Takes one sapling out of the drops and plants it where the trunk stood
    private boolean tryReplant(ItemStack drop) {
        if (!Tag.SAPLINGS.isTagged(drop.getType()) && drop.getType() != Material.MANGROVE_PROPAGULE) {
            return false;
        }
        Block soil = origin.getRelative(0, -1, 0);
        if (!origin.getType().isAir() || !Tag.DIRT.isTagged(soil.getType())) {
            return false;
        }
        if (!drop.getType().name().startsWith(TreeScanner.family(originType))) {
            return false;
        }
        origin.setType(drop.getType());
        drop.setAmount(drop.getAmount() - 1);
        return true;
    }

    private Quaternionf rotation(float degrees) {
        return new Quaternionf().rotateAxis((float) Math.toRadians(degrees), axis);
    }

    private Transformation transformation(Piece piece, float degrees) {
        Quaternionf rotation = rotation(degrees);
        Vector3f translation = rotation.transform(new Vector3f(piece.offset));
        return new Transformation(translation, rotation, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    private Location landed(Piece piece, Quaternionf rotation) {
        Vector3f center = rotation.transform(new Vector3f(piece.offset).add(0.5f, 0.5f, 0.5f));
        return pivot.clone().add(center.x, center.y, center.z);
    }

    private Location free(Location location) {
        Location result = location.clone();
        for (int i = 0; i < 3 && !result.getBlock().isPassable(); i++) {
            result.add(0, 1, 0);
        }
        return result;
    }

    // Finds the angle at which the trunk first touches solid terrain
    private float restingAngle() {
        for (float angle = MIN_ANGLE; angle < 90f; angle += 5f) {
            Quaternionf rotation = rotation(angle);
            for (Piece piece : pieces) {
                if (!piece.log || piece.offset.y < 2f) {
                    continue;
                }
                Block block = landed(piece, rotation).getBlock();
                if (!block.isPassable() && !Tag.LEAVES.isTagged(block.getType())) {
                    return angle;
                }
            }
        }
        return 90f;
    }
}
