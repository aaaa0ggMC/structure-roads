package local.structureroads;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.StructureBoundingBox;

final class Site {
    final String id;
    final StructureBoundingBox box;
    final boolean target;
    final boolean settlement;
    boolean protectEnvelope = true;
    int[] exits = new int[0];

    Site(String id, StructureBoundingBox box, boolean target) {
        this(id, box, target, id.startsWith("nbt:Village:") || id.startsWith("manual:settlement:"));
    }

    Site(String id, StructureBoundingBox box, boolean target, boolean settlement) {
        this.id = id;
        this.box = box;
        this.target = target;
        this.settlement = settlement;
    }

    int x() { return box.minX + (box.maxX - box.minX) / 2; }
    int z() { return box.minZ + (box.maxZ - box.minZ) / 2; }

    long distanceSq(Site other) {
        long dx = (long) x() - other.x(), dz = (long) z() - other.z();
        return dx * dx + dz * dz;
    }

    boolean covers(int x, int y, int z) {
        if (!protectEnvelope) return false;
        int m = id.contains(":piece:") ? 0 : RoadConfig.margin;
        return x >= box.minX - m && x <= box.maxX + m
                && z >= box.minZ - m && z <= box.maxZ + m
                && y >= box.minY - m && y <= box.maxY + m;
    }

    // Terminate OUTSIDE the structure. Never carve towards a guessed door through a building.
    BlockPos approach(Site other) {
        if (exits.length >= 2) {
            int dx = other.x() - x(), dz = other.z() - z(), best = 0;
            long score = Long.MIN_VALUE;
            for (int i = 0; i + 1 < exits.length; i += 2) {
                long projected = (long) (exits[i] - x()) * dx + (long) (exits[i + 1] - z()) * dz;
                if (projected > score) { score = projected; best = i; }
            }
            return new BlockPos(Math.floorDiv(exits[best], 4) * 4, 0, Math.floorDiv(exits[best + 1], 4) * 4);
        }
        int pad = RoadConfig.margin + 5;
        int dx = other.x() - x(), dz = other.z() - z();
        int x = x(), z = z();
        if (Math.abs(dx) >= Math.abs(dz)) x = dx >= 0 ? box.maxX + pad : box.minX - pad;
        else z = dz >= 0 ? box.maxZ + pad : box.minZ - pad;
        return new BlockPos(Math.floorDiv(x, 4) * 4, 0, Math.floorDiv(z, 4) * 4);
    }

    NBTTagCompound write() {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("id", id);
        n.setIntArray("box", new int[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
        n.setBoolean("target", target);
        n.setBoolean("settlement", settlement);
        n.setBoolean("protectEnvelope", protectEnvelope); n.setIntArray("exits", exits);
        return n;
    }

    static Site read(NBTTagCompound n) {
        int[] b = n.getIntArray("box");
        if (b.length != 6 || n.getString("id").isEmpty()
                || b[0] > b[3] || b[1] > b[4] || b[2] > b[5]) return null;
        String id = n.getString("id");
        Site site = new Site(id, new StructureBoundingBox(b), n.getBoolean("target"),
                n.hasKey("settlement") ? n.getBoolean("settlement") : id.startsWith("nbt:Village:"));
        site.protectEnvelope = !n.hasKey("protectEnvelope") || n.getBoolean("protectEnvelope");
        int[] exits = n.getIntArray("exits");
        if (exits.length <= 2048 && exits.length % 2 == 0) site.exits = exits;
        return site;
    }
}
