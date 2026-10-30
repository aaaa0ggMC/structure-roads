package local.structureroads;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = StructureRoads.ID, name = "Broken Connections", version = "0.2.0",
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class StructureRoads {
    public static final String ID = "brokenconnections";
    public static Logger LOG;
    private static final Map<WorldServer, RoadEngine> ENGINES = new IdentityHashMap<>();

    @Mod.EventHandler
    public void init(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
        RoadConfig.load(event.getSuggestedConfigurationFile());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) { event.registerServerCommand(new RoadsCommand()); }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) { ENGINES.clear(); }

    // First version intentionally limits automatic roads to the overworld.
    static RoadEngine engine(World world) {
        if (!(world instanceof WorldServer) || world.isRemote || world.provider.getDimension() != 0) return null;
        return ENGINES.computeIfAbsent((WorldServer) world, RoadEngine::new);
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RoadEngine e = engine(event.world);
        if (e != null) e.tick();
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.getWorld() instanceof WorldServer) ENGINES.remove((WorldServer) event.getWorld());
    }

    @SubscribeEvent
    public void chunk(ChunkEvent.Load event) {
        RoadEngine e = engine(event.getWorld());
        if (e != null) {
            e.adapters.enqueue(event.getChunk().getPos());
            e.terrain.loadedChunk(event.getChunk().getPos(), false);
            e.construction.activate(event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public void chunkUnloaded(ChunkEvent.Unload event) {
        RoadEngine e = ENGINES.get(event.getWorld());
        if (e != null) e.terrain.unloadedChunk(event.getChunk().getPos());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void populated(PopulateChunkEvent.Post event) {
        RoadEngine e = engine(event.getWorld());
        if (e == null) return;
        long key = ChunkPos.asLong(event.getChunkX(), event.getChunkZ());
        if (e.data.newChunks.add(key)) e.data.markDirty();
        e.terrain.loadedChunk(new ChunkPos(event.getChunkX(), event.getChunkZ()), true);
        e.adapters.enqueue(new ChunkPos(event.getChunkX(), event.getChunkZ()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void placed(BlockEvent.PlaceEvent event) { edited(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void broken(BlockEvent.BreakEvent event) { edited(event); }

    private void edited(BlockEvent event) {
        RoadEngine e = engine(event.getWorld());
        if (e == null) return;
        long key = ChunkPos.asLong(event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        if (e.data.editedChunks.add(key)) {
            e.data.surveys.remove(key); e.data.revision++; e.data.markDirty();
        }
    }

    // Avoid a mandatory dependency on RC. Public event methods were checked in the installed jar.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void optionalEvent(WorldEvent event) {
        if (!event.getClass().getName().equals("ivorius.reccomplex.events.StructureGenerationEventLite$Post")) return;
        World world = event.getWorld();
        if (!(world instanceof WorldServer) || world.isRemote) return;
        WorldServer server = (WorldServer) world;
        server.addScheduledTask(() -> {
            RoadEngine e = engine(server);
            if (e != null) e.adapters.rcEvent(event);
        });
    }
}
