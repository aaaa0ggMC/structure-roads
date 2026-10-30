package local.structureroads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Incremental cardinal A*: four-block grid, but every intermediate column is checked. */
final class PathSearch {
    interface Ground { int surface(int x, int z); int revision(); }
    private final Ground terrain;
    private final BlockPos start, goal;
    private final Map<Long, Node> best = new HashMap<>();
    private final Map<Long, Integer> heights = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
        @Override public int compare(Node first, Node second) {
            int c = Integer.compare(first.f, second.f);
            return c != 0 ? c : Long.compare(first.key(), second.key());
        }
    });
    private final int revision;
    private int expanded;
    private final boolean partial;
    private Node furthest;
    String reason = "searching";
    boolean done;
    List<Long> result;
    final String id;

    PathSearch(Terrain terrain, Site a, Site b) {
        this(terrain, RoadData.pair(a, b), approach(terrain, a, b), approach(terrain, b, a), false);
    }

    PathSearch(Ground terrain, String id, BlockPos start, BlockPos goal, boolean partial) {
        this.terrain = terrain;
        this.start = start; this.goal = goal; this.partial = partial;
        this.id = id; revision = terrain.revision();
        int y = height(start.getX(), start.getZ());
        if (y < 0 || (!partial && height(goal.getX(), goal.getZ()) < 0)) {
            done = true; reason = "endpoint unavailable/protected; waiting for local terrain"; return;
        }
        Node n = new Node(start.getX(), y, start.getZ(), 0, estimate(start.getX(), start.getZ()), null);
        best.put(n.key(), n); open.add(n); furthest = n;
    }

    static BlockPos approach(Ground terrain, Site a, Site b) {
        BlockPos base = a.approach(b);
        boolean eastWest = Math.abs(b.x() - a.x()) >= Math.abs(b.z() - a.z());
        for (int offset : new int[] {0, 8, -8, 16, -16}) {
            BlockPos candidate = base.add(eastWest ? 0 : offset, 0, eastWest ? offset : 0);
            boolean safe = true;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (terrain.surface(candidate.getX() + dx, candidate.getZ() + dz) < 0) safe = false;
            if (safe) return candidate;
        }
        return base;
    }

    void step() {
        // A world changed while planning: discard and retry against the new structure map.
        if (terrain.revision() != revision) { done = true; reason = "structure/protection changed"; return; }
        long deadline = System.nanoTime() + 2_000_000L;
        for (int i = 0; i < RoadConfig.nodesPerTick && !done && System.nanoTime() < deadline; i++) {
            if (open.isEmpty() || expanded++ >= RoadConfig.maxNodes || heights.size() > 150000) {
                if (partial && furthest != null && estimate(start.getX(), start.getZ()) - estimate(furthest.x, furthest.z) >= 48)
                    finish(furthest);
                else { done = true; reason = "no safe local route yet"; }
                return;
            }
            Node n = open.remove();
            if (best.get(n.key()) != n) continue;
            if (estimate(n.x, n.z) < estimate(furthest.x, furthest.z)) furthest = n;
            if (n.x == goal.getX() && n.z == goal.getZ()) { finish(n); return; }
            visit(n, 4, 0); visit(n, -4, 0); visit(n, 0, 4); visit(n, 0, -4);
        }
    }

    private void visit(Node parent, int dx, int dz) {
        int x = parent.x + dx, z = parent.z + dz;
        int padding = partial ? 32 : 64;
        if (x < Math.min(start.getX(), goal.getX()) - padding
                || x > Math.max(start.getX(), goal.getX()) + padding
                || z < Math.min(start.getZ(), goal.getZ()) - padding
                || z > Math.max(start.getZ(), goal.getZ()) + padding) return;
        int previous = parent.y, slope = 0;
        for (int i = 1; i <= 4; i++) {
            int y = height(parent.x + dx / 4 * i, parent.z + dz / 4 * i);
            if (y < 0 || Math.abs(y - previous) > 1) return;
            slope += Math.abs(y - previous); previous = y;
        }
        int g = parent.g + 4 + slope * 5;
        long key = ChunkPos.asLong(x, z);
        Node old = best.get(key);
        if (old != null && old.g <= g) return;
        Node n = new Node(x, previous, z, g, g + estimate(x, z), parent);
        best.put(key, n); open.add(n);
    }

    private int height(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Integer cached = heights.get(key);
        if (cached != null) return cached;
        int centre = terrain.surface(x, z);
        if (centre >= 0) {
            for (int dx = -1; dx <= 1 && centre >= 0; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int y = terrain.surface(x + dx, z + dz);
                    if (y < 0 || Math.abs(y - centre) > 1) { centre = -1; break; }
                }
            }
        }
        heights.put(key, centre);
        return centre;
    }

    private int estimate(int x, int z) {
        return Math.abs(x - goal.getX()) + Math.abs(z - goal.getZ());
    }

    private void finish(Node n) {
        List<Node> chain = new ArrayList<>();
        for (Node p = n; p != null; p = p.parent) chain.add(p);
        Collections.reverse(chain);
        result = new ArrayList<>();
        Node first = chain.get(0);
        result.add(new BlockPos(first.x, first.y, first.z).toLong());
        for (int i = 1; i < chain.size(); i++) {
            Node a = chain.get(i - 1), b = chain.get(i);
            for (int j = 1; j <= 4; j++) {
                int x = a.x + (b.x - a.x) / 4 * j, z = a.z + (b.z - a.z) / 4 * j;
                result.add(new BlockPos(x, heights.get(ChunkPos.asLong(x, z)), z).toLong());
            }
        }
        done = true;
        reason = "planned";
    }

    int expanded() { return expanded; }

    private static final class Node {
        final int x, y, z, g, f;
        final Node parent;
        Node(int x, int y, int z, int g, int f, Node parent) {
            this.x = x; this.y = y; this.z = z; this.g = g; this.f = f; this.parent = parent;
        }
        long key() { return ChunkPos.asLong(x, z); }
    }
}
