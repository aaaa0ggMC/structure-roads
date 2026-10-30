package local.structureroads;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/** Offline logic regressions; no game launch, world generation, graphics, or added dependency. */
public final class RoadLogicChecks {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RoadConfig.nodesPerTick = 8; RoadConfig.maxNodes = 3000; RoadConfig.margin = 5;
        deterministicDecay(); localPlanning(); progressAndPersistence();
        System.out.println("Broken Connections: " + checks + " logic assertions passed.");
    }

    private static void deterministicDecay() {
        long seed = 1234567;
        int direction = RoadStyle.direction(seed, "Village:12,4", 0);
        require(direction >= 0 && direction < 8, "direction bounded");
        require(direction == RoadStyle.direction(seed, "Village:12,4", 0), "layout stable");
        require(direction != RoadStyle.direction(seed, "Village:12,4", 1), "branches spread out");
        int length = RoadStyle.length(seed, "Village:12,4", 0, 96, 160);
        require(length >= 96 && length <= 160, "short local goal length");
        int intact = 0, tail = 0;
        for (int point = 0; point < 160; point++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            boolean p = RoadStyle.place(seed, point, 160, dx, dz);
            require(p == RoadStyle.place(seed, point, 160, dx, dz), "decay survives reload");
            if (point < 80) { require(p, "first half intact"); intact++; }
            if (point >= 120 && p) tail++;
        }
        require(tail > 0 && tail < intact / 3, "tail progressively broken, not a solid road");
    }

    private static void run(PathSearch search) {
        int frames = 0;
        while (!search.done && frames++ < 3000) {
            int before = search.expanded(); search.step();
            require(search.expanded() - before <= RoadConfig.nodesPerTick, "bounded work per step");
        }
        require(search.done, "search terminates");
    }

    private static void localPlanning() {
        // This backend is ONLY a snapshot: no World or loaded-chunk provider exists.
        PathSearch.Ground flat = new PathSearch.Ground() {
            @Override public int revision() { return 1; }
            @Override public int surface(int x, int z) { return x >= -16 && x <= 200 && Math.abs(z) <= 80 ? 64 : -1; }
        };
        PathSearch route = new PathSearch(flat, "local", new BlockPos(0, 0, 0), new BlockPos(128, 0, 0), false);
        run(route);
        require(route.result != null && route.result.size() == 129, "local building connects through cached terrain");
        require(BlockPos.fromLong(route.result.get(128)).getX() == 128, "connection reaches goal");
        PathSearch.Ground obstruction = new PathSearch.Ground() {
            @Override public int revision() { return 1; }
            @Override public int surface(int x, int z) {
                return x >= 40 && x <= 60 && Math.abs(z) <= 8 ? -1 : flat.surface(x, z);
            }
        };
        PathSearch around = new PathSearch(obstruction, "avoid", new BlockPos(0, 0, 0), new BlockPos(128, 0, 0), false);
        run(around); require(around.result != null, "can avoid building instead of cutting through");
        for (long encoded : around.result) {
            BlockPos p = BlockPos.fromLong(encoded);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                require(obstruction.surface(p.getX() + dx, p.getZ() + dz) >= 0, "entire road width avoids structure");
        }
        PathSearch.Ground unknown = new PathSearch.Ground() {
            @Override public int revision() { return 1; }
            @Override public int surface(int x, int z) { return x <= 64 ? flat.surface(x, z) : -1; }
        };
        PathSearch cannotConnect = new PathSearch(unknown, "unknown", new BlockPos(0, 0, 0), new BlockPos(128, 0, 0), false);
        run(cannotConnect); require(cannotConnect.result == null, "never invent unobserved terrain for connection");
        PathSearch abandoned = new PathSearch(unknown, "branch", new BlockPos(0, 0, 0), new BlockPos(128, 0, 0), true);
        run(abandoned);
        require(abandoned.result != null && abandoned.result.size() >= 49, "isolated branch keeps safe local prefix");
        for (long encoded : abandoned.result)
            require(BlockPos.fromLong(encoded).getX() < 64, "branch stops before unknown terrain");
        Site village = new Site("nbt:Village:test", new StructureBoundingBox(-8, 60, -8, 8, 90, 8), true);
        Site nearby = new Site("building", new StructureBoundingBox(100, 60, -8, 116, 90, 8), true, false);
        require(village.settlement && !nearby.settlement, "origins and destinations have different roles");
        require(!village.covers(village.approach(nearby).getX(), 64, village.approach(nearby).getZ()), "road starts outside village protection");
    }

    private static void progressAndPersistence() {
        RoadProgress progress = new RoadProgress();
        for (int c = 90; c < 99; c++) progress.finish(c);
        require(progress.finished(10) && !progress.finished(0), "loaded end builds without unloaded prefix");
        require(!progress.finish(90), "same column does not increment twice");
        progress.blocked.set(4);
        RoadProgress restored = new RoadProgress(); restored.restore(progress.done.toLongArray(), progress.blocked.toLongArray(), 180);
        require(restored.finished(10) && !restored.finished(0) && restored.blocked.get(4), "progress persists out of order");
        List<Long> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) points.add(new BlockPos(i, 64, 0).toLong());
        RoadData.Route route = new RoadData.Route("atom:1", points);
        route.abandoned = true; route.salt = 123; route.progress.finish(90); route.cursor = 1;
        RoadData.Route copy = RoadData.Route.read(route.write());
        require(copy != null && copy.abandoned && copy.salt == 123 && copy.cursor == 1 && copy.progress.done.get(90), "abandoned route NBT round trip");
        NBTTagCompound legacy = route.write(); legacy.removeTag("completed"); legacy.removeTag("blockedPoints"); legacy.setInteger("cursor", 18);
        RoadData.Route old = RoadData.Route.read(legacy);
        require(old.progress.finished(0) && old.progress.finished(1) && !old.progress.finished(2), "old prefix progress migration");
        RoadData data = new RoadData();
        data.site(new Site("manual:settlement:sample", new StructureBoundingBox(0, 60, 0, 8, 80, 8), true, true));
        data.routes.put(route.id, route);
        data.survey(7L, new RoadData.Survey(new int[256], new int[256]));
        RoadData read = new RoadData(); read.readFromNBT(data.writeToNBT(new NBTTagCompound()));
        require(read.sites.get("manual:settlement:sample").settlement && read.routes.size() == 1 && read.surveys.size() == 1,
                "settlement roles and demanded survey persist");
        Site envelope = new Site("nbt:Village:envelope", new StructureBoundingBox(-32, 60, -32, 32, 90, 32), true, true);
        envelope.protectEnvelope = false; envelope.exits = new int[] {28, 0, -28, 0};
        data.site(envelope);
        data.site(new Site("nbt:Village:envelope:piece:0", new StructureBoundingBox(0, 60, 8, 12, 80, 20), false, false));
        Site street = new Site("nbt:Village:envelope:street:1", new StructureBoundingBox(-28, 63, -1, 28, 66, 1), false, false);
        street.protectEnvelope = false; data.site(street);
        require(!data.protectedAt(20, 64, 0), "village street is not blocked by entire settlement envelope");
        require(data.protectedAt(4, 64, 12), "village houses/farms still protected");
        require(data.villageStreetAt(20, 64, 0) && !data.villageStreetAt(20, 64, 8), "desert/wood street allowance stays inside registered path");
        Site exitRoundTrip = Site.read(envelope.write());
        require(exitRoundTrip != null && !exitRoundTrip.protectEnvelope && exitRoundTrip.exits.length == 4,
                "village exits and component protection mode persist");
    }
}
