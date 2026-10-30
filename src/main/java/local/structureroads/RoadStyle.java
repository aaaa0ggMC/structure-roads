package local.structureroads;

/** Seeded layout and decay. No world access, no mutable Random state, stable after reload. */
final class RoadStyle {
    static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }
    static int direction(long seed, String id, int slot) {
        return (int) Math.floorMod(mix(seed ^ id.hashCode()), 8) + slot * 3 & 7;
    }
    static int length(long seed, String id, int slot, int min, int max) {
        return min + (int) Math.floorMod(mix(seed ^ id.hashCode() ^ (slot * 7151L)), max - min + 1);
    }
    static boolean place(long salt, int point, int count, int dx, int dz) {
        double progress = point / (double) Math.max(1, count - 1);
        if (progress < 0.5) return true;
        double decay = (progress - 0.5) * 2;
        boolean edge = dx != 0 || dz != 0;
        double probability = 1 - decay * (edge ? 0.98 : 0.82);
        long h = mix(salt ^ point * 65537L ^ (dx + 2) * 257L ^ (dz + 2) * 4099L);
        return (h >>> 11) * 0x1.0p-53 < probability;
    }
    private RoadStyle() { }
}
