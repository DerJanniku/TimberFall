package de.derjannik.timberfall;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TreeScanner {

    private static final int MAX_WIDTH = 10;
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    record Tree(List<Block> logs, List<Block> leaves) {
    }

    private TreeScanner() {
    }

    static boolean isLog(Material type) {
        return Tag.LOGS.isTagged(type);
    }

    // Returns null when the structure is not a natural tree
    static Tree scan(Block origin, int maxLogs, int maxLeaves, int minLeaves, int leafRadius) {
        String family = family(origin.getType());
        List<Block> logs = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);

        while (!queue.isEmpty()) {
            Block current = queue.poll();
            logs.add(current);
            if (logs.size() > maxLogs) {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block next = current.getRelative(dx, dy, dz);
                        if (seen.contains(next) || !isLog(next.getType()) || !family.equals(family(next.getType()))) {
                            continue;
                        }
                        if (Math.abs(next.getX() - origin.getX()) > MAX_WIDTH
                                || Math.abs(next.getZ() - origin.getZ()) > MAX_WIDTH) {
                            continue;
                        }
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        List<Block> leaves = scanLeaves(logs, maxLeaves, leafRadius);
        if (leaves.size() < minLeaves) {
            return null;
        }
        logs.remove(origin);
        return new Tree(logs, leaves);
    }

    private static List<Block> scanLeaves(List<Block> logs, int maxLeaves, int leafRadius) {
        List<Block> leaves = new ArrayList<>();
        Map<Block, Integer> depth = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();
        for (Block log : logs) {
            depth.put(log, 0);
            queue.add(log);
        }
        while (!queue.isEmpty() && leaves.size() < maxLeaves) {
            Block current = queue.poll();
            int next = depth.get(current) + 1;
            if (next > leafRadius) {
                continue;
            }
            for (BlockFace face : FACES) {
                Block block = current.getRelative(face);
                if (depth.containsKey(block) || !isFoliage(block, next)) {
                    continue;
                }
                depth.put(block, next);
                queue.add(block);
                leaves.add(block);
                if (leaves.size() >= maxLeaves) {
                    break;
                }
            }
        }
        return leaves;
    }

    private static boolean isFoliage(Block block, int depth) {
        Material type = block.getType();
        if (Tag.LEAVES.isTagged(type)) {
            // Persistent leaves were placed by players, a lower distance belongs to another tree
            return block.getBlockData() instanceof Leaves data && !data.isPersistent() && data.getDistance() >= depth;
        }
        return Tag.WART_BLOCKS.isTagged(type) || type == Material.SHROOMLIGHT;
    }

    // OAK_LOG, OAK_WOOD and STRIPPED_OAK_LOG all share the family OAK
    static String family(Material type) {
        return type.name().replace("STRIPPED_", "")
                .replace("_LOG", "").replace("_WOOD", "").replace("_STEM", "").replace("_HYPHAE", "");
    }
}
