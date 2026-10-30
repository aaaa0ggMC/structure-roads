package local.structureroads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

public final class RoadData extends WorldSavedData {
    static final String NAME = "brokenconnections_v1";
    final Map<String, Site> sites = new LinkedHashMap<>();
    final Set<Long> editedChunks = new HashSet<>();
    final Set<Long> newChunks = new HashSet<>();
    final Map<String, Route> routes = new LinkedHashMap<>();
    final Map<Long, Survey> surveys = new LinkedHashMap<>(128, 0.75f, true);
    int terrainRevision;
    boolean terrainLimited;
    private final Map<Long, List<Site>> protectionGrid = new HashMap<>();
    private final List<Site> largeSites = new ArrayList<>();
    int revision;
    boolean capacityExceeded;

    public RoadData() { super(NAME); }
    public RoadData(String name) { super(name); }

    static RoadData get(World world) {
        RoadData d = (RoadData) world.getPerWorldStorage().getOrLoadData(RoadData.class, NAME);
        if (d == null) {
            d = new RoadData();
            // Import safety permissions/edits only. The cancelled long-distance network
            // and its old structure targets must NOT resume under the new mod.
            RoadData old = (RoadData) world.getPerWorldStorage().getOrLoadData(RoadData.class, "structureroads_v1");
            if (old != null) { d.newChunks.addAll(old.newChunks); d.editedChunks.addAll(old.editedChunks); }
            world.getPerWorldStorage().setData(NAME, d);
            d.markDirty();
        }
        return d;
    }

    void site(Site s) {
        Site old = sites.get(s.id);
        if (old != null && old.write().equals(s.write())) return;
        if (old == null && sites.size() >= 8192) {
            if (!capacityExceeded) {
                capacityExceeded = true; markDirty();
                StructureRoads.LOG.error("Structure index capacity exceeded; roads disabled for safety.");
            }
            return;
        }
        if (old != null) index(old, false);
        sites.put(s.id, s);
        index(s, true);
        revision++;
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound n) {
        sites.clear(); editedChunks.clear(); newChunks.clear(); routes.clear();
        protectionGrid.clear(); largeSites.clear(); surveys.clear();
        capacityExceeded = n.getBoolean("capacityExceeded");
        NBTTagList list = n.getTagList("sites", 10);
        for (int i = 0; i < Math.min(8192, list.tagCount()); i++) {
            Site s = Site.read(list.getCompoundTagAt(i));
            if (s != null) { sites.put(s.id, s); index(s, true); }
        }
        for (long v : unpack(n.getIntArray("edited"))) editedChunks.add(v);
        for (long v : unpack(n.getIntArray("new"))) newChunks.add(v);
        list = n.getTagList("routes", 10);
        for (int i = 0; i < Math.min(8192, list.tagCount()); i++) {
            Route r = Route.read(list.getCompoundTagAt(i));
            if (r != null) routes.put(r.id, r);
        }
        revision++;
        list = n.getTagList("surveys", 10);
        for (int i = 0; i < Math.min(256, list.tagCount()); i++) {
            NBTTagCompound s = list.getCompoundTagAt(i);
            int[] heights = s.getIntArray("heights"), tops = s.getIntArray("tops");
            if (heights.length == 256 && tops.length == 256)
                surveys.put(s.getLong("chunk"), new Survey(heights, tops));
        }
        terrainRevision++;
    }

    void survey(long chunk, Survey survey) {
        if (!surveys.containsKey(chunk) && surveys.size() >= 256) {
            surveys.remove(surveys.keySet().iterator().next()); terrainLimited = true;
        }
        surveys.put(chunk, survey); terrainRevision++; markDirty();
    }

    static final class Survey {
        final int[] heights, tops;
        Survey(int[] heights, int[] tops) { this.heights = heights; this.tops = tops; }
    }

    boolean protectedAt(int x, int y, int z) {
        List<Site> nearby = protectionGrid.getOrDefault(ChunkPos.asLong(x >> 6, z >> 6), Collections.emptyList());
        for (Site s : nearby) if (s.covers(x, y, z)) return true;
        for (Site s : largeSites) if (s.covers(x, y, z)) return true;
        return false;
    }

    boolean villageStreetAt(int x, int y, int z) {
        List<Site> nearby = protectionGrid.getOrDefault(ChunkPos.asLong(x >> 6, z >> 6), Collections.emptyList());
        for (Site s : nearby) if (s.id.contains(":street:") && x >= s.box.minX && x <= s.box.maxX
                && z >= s.box.minZ && z <= s.box.maxZ && y >= s.box.minY - 3 && y <= s.box.maxY + 3) return true;
        return false;
    }

