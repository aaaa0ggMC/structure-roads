package local.structureroads;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.MapGenStructureData;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.fml.common.Loader;

/** Optional adapters call public mod APIs; no coremods, generator replacement or locate(). */
final class Adapters {
    private static final String RC = "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData";
    private static final String CQR = "team.cqr.cqrepoured.world.structure.protection.ProtectedRegionManager";
    private static final Map<String, Method> METHODS = new HashMap<>();
    final WorldServer world;
    final RoadData data;
    final Queue<ChunkPos> chunks = new ArrayDeque<>();
    private Iterator<?> cqrEntries = Collections.emptyIterator();
    private Iterator<String> structureKeys = Collections.emptyIterator();
    private NBTTagCompound structureTags;
    private String structureName;
    private boolean structureTarget;
    private boolean structureSettlement;
    private final List<String> sources;
    private boolean initialScanComplete;
    private int sourceIndex;
    boolean healthy = true;

    Adapters(WorldServer world, RoadData data) {
        this.world = world; this.data = data;
        LinkedHashSet<String> names = new LinkedHashSet<>(Arrays.asList(RoadConfig.settlementNames));
        names.addAll(Arrays.asList(RoadConfig.targetNames)); names.addAll(Arrays.asList(RoadConfig.protectionNames));
        sources = new ArrayList<>(names);
    }

