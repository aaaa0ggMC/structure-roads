package local.structureroads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.StructureBoundingBox;

public final class RoadsCommand extends CommandBase {
    @Override public String getName() { return "brokenconnections"; }
    @Override public List<String> getAliases() { return Collections.singletonList("bcroads"); }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public String getUsage(ICommandSender sender) {
        return "/brokenconnections status|sites [page]|routes [page]|settlement <id> <radius>"
                + "|building <id> <radius>|protect <id> <x1> <y1> <z1> <x2> <y2> <z2>|allowarea [chunkRadius]|retry";
    }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        RoadEngine e = StructureRoads.engine(sender.getEntityWorld());
        if (e == null) throw new CommandException("Broken Connections generates only in the server overworld.");
        String op = args.length == 0 ? "status" : args[0];
        if (op.equals("status")) {
            int abandoned = 0, blocked = 0, done = 0;
            for (RoadData.Route route : e.data.routes.values()) {
                if (route.abandoned) abandoned++;
                if (route.blocked) blocked++;
                if (route.cursor == route.points.size() * 9) done++;
            }
            say(sender, "Broken Connections: settlements=" + e.settlementCount() + ", sites=" + e.data.sites.size()
                    + ", routes=" + e.data.routes.size() + ", abandoned=" + abandoned + ", completed=" + done
                    + ", locallyBlocked=" + blocked + ", surveyedChunks=" + e.data.surveys.size()
                    + ", pendingSurveys=" + e.terrain.pending() + ", eligibleChunks=" + e.data.newChunks.size()
                    + ", editedChunks=" + e.data.editedChunks.size());
            say(sender, "adaptersHealthy=" + e.adapters.healthy + ", indexLimited=" + e.construction.limited
                    + ", terrainCacheLimited=" + e.data.terrainLimited + "; " + e.lastReason);
        } else if (op.equals("sites") || op.equals("routes")) {
            int page = args.length > 1 ? parseInt(args[1], 1) - 1 : 0;
            List<String> lines = new ArrayList<>();
            if (op.equals("sites")) {
                for (Site s : e.data.sites.values()) lines.add((s.settlement ? "settlement " : s.target ? "building " : "avoid ")
                        + s.id + " @ " + s.x() + "," + s.z());
            } else {
                for (RoadData.Route r : e.data.routes.values()) lines.add((r.abandoned ? "abandoned " : "connection ")
                        + r.id + " length=" + r.points.size() + " completedColumns=" + r.cursor + " blocked=" + r.blocked);
            }
            say(sender, "Total " + lines.size() + ", page " + (page + 1));
            long from = (long) page * 8;
            for (long i = from; i < Math.min(from + 8, lines.size()); i++) say(sender, lines.get((int) i));
        } else if ((op.equals("settlement") || op.equals("building")) && args.length == 3) {
            String id = manualId(args[1]);
            int radius = parseInt(args[2], 2, 128);
            BlockPos p = sender.getPosition();
            e.data.site(new Site("manual:" + op + ":" + id, new StructureBoundingBox(p.getX() - radius, p.getY() - 8,
                    p.getZ() - radius, p.getX() + radius, p.getY() + 32, p.getZ() + radius), true, op.equals("settlement")));
            say(sender, "Registered " + op + " " + id + "; roads respect its interior.");
        } else if (op.equals("protect") && args.length == 8) {
            String id = manualId(args[1]);
            BlockPos a = parseBlockPos(sender, args, 2, false), b = parseBlockPos(sender, args, 5, false);
            e.data.site(new Site("manual:avoid:" + id, new StructureBoundingBox(a, b), false, false));
            say(sender, "Registered avoidance region " + id);
        } else if (op.equals("allowarea") && args.length <= 2) {
            int radius = args.length == 2 ? parseInt(args[1], 0, 8) : 4;
            int cx = sender.getPosition().getX() >> 4, cz = sender.getPosition().getZ() >> 4, count = 0;
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                Chunk chunk = e.terrain.loaded(x << 4, z << 4);
                long key = ChunkPos.asLong(x, z);
                if (chunk != null && chunk.isTerrainPopulated() && !e.data.editedChunks.contains(key)) {
                    if (e.data.newChunks.add(key)) count++;
                    e.terrain.loadedChunk(new ChunkPos(x, z), false);
                }
            }
            e.data.markDirty(); e.retry();
            say(sender, "Explicitly allowed " + count + " currently loaded old chunks for natural-ground road building."
                    + " Player-edited chunks remain protected; no chunks were loaded/generated.");
        } else if (op.equals("retry") && args.length == 1) {
            e.retry(); say(sender, "Retrying local roads with all safety checks retained.");
        } else throw new CommandException(getUsage(sender));
    }
    private static String manualId(String id) throws CommandException {
        if (!id.matches("[a-zA-Z0-9_.-]{1,64}")) throw new CommandException("Use a short alphanumeric ID.");
        return id;
    }
    private static void say(ICommandSender sender, String message) { sender.sendMessage(new TextComponentString(message)); }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args,
                Arrays.asList("status", "sites", "routes", "settlement", "building", "protect", "allowarea", "retry"))
                : Collections.emptyList();
    }
}
