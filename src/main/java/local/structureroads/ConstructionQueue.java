package local.structureroads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Budgeted local construction index. An unloaded endpoint cannot block another loaded piece. */
final class ConstructionQueue {
    private final Terrain terrain;
    private final Map<Long, List<Point>> chunks = new HashMap<>();
    private final Queue<Pending> indexing = new ArrayDeque<>();
    private final Queue<Long> active = new ArrayDeque<>();
    private final Set<Long> activated = new HashSet<>();
    private final Map<Long, Integer> cursors = new HashMap<>();
    private final Map<Long, Integer> completedScans = new HashMap<>();
    private int points;
    boolean limited;

    ConstructionQueue(Terrain terrain) {
        this.terrain = terrain;
        for (RoadData.Route route : terrain.data.routes.values()) add(route);
    }
    void add(RoadData.Route route) { indexing.add(new Pending(route)); }
    void activate(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (chunks.containsKey(key) && activated.add(key)) active.add(key);
    }
    void tick() {
        for (int i = 0; i < 256 && !indexing.isEmpty(); i++) {
            Pending p = indexing.peek();
            if (p.cursor >= p.route.points.size()) { indexing.remove(); continue; }
            if (points >= 250000) { limited = true; indexing.clear(); return; }
            BlockPos pos = BlockPos.fromLong(p.route.points.get(p.cursor));
            long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            chunks.computeIfAbsent(key, k -> new ArrayList<>()).add(new Point(p.route, p.cursor++, pos));
            points++;
            if (terrain.loaded(pos.getX(), pos.getZ()) != null) activate(new ChunkPos(pos));
        }
    }
    Point next() {
        for (int tries = 0; tries < 32 && !active.isEmpty(); tries++) {
            long key = active.remove();
            int cx = (int) key, cz = (int) (key >>> 32);
            if (terrain.loaded(cx << 4, cz << 4) == null) { activated.remove(key); continue; }
            List<Point> list = chunks.get(key);
            int cursor = cursors.getOrDefault(key, 0) % list.size();
            Point p = list.get(cursor);
            cursors.put(key, cursor + 1);
            boolean pending = !p.route.progress.finished(p.index) && !p.route.progress.blocked.get(p.index);
            int complete = pending ? 0 : completedScans.getOrDefault(key, 0) + 1;
            completedScans.put(key, complete);
            if (complete >= list.size()) { activated.remove(key); continue; }
            active.add(key);
            if (pending) return p;
        }
        return null;
    }
    void retry() {
        cursors.clear(); completedScans.clear();
        for (long key : chunks.keySet())
            if (terrain.loaded(((int) key) << 4, ((int) (key >>> 32)) << 4) != null)
                activate(new ChunkPos((int) key, (int) (key >>> 32)));
    }
    static final class Point {
        final RoadData.Route route; final int index; final BlockPos pos;
        Point(RoadData.Route route, int index, BlockPos pos) { this.route = route; this.index = index; this.pos = pos; }
    }
    private static final class Pending {
        final RoadData.Route route; int cursor;
        Pending(RoadData.Route route) { this.route = route; }
    }
}
