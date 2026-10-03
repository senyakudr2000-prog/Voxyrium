package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Coverage regressions: a missing column must never be hidden by neighbouring Sodium geometry. */
final class Blaze3dVisibilityTest {
    private static int checks;
    static void run() {
        var lists = new Blaze3dSodiumVisibility();
        Set<Long> visible = new HashSet<>();
        lists.begin(false); lists.record(1); lists.record(2);
        lists.begin(true); lists.record(3); lists.record(2);
        check(lists.commitInto(visible) && visible.equals(Set.of(1L, 2L, 3L)), "Main and extra lists form one union");
        check(!lists.commitInto(visible), "Unchanged lists do not trigger a mask upload");
        lists.begin(true); lists.record(4);
        check(lists.commitInto(visible) && visible.equals(Set.of(1L, 2L, 4L)), "Refreshing extras preserves the reused main list");
        lists.begin(false); lists.record(5);
        check(lists.commitInto(visible) && visible.equals(Set.of(4L, 5L)), "Main rebuild retires obsolete sections");
        lists.forget(section -> section == 4 || section == 5);
        check(lists.commitInto(visible) && visible.isEmpty(), "Chunk removal immediately retires both collections");
        lists.record(7); lists.clear();
        check(!lists.commitInto(visible), "World shutdown clears collection state");

        var empty = Blaze3dSodiumCoverage.empty();
        check(!empty.covers(0, 0, 16, 16, false), "No Sodium terrain keeps all Voxy fallback");
        var teleport = Blaze3dSodiumCoverage.of(Set.of(Blaze3dSodiumCoverage.key(-100000, 0),
                Blaze3dSodiumCoverage.key(100000, 0)));
        check(teleport.pixels().length == 1 && !teleport.covers(-16, 0, 16, 16, false),
                "Disjoint collector generations cannot allocate a world-sized mask or hide the gap");
        Set<Long> columns = new HashSet<>();
        for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) columns.add(Blaze3dSodiumCoverage.key(x, z));
        var full = Blaze3dSodiumCoverage.of(columns);
        check(full.flags(-4, -4) == (1 | 2 | 8), "Negative corner has the correct outer edges");
        check(full.flags(4, 4) == (1 | 4 | 16), "Opposite corner has the correct outer edges");
        check(full.flags(-1, -1) == 1 && full.flags(-5, -1) == 0, "Interior and absent columns remain distinct");
        check(full.covers(-32, -32, 32, 32, true), "Fully covered Voxy footprints are suppressed at every height");
        check(full.covers(-63, -63, -49, -49, false), "Overlap disabled suppresses a covered border");
        check(!full.covers(-63, -63, -49, -49, true), "Overlap enabled preserves the transition border");
        check(!full.covers(-64, -32, -48, -16, false), "Bake expansion beyond the first covered column keeps fallback");
        columns.remove(Blaze3dSodiumCoverage.key(0, 0));
        var hole = Blaze3dSodiumCoverage.of(columns);
        check(!hole.covers(-32, -32, 32, 32, false), "Four covered corners cannot hide a hole inside a coarse footprint");
        check(!hole.covers(-.5, -.5, .5, .5, false), "Crossing zero uses floor division for negative coordinates");
        check(hole.flags(-1, 0) == (1 | 4) && hole.flags(0, -1) == (1 | 16), "Missing inner columns gain transition edges");
        check(hole.flags(0, 0) == 0, "An unbuilt column is never inferred from its neighbours");
        check(!hole.covers(16.01, .01, 31.99, 15.99, true), "The transition border around a hole preserves fallback");
        check(full.covers(16.01, .01, 31.99, 15.99, true), "The same interior footprint is removable once the hole loads");

        Set<Integer> slots = new HashSet<>();
        var owners = new HashMap<Integer, long[]>();
        ByteBuffer table = ByteBuffer.allocate(Blaze3dOcclusionHash.CAPACITY * 16).order(ByteOrder.nativeOrder());
        boolean collisionTested = false;
        for (int z = -64; z < 64; z++) for (int x = -64; x < 64; x++) {
            int index = Blaze3dOcclusionHash.index(x * 32f, 0, z * 32f, 1);
            check(index >= 0 && index < Blaze3dOcclusionHash.CAPACITY, "Signed world coordinates stay in the visibility table");
            slots.add(index);
            long[] previous = owners.putIfAbsent(index, new long[]{x, z});
            if (previous != null && !collisionTested) {
                check(!Blaze3dOcclusionHash.owns(table, index, x * 32f, 0, z * 32f, 1), "An empty slot cannot own a real section");
                int offset = index * 16;
                table.putFloat(offset, previous[0] * 32f).putFloat(offset + 4, 0)
                        .putFloat(offset + 8, previous[1] * 32f).putFloat(offset + 12, 1);
                check(Blaze3dOcclusionHash.owns(table, index, previous[0] * 32f, 0, previous[1] * 32f, 1), "The original slot owner may use indirect commands");
                check(!Blaze3dOcclusionHash.owns(table, index, x * 32f, 0, z * 32f, 1), "A hash collision must keep its own direct draw");
                check(!Blaze3dOcclusionHash.owns(table, index, previous[0] * 32f, 0, previous[1] * 32f, 2), "A different LOD cannot consume another scale's commands");
                collisionTested = true;
            }
        }
        check(collisionTested, "The collision safety checks exercised a real table collision");
        check(slots.size() > 13000, "Aligned ground-plane origins must not collapse to a small subset of slots");
        System.out.println("Blaze3D visibility: " + checks + " checks passed; " + slots.size() + " unique ground-plane slots.");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
