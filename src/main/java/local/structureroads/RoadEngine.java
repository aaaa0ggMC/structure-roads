package local.structureroads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/** Each settlement is an independent atom. Never attempt a global village graph. */
final class RoadEngine {
    final RoadData data;
    final Adapters adapters;
    final Terrain terrain;
    final ConstructionQueue construction;
    private final long seed;
    private final Set<String> connected = new HashSet<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private List<Site> destinations = new ArrayList<>(), settlements = new ArrayList<>();
    private int siteRevision = -1, sourceCursor;
    private NearbyScan scan;
    private Job job;
    private long tick;
    String lastReason = "initial structure scan";

    RoadEngine(WorldServer world) {
        data = RoadData.get(world); seed = world.getSeed();
        adapters = new Adapters(world, data);
        terrain = new Terrain(world, data, adapters);
        construction = new ConstructionQueue(terrain);
        for (RoadData.Route r : data.routes.values()) if (!r.connection.isEmpty()) connected.add(r.connection);
    }

    void tick() {
        tick++;
        adapters.tick(tick); terrain.tick(); construction.tick();
        if (!adapters.healthy || data.capacityExceeded) { lastReason = "adapter/protection index stopped"; return; }
        if (!adapters.ready()) return;
        if (siteRevision != data.revision) {
            destinations = new ArrayList<>(); settlements = new ArrayList<>();
            for (Site site : data.sites.values()) {
                if (site.target) destinations.add(site);
                if (site.settlement) settlements.add(site);
            }
            siteRevision = data.revision; scan = null;
        }
        if (job != null) {
            job.search.step();
            if (job.search.done) finish();
        } else if (scan != null) {
            scan.step();
            if (scan.cursor >= scan.candidates.size()) { schedule(scan); scan = null; }
        } else if (tick % 20 == 0 && data.routes.size() < 8192) {
            if (settlements.isEmpty()) lastReason = "no settlement registered";
            else {
                Site a = settlements.get(Math.floorMod(sourceCursor++, settlements.size()));
                if (terrain.surfaceSite(a)) scan = new NearbyScan(a, destinations);
                else lastReason = "waiting for a loaded/surveyed settlement";
            }
        }
        build();
    }

    int settlementCount() { return settlements.size(); }

    void retry() {
        failures.clear();
        for (RoadData.Route route : data.routes.values()) { route.progress.blocked.clear(); route.blocked = false; }
        construction.retry(); data.markDirty();
    }

    private boolean cooled(String key) {
        Failure failure = failures.get(key);
        return failure == null || tick >= failure.until || failure.terrain != data.terrainRevision
                || failure.structure != data.revision;
    }

    private void schedule(NearbyScan found) {
        for (int slot = 0; slot < RoadConfig.roadsPerSettlement; slot++) {
            String id = "local:" + found.origin.id + ":" + slot;
            if (data.routes.containsKey(id)) continue;
            Site target = slot < found.nearest.size() ? found.nearest.get(slot) : null;
            if (target != null) {
                String pair = RoadData.pair(found.origin, target);
                if (connected.contains(pair)) continue; // Both settlements share this local road.
                if (cooled("connect:" + id)) {
                    PathSearch search = new PathSearch(terrain, found.origin, target);
                    job = new Job(id, "connect:" + id, search, false, pair); return;
                }
            }
            if (!cooled("branch:" + id)) continue;
            int direction = RoadStyle.direction(seed, found.origin.id, slot);
            int[] dxs = {1, 1, 0, -1, -1, -1, 0, 1};
            int[] dzs = {0, 1, 1, 1, 0, -1, -1, -1};
            int dx = dxs[direction], dz = dzs[direction];
            Site bearing = new Site("bearing", new StructureBoundingBox(found.origin.x() + dx * 512, 64,
                    found.origin.z() + dz * 512, found.origin.x() + dx * 512, 64,
                    found.origin.z() + dz * 512), false);
            BlockPos start = PathSearch.approach(terrain, found.origin, bearing);
            int length = RoadStyle.length(seed, found.origin.id, slot, RoadConfig.branchMin, RoadConfig.branchMax);
            int axisLength = (int) (length / (dx != 0 && dz != 0 ? Math.sqrt(2) : 1)) / 4 * 4;
            BlockPos goal = start.add(dx * axisLength, 0, dz * axisLength);
            job = new Job(id, "branch:" + id, new PathSearch(terrain, id, start, goal, true), true, "");
            return;
        }
        lastReason = "local roads complete or waiting for safe local terrain";
    }