    // 64-block spatial buckets: do not scan every known structure for every path column.
    private void index(Site s, boolean add) {
        int m = RoadConfig.margin;
        int x1 = (s.box.minX - m) >> 6, x2 = (s.box.maxX + m) >> 6;
        int z1 = (s.box.minZ - m) >> 6, z2 = (s.box.maxZ + m) >> 6;
        if ((long) (x2 - x1 + 1) * (z2 - z1 + 1) > 64) {
            if (add) largeSites.add(s); else largeSites.remove(s);
            return;
        }
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            long key = ChunkPos.asLong(x, z);
            if (add) protectionGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            else {
                List<Site> bucket = protectionGrid.get(key);
                if (bucket != null) { bucket.remove(s); if (bucket.isEmpty()) protectionGrid.remove(key); }
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound n) {
        n.setBoolean("capacityExceeded", capacityExceeded);
        NBTTagList list = new NBTTagList();
        for (Site s : sites.values()) list.appendTag(s.write());
        n.setTag("sites", list);
        n.setIntArray("edited", pack(longs(editedChunks)));
        n.setIntArray("new", pack(longs(newChunks)));
        list = new NBTTagList();
        for (Route r : routes.values()) list.appendTag(r.write());
        n.setTag("routes", list);
        list = new NBTTagList();
        for (Map.Entry<Long, Survey> entry : surveys.entrySet()) {
            NBTTagCompound s = new NBTTagCompound();
            s.setLong("chunk", entry.getKey()); s.setIntArray("heights", entry.getValue().heights);
            s.setIntArray("tops", entry.getValue().tops); list.appendTag(s);
        }
        n.setTag("surveys", list);
        return n;
    }

    private static long[] longs(Set<Long> set) {
        long[] a = new long[set.size()]; int i = 0;
        for (long v : set) a[i++] = v;
        return a;
    }

    // 1.12.2 NBTTagCompound does not expose long-array accessors.
    private static int[] pack(long[] a) {
        int[] out = new int[a.length * 2];
        for (int i = 0; i < a.length; i++) {
            out[i * 2] = (int) (a[i] >>> 32); out[i * 2 + 1] = (int) a[i];
        }
        return out;
    }

    private static long[] unpack(int[] a) {
        long[] out = new long[a.length / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = ((long) a[i * 2] << 32) | (a[i * 2 + 1] & 0xffffffffL);
        return out;
    }

    static String pair(Site a, Site b) {
        return a.id.compareTo(b.id) < 0 ? a.id + "|" + b.id : b.id + "|" + a.id;
    }

    static final class Route {
        final String id;
        final List<Long> points;
        int cursor;
        boolean blocked;
        boolean abandoned;
        long salt;
        String connection = "";
        final RoadProgress progress = new RoadProgress();

        Route(String id, List<Long> points) { this.id = id; this.points = points; }

        NBTTagCompound write() {
            NBTTagCompound n = new NBTTagCompound();
            n.setString("id", id); n.setInteger("cursor", cursor); n.setBoolean("blocked", blocked);
            long[] p = new long[points.size()];
            for (int i = 0; i < p.length; i++) p[i] = points.get(i);
            n.setIntArray("points", pack(p));
            n.setBoolean("abandoned", abandoned); n.setLong("salt", salt);
            n.setString("connection", connection);
            n.setIntArray("completed", pack(progress.done.toLongArray()));
            n.setIntArray("blockedPoints", pack(progress.blocked.toLongArray()));
            return n;
        }

        static Route read(NBTTagCompound n) {
            long[] p = unpack(n.getIntArray("points"));
            if (p.length == 0 || p.length > 100000 || n.getString("id").isEmpty()) return null;
            List<Long> points = new ArrayList<>();
            for (long v : p) points.add(v);
            Route r = new Route(n.getString("id"), points);
            r.cursor = Math.max(0, Math.min(p.length * 9, n.getInteger("cursor")));
            r.blocked = n.getBoolean("blocked");
            r.abandoned = n.getBoolean("abandoned"); r.salt = n.getLong("salt");
            r.connection = n.getString("connection");
            if (n.hasKey("completed")) {
                r.progress.restore(unpack(n.getIntArray("completed")), unpack(n.getIntArray("blockedPoints")), p.length * 9);
            } else {
                r.progress.done.set(0, r.cursor);
                if (r.blocked) r.progress.blocked.set(0, p.length);
            }
            r.cursor = r.progress.done.cardinality();
            return r;
        }
    }
}