    void tick(long tick) {
        if (!healthy) return;
        try {
            // Bounded processing, including worlds with many saved vanilla structures.
            for (int i = 0; i < 8 && structureKeys.hasNext(); i++) {
                String key = structureKeys.next();
                NBTTagCompound tag = structureTags.getCompoundTag(key);
                int[] box = tag.getIntArray("BB");
                if (box.length == 6 && (!tag.hasKey("Valid") || tag.getBoolean("Valid")))
                    recordStructure(key, tag, box);
            }
            if (sourceIndex >= sources.size() && !structureKeys.hasNext()) initialScanComplete = true;
            if (!structureKeys.hasNext() && tick % 20 == 0) {
                int total = sources.size();
                if (total > 0) {
                    int i = sourceIndex++ % total;
                    structureName = sources.get(i);
                    structureSettlement = Arrays.asList(RoadConfig.settlementNames).contains(structureName);
                    structureTarget = structureSettlement || Arrays.asList(RoadConfig.targetNames).contains(structureName);
                    MapGenStructureData saved = (MapGenStructureData) world.getPerWorldStorage()
                            .getOrLoadData(MapGenStructureData.class, structureName);
                    if (saved != null) {
                        structureTags = saved.getTagCompound();
                        // Copy keys: vanilla may add structures between ticks.
                        structureKeys = new java.util.ArrayList<>(structureTags.getKeySet()).iterator();
                    }
                }
            }
            if (!chunks.isEmpty()) scanChunk(chunks.remove());
            if (tick % 200 == 0 && !cqrEntries.hasNext() && Loader.isModLoaded("cqrepoured")) {
                Object manager = callStatic(CQR, "getInstance", World.class, world);
                cqrEntries = ((Iterable<?>) call(manager, "getProtectedRegions")).iterator();
            }
            for (int i = 0; i < 8 && cqrEntries.hasNext(); i++) recordCqr(cqrEntries.next());
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    void enqueue(ChunkPos pos) {
        if (Loader.isModLoaded("reccomplex") && chunks.size() < 2048) chunks.add(pos);
    }

    boolean ready() {
        return initialScanComplete;
    }

    private void recordStructure(String key, NBTTagCompound tag, int[] box) {
        String id = "nbt:" + structureName + ":" + key;
        Site site = new Site(id, new StructureBoundingBox(box), structureTarget, structureSettlement);
        NBTTagList pieces = tag.getTagList("Children", 10);
        List<Integer> exits = new ArrayList<>();
        boolean complete = pieces.tagCount() > 0 && pieces.tagCount() <= 256;
        if (structureSettlement && complete) {
            for (int i = 0; i < pieces.tagCount(); i++) {
                NBTTagCompound piece = pieces.getCompoundTagAt(i);
                int[] b = piece.getIntArray("BB");
                if (b.length != 6) { complete = false; break; }
                // Vanilla's registered village path component (all vanilla village biomes).
                // Do not guess arbitrary mod component IDs as safe walkways.
                if (piece.getString("id").equals("ViSR")) {
                    int x = b[0] + (b[3] - b[0]) / 2, z = b[2] + (b[5] - b[2]) / 2;
                    exits.add(b[0]); exits.add(z); exits.add(b[3]); exits.add(z);
                    exits.add(x); exits.add(b[2]); exits.add(x); exits.add(b[5]);
                }
            }
            if (complete && !exits.isEmpty()) {
                // Existing village streets are valid starts; protect each house/farm/well,
                // not the whole settlement envelope, which would disconnect every exit.
                for (int i = 0; i < pieces.tagCount(); i++) {
                    NBTTagCompound piece = pieces.getCompoundTagAt(i);
                    if (!piece.getString("id").equals("ViSR")) {
                        data.site(new Site(id + ":piece:" + i, new StructureBoundingBox(piece.getIntArray("BB")), false, false));
                    } else {
                        Site street = new Site(id + ":street:" + i, new StructureBoundingBox(piece.getIntArray("BB")), false, false);
                        street.protectEnvelope = false; data.site(street);
                    }
                }
                site.protectEnvelope = false;
                site.exits = new int[exits.size()];
                for (int i = 0; i < exits.size(); i++) site.exits[i] = exits.get(i);
            }
        }
        data.site(site);
    }

    private void scanChunk(ChunkPos pos) throws ReflectiveOperationException {
        if (!Loader.isModLoaded("reccomplex")) return;
        Object saved = callStatic(RC, "get", World.class, world);
        try (Stream<?> entries = (Stream<?>) call(saved, "entriesAt", ChunkPos.class, pos)) {
            Iterator<?> it = entries.iterator();
            // Keep all avoidance checks independent of this discovery limit (see blocked()).
            for (int n = 0; n < 256 && it.hasNext(); n++) recordRc(it.next());
        }
    }

    private void recordRc(Object entry) throws ReflectiveOperationException {
        StructureBoundingBox box = (StructureBoundingBox) call(entry, "getBoundingBox");
        String name = "";
        if (entry.getClass().getName().endsWith("$StructureEntry"))
            name = (String) call(entry, "getStructureID");
        boolean settlement = Arrays.asList(RoadConfig.rcSettlements).contains(name);
        boolean target = settlement || Arrays.asList(RoadConfig.rcTargets).contains(name);
        String id = name.isEmpty() ? "rc-custom:" + call(entry, "getUuid") : rcId(name, box);
        data.site(new Site(id, copy(box), target, settlement));
    }

    private void recordCqr(Object region) throws ReflectiveOperationException {
        BlockPos a = (BlockPos) call(region, "getStartPos");
        BlockPos b = (BlockPos) call(region, "getEndPos");
        StructureBoundingBox box = new StructureBoundingBox(a, b);
        // CQR's region name and generation point are NOT reliable entrance coordinates.
        // Surface regions may be approached at their perimeter; underground ones stay protected.
        data.site(new Site("cqr:" + call(region, "getUuid"), box,
                !(Boolean) call(region, "isGenerating"),
                !(Boolean) call(region, "isGenerating")
                        && Arrays.asList(RoadConfig.cqrSettlements).contains((String) call(region, "getName"))));
    }

    boolean blocked(BlockPos pos) {
        if (!healthy) return true;
        try {
            if (Loader.isModLoaded("reccomplex")) {
                Object saved = callStatic(RC, "get", World.class, world);
                try (Stream<?> stream = (Stream<?>) call(saved, "entriesAt", ChunkPos.class, new ChunkPos(pos))) {
                    Iterator<?> it = stream.iterator();
                    while (it.hasNext()) {
                        Object entry = it.next();
                        StructureBoundingBox box = (StructureBoundingBox) call(entry, "getBoundingBox");
                        if (new Site("", box, false).covers(pos.getX(), pos.getY(), pos.getZ())) return true;
                    }
                }
            }
            if (Loader.isModLoaded("cqrepoured")) {
                Object manager = callStatic(CQR, "getInstance", World.class, world);
                List<?> regions = (List<?>) call(manager, "getProtectedRegionsAt", BlockPos.class, pos);
                if (!regions.isEmpty()) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return true;
        }
    }

    void rcEvent(Object event) {
        try {
            String name = (String) call(event, "getStructureName");
            StructureBoundingBox box = (StructureBoundingBox) call(event, "getBoundingBox");
            String id = rcId(name, box);
            boolean settlement = Arrays.asList(RoadConfig.rcSettlements).contains(name);
            data.site(new Site(id, copy(box), settlement || Arrays.asList(RoadConfig.rcTargets).contains(name), settlement));
        } catch (ReflectiveOperationException | RuntimeException e) { fail(e); }
    }

    private void fail(Exception e) {
        healthy = false;
        StructureRoads.LOG.error("Structure adapter failed. Roads disabled in dimension {} for safety.",
                world.provider.getDimension(), e);
    }

    private static StructureBoundingBox copy(StructureBoundingBox b) {
        return new StructureBoundingBox(new int[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
    }

    private static String rcId(String name, StructureBoundingBox box) {
        return "rc:" + name + ":" + box.minX + ":" + box.minY + ":" + box.minZ;
    }

    private static Object callStatic(String type, String name, Class<?> argType, Object arg)
            throws ReflectiveOperationException {
        return method(Class.forName(type), name, argType).invoke(null, arg);
    }

    private static Object call(Object object, String name) throws ReflectiveOperationException {
        return method(object.getClass(), name).invoke(object);
    }

    private static Object call(Object object, String name, Class<?> type, Object arg)
            throws ReflectiveOperationException {
        return method(object.getClass(), name, type).invoke(object, arg);
    }

    private static Method method(Class<?> type, String name, Class<?>... args)
            throws ReflectiveOperationException {
        String key = type.getName() + ":" + name + Arrays.toString(args);
        Method m = METHODS.get(key);
        if (m == null) { m = type.getMethod(name, args); METHODS.put(key, m); }
        return m;
    }
}
