package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/** Sodium reuses its main list while independently collecting out-of-graph sections. */
final class Blaze3dSodiumVisibility {
    private final Set<Long> main = new HashSet<>(), extras = new HashSet<>();
    private boolean outOfGraph;

    void begin(boolean outOfGraph) {
        this.outOfGraph = outOfGraph;
        (outOfGraph ? this.extras : this.main).clear();
    }
    void record(long section) { (this.outOfGraph ? this.extras : this.main).add(section); }
    void forget(LongPredicate removed) {
        this.main.removeIf(section -> removed.test(section));
        this.extras.removeIf(section -> removed.test(section));
    }
    boolean commitInto(Set<Long> visible) {
        int count = this.main.size();
        for (long section : this.extras) if (!this.main.contains(section)) count++;
        if (visible.size() == count && visible.containsAll(this.main) && visible.containsAll(this.extras)) return false;
        visible.clear(); visible.addAll(this.main); visible.addAll(this.extras);
        return true;
    }
    void clear() { this.main.clear(); this.extras.clear(); this.outOfGraph = false; }
}
