package com.ccrstech.openandroiddex.launcher;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

/**
 * When a measured caption height may be forgotten, and about what.
 *
 * <p>Why this is its own class, with no Android in it: the first version of this prune lived
 * inside {@code CaptionFallback.pass()} and was wrong in a way no test could see. It pruned
 * the probe cache against {@code bars} — the windows <em>our</em> renderer draws a bar for.
 * But every visible task is probed, including the ones {@code CaptionRule} hands to the
 * accessibility service and the ones it decides not to decorate; those never own a bar. So
 * the cache threw away live entries every pass against a list that could never contain them,
 * and the four-second TTL became a probe on every pass. A grep-style check cannot tell that
 * apart from a correct prune, because the line looks right.
 *
 * <p>The question it answers is small and worth naming: a probe belongs to a <em>task</em>,
 * so it dies with the task, not with our bar.
 */
final class ProbeCache {

    private ProbeCache() {
    }

    /** How far the cache may outgrow the live task list before it is swept. */
    static final int SLACK = 8;

    /** Keeps a pass that changed nothing from allocating: the sweep is not free. */
    static boolean shouldSweep(int cached, int aliveCount) {
        return cached > aliveCount + SLACK;
    }

    /**
     * Drop every cached probe whose task is no longer in {@code aliveIds}.
     *
     * <p>{@code aliveIds} is the windowed task list the display returned this pass — every
     * task, not only the decorated ones. A window that closed leaves no entry to be found by
     * a reused task id; a window that is merely hidden keeps its measurement, which is what
     * the TTL is for.
     */
    static void sweep(Map<Integer, long[]> probed, Collection<Integer> aliveIds) {
        if (probed == null || aliveIds == null) return;
        if (!shouldSweep(probed.size(), aliveIds.size())) return;
        probed.keySet().retainAll(new HashSet<>(aliveIds));
    }
}