    private void finish() {
        if (job.search.result != null) {
            RoadData.Route route = new RoadData.Route(job.id, job.search.result);
            route.abandoned = job.abandoned; route.salt = RoadStyle.mix(seed ^ job.id.hashCode());
            route.connection = job.pair;
            data.routes.put(route.id, route); construction.add(route);
            if (!route.connection.isEmpty()) connected.add(route.connection);
            data.markDirty();
            lastReason = (route.abandoned ? "abandoned branch" : "local connection") + " planned: " + route.points.size() + " columns";
            StructureRoads.LOG.info("Broken Connections: {}", lastReason);
        } else {
            if (failures.size() >= 16384) failures.clear();
            failures.put(job.retryKey, new Failure(tick + 1200, data.terrainRevision, data.revision));
            lastReason = job.search.reason;
        }
        job = null;
    }

    private void build() {
        long deadline = System.nanoTime() + 1_000_000L;
        ConstructionQueue.Point current = null;
        for (int budget = 0; budget < RoadConfig.blocksPerTick && System.nanoTime() < deadline; budget++) {
            if (current == null) current = construction.next();
            if (current == null) return;
            RoadData.Route r = current.route;
            int first = current.index * 9;
            int column = r.progress.done.nextClearBit(first);
            if (column >= first + 9) { current = null; continue; }
            int offset = column - first, dx = offset / 3 - 1, dz = offset % 3 - 1;
            int x = current.pos.getX() + dx, z = current.pos.getZ() + dz;
            if (!terrain.readyToBuild(x, z)) { current = null; continue; }
            if (r.abandoned && !RoadStyle.place(r.salt, current.index, r.points.size(), dx, dz)) {
                if (r.progress.finish(column)) r.cursor++;
                data.markDirty(); continue;
            }
            int y = terrain.liveHeight(x, z);
            if (y < 0 || Math.abs(y - current.pos.getY()) > 1 || !terrain.pave(new BlockPos(x, y, z))) {
                r.progress.blocked.set(current.index); r.blocked = true; data.markDirty();
                lastReason = "construction stopped locally: unsafe/edited terrain";
                current = null; continue;
            }
            if (r.progress.finish(column)) r.cursor++;
            data.markDirty();
        }
    }

    private final class NearbyScan {
        final Site origin;
        final List<Site> candidates;
        final List<Site> nearest = new ArrayList<>();
        int cursor;
        NearbyScan(Site origin, List<Site> candidates) { this.origin = origin; this.candidates = candidates; }
        void step() {
            for (int budget = 0; budget < 128 && cursor < candidates.size(); budget++) {
                Site b = candidates.get(cursor++);
                long distance = origin.distanceSq(b);
                boolean overlap = origin.box.minX <= b.box.maxX && origin.box.maxX >= b.box.minX
                        && origin.box.minZ <= b.box.maxZ && origin.box.maxZ >= b.box.minZ;
                if (origin.id.equals(b.id) || overlap || distance > (long) RoadConfig.radius * RoadConfig.radius
                        || !terrain.surfaceSite(b)) continue;
                int index = 0;
                while (index < nearest.size() && (origin.distanceSq(nearest.get(index)) < distance
                        || (origin.distanceSq(nearest.get(index)) == distance && nearest.get(index).id.compareTo(b.id) < 0))) index++;
                nearest.add(index, b);
                if (nearest.size() > RoadConfig.roadsPerSettlement) nearest.remove(nearest.size() - 1);
            }
        }
    }
    private static final class Job {
        final String id, retryKey, pair; final PathSearch search; final boolean abandoned;
        Job(String id, String retryKey, PathSearch search, boolean abandoned, String pair) {
            this.id = id; this.retryKey = retryKey; this.search = search; this.abandoned = abandoned; this.pair = pair;
        }
    }
    private static final class Failure {
        final long until; final int terrain, structure;
        Failure(long until, int terrain, int structure) { this.until = until; this.terrain = terrain; this.structure = structure; }
    }
}
