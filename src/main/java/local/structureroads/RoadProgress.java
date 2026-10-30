package local.structureroads;

import java.util.BitSet;

/** Completion is per road column, not a prefix blocked by an unloaded first endpoint. */
final class RoadProgress {
    final BitSet done = new BitSet();
    final BitSet blocked = new BitSet();

    boolean finished(int point) { return done.nextClearBit(point * 9) >= (point + 1) * 9; }
    boolean finish(int column) {
        if (done.get(column)) return false;
        done.set(column); return true;
    }
    void restore(long[] completed, long[] stopped, int columns) {
        done.clear(); blocked.clear();
        done.or(BitSet.valueOf(completed)); blocked.or(BitSet.valueOf(stopped));
        if (done.length() > columns) done.clear(columns, done.length());
        int points = columns / 9;
        if (blocked.length() > points) blocked.clear(points, blocked.length());
    }
}
