package local.structureroads;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Only read already loaded, populated chunks. Never call World.getChunkFromBlockCoords(). */
final class Terrain implements PathSearch.Ground {
    final WorldServer world;
    final RoadData data;
    final Adapters adapters;
    private final Queue<Long> requests = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Long> settling = new HashMap<>();
    private SurveyTask active;
    private long tick;

    Terrain(WorldServer world, RoadData data, Adapters adapters) {
        this.world = world; this.data = data; this.adapters = adapters;
    }

    Chunk loaded(int x, int z) {
        return world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
    }

    void loadedChunk(ChunkPos pos, boolean populated) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (data.newChunks.contains(key))
            settling.put(key, Math.max(settling.getOrDefault(key, 0L), tick + (populated ? 80 : 20)));
    }

    void unloadedChunk(ChunkPos pos) { settling.remove(ChunkPos.asLong(pos.x, pos.z)); }
    int pending() { return requests.size() + (active == null ? 0 : 1); }
    @Override public int revision() { return data.revision; }

    private boolean eligible(long key) {
        return data.newChunks.contains(key) && !data.editedChunks.contains(key);
    }

    private void request(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        if (eligible(key) && loaded(x, z) != null && !data.surveys.containsKey(key)
                && requests.size() < 128 && queued.add(key)) requests.add(key);
    }

    void tick() {
        tick++;
        long deadline = System.nanoTime() + 2_000_000L;
        for (int budget = 0; budget < RoadConfig.surveyColumns && System.nanoTime() < deadline; budget++) {
            if (active == null) {
                int tries = Math.min(32, requests.size());
                while (tries-- > 0 && active == null) {
                    long key = requests.remove();
                    if (!eligible(key) || data.surveys.containsKey(key)
                            || world.getChunkProvider().getLoadedChunk((int) key, (int) (key >>> 32)) == null) {
                        queued.remove(key); continue;
                    }
                    if (tick < settling.getOrDefault(key, 0L)) { requests.add(key); continue; }
                    active = new SurveyTask(key);
                }
                if (active == null) return;
            }
            int x = ((int) active.key << 4) + (active.cursor & 15);
            int z = ((int) (active.key >>> 32) << 4) + (active.cursor >> 4);
            Chunk chunk = loaded(x, z);
            if (chunk == null || !chunk.isTerrainPopulated() || !eligible(active.key)) {
                queued.remove(active.key); active = null; continue;
            }
            active.tops[active.cursor] = chunk.getHeightValue(x & 15, z & 15);
            active.heights[active.cursor] = naturalSurface(chunk, x, z);
            if (++active.cursor == 256) {
                data.survey(active.key, new RoadData.Survey(active.heights, active.tops));
                queued.remove(active.key); active = null;
            }
        }
    }

    // Planning never reads an unloaded world. Snapshots are requested only near actual
    // local road searches, not by scanning/sampling the whole explored world.
    @Override public int surface(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        if (!eligible(key)) return -1;
        RoadData.Survey survey = data.surveys.get(key);
        if (survey == null) { request(x, z); return -1; }
        int y = survey.heights[(z & 15) * 16 + (x & 15)];
        return y >= 0 && !data.protectedAt(x, y, z) ? y : -1;
    }

    private int naturalSurface(Chunk c, int x, int z) {
        if (!world.getWorldBorder().contains(new BlockPos(x, 64, z))) return -1;
        int top = Math.min(253, c.getHeightValue(x & 15, z & 15));
        // Skip a bounded amount of canopy, never tunnel through trees, roofs or hills.
        for (int y = top; y >= Math.max(1, top - 24); y--) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState state = c.getBlockState(p);
            Block block = state.getBlock();
            if (block == Blocks.AIR || block == Blocks.SNOW_LAYER || block == Blocks.TALLGRASS
                    || block == Blocks.DOUBLE_PLANT || block == Blocks.YELLOW_FLOWER
                    || block == Blocks.RED_FLOWER || block == Blocks.LEAVES || block == Blocks.LEAVES2) continue;
            boolean existingStreet = (block == Blocks.SANDSTONE || block == Blocks.COBBLESTONE || block == Blocks.PLANKS)
                    && data.villageStreetAt(x, y, z);
            if ((!ground(block) && !existingStreet) || state.getBlock().hasTileEntity(state)) return -1;
            if (!clear(c.getBlockState(p.up())) || !clear(c.getBlockState(p.up(2)))) return -1;
            if (!c.getBlockState(p.down()).isFullBlock()) return -1;
            return y;
        }
        return -1;
    }

    boolean surfaceSite(Site s) {
        Chunk c = loaded(s.x(), s.z());
        if (c != null && c.isTerrainPopulated())
            return s.box.maxY >= c.getHeightValue(s.x() & 15, s.z() & 15) - 8;
        RoadData.Survey survey = data.surveys.get(ChunkPos.asLong(s.x() >> 4, s.z() >> 4));
        return survey != null && s.box.maxY >= survey.tops[(s.z() & 15) * 16 + (s.x() & 15)] - 8;
    }

    static boolean ground(Block b) {
        return b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.GRASS_PATH
                || b == Blocks.SAND || b == Blocks.GRAVEL || b == Blocks.STONE
                || b == Blocks.MYCELIUM;
    }

    static boolean clear(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.AIR || b == Blocks.TALLGRASS || b == Blocks.SNOW_LAYER
                || b == Blocks.YELLOW_FLOWER || b == Blocks.RED_FLOWER;
    }

    boolean pave(BlockPos p) {
        // Recheck live block states and adapters immediately before every mutation.
        Chunk c = loaded(p.getX(), p.getZ());
        long key = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
        if (c == null || !c.isTerrainPopulated() || !eligible(key)
                || tick < settling.getOrDefault(key, 0L)
                || naturalSurface(c, p.getX(), p.getZ()) != p.getY()
                || data.protectedAt(p.getX(), p.getY(), p.getZ())
                || adapters.blocked(p) || adapters.blocked(p.up())) return false;
        for (int h = 1; h <= 2; h++) {
            BlockPos above = p.up(h);
            if (c.getBlockState(above).getBlock() != Blocks.AIR)
                world.setBlockState(above, Blocks.AIR.getDefaultState(), 2);
        }
        // Full-block gravel is walkable at one-block slopes and unlike dirt paths has no
        // conditional height/placement behaviour. Natural ground is its existing support.
        return world.setBlockState(p, Blocks.GRAVEL.getDefaultState(), 2)
                || c.getBlockState(p).getBlock() == Blocks.GRAVEL;
    }

    int liveHeight(int x, int z) {
        Chunk chunk = loaded(x, z);
        return chunk == null ? -1 : naturalSurface(chunk, x, z);
    }

    boolean readyToBuild(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        Chunk c = loaded(x, z);
        return c != null && c.isTerrainPopulated() && tick >= settling.getOrDefault(key, 0L);
    }

    private static final class SurveyTask {
        final long key;
        final int[] heights = new int[256], tops = new int[256];
        int cursor;
        SurveyTask(long key) { this.key = key; Arrays.fill(heights, -1); Arrays.fill(tops, -1); }
    }
}
