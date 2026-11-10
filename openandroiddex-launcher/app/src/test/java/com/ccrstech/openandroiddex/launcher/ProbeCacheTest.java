package com.ccrstech.openandroiddex.launcher;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The probe cache against every state it is asked about. Plain {@code main}, like
 * {@code CaptionRuleTest}: no JUnit, no Android, nothing but a JDK.
 *
 * <p>Case 1 is the one that matters. It is the bug: a live window that our renderer does not
 * draw a bar for must keep its measurement. The first prune kept only windows WITH a bar and
 * so threw these away every pass.
 */
public final class ProbeCacheTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 1. A live, undecorated window keeps its measurement. (The bug.)
        Map<Integer, long[]> p = cache(7, 8, 9, 10, 11);
        ProbeCache.sweep(p, Arrays.asList(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21));
        check("a live task with no bar of ours keeps its probe", keeps(p, 7, 8, 9, 10, 11));

        // 2. Below the slack a dead entry may sit — by design, so that a pass which
        // changed nothing allocates nothing. It is harmless because seenCaption() never
        // trusts a cached value past PROBE_TTL_MS (4 s), so a reused task id can inherit
        // nothing older than that no matter what the sweep does.
        p = cache(7, 8, 9, 10, 11);
        ProbeCache.sweep(p, Arrays.asList(7, 8, 9, 10));
        check("below the slack a dead entry lingers (TTL bounds its age, not the sweep)",
                p.size() == 5);

        // 2b. Once the cache outgrows alive + SLACK, everything dead goes at once.
        p = cache(7, 8, 9, 10, 11, 12, 13, 14, 15, 16);   // 10 cached
        ProbeCache.sweep(p, Arrays.asList(7));             // 1 alive: 10 > 1 + 8
        check("above the slack the closed tasks lose their probes",
                p.size() == 1 && p.containsKey(7));

        // 3. Below the slack nothing is touched, even if every entry is dead.
        p = cache(1, 2, 3, 4, 5);
        ProbeCache.sweep(p, Arrays.asList(90));
        check("a small cache is left alone rather than swept every pass", p.size() == 5);

        // 4. At exactly the threshold it still waits — the sweep costs an allocation.
        p = cache(1, 2, 3, 4, 5, 6, 7, 8, 9);
        ProbeCache.sweep(p, Arrays.asList(90));
        check("exactly alive+8 does not sweep", p.size() == 9);

        // 5. One past it does.
        p = cache(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        ProbeCache.sweep(p, Arrays.asList(90));
        check("alive+9 sweeps", p.isEmpty());

        // 6. A hidden window stays; only the task list decides, not visibility.
        p = cache(4, 6);
        ProbeCache.sweep(p, Arrays.asList(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
        check("a task still in the list keeps its probe", keeps(p, 4, 6));

        // 7. Nothing on screen: a cache small enough to be irrelevant is left alone,
        // and one that has grown is cleared. The map can never be larger than
        // alive + SLACK once a sweep has run.
        p = cache(4, 6);
        ProbeCache.sweep(p, Arrays.asList());
        check("an empty display leaves a small cache alone", p.size() == 2);
        p = cache(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        ProbeCache.sweep(p, Arrays.asList());
        check("an empty display clears a grown cache", p.isEmpty());

        // 8. Nulls never throw — this runs on a handler thread.
        ProbeCache.sweep(null, Arrays.asList(1));
        ProbeCache.sweep(cache(1), null);
        check("null arguments are ignored", true);

        // 9. task id 0 and negative ids are ordinary keys, not a sentinel.
        p = cache(0, -1, 5);
        ProbeCache.sweep(p, Arrays.asList(0, -1, 5, 6, 7, 8, 9, 10, 11, 12, 13));
        check("task 0 and -1 survive like any other", keeps(p, 0, -1, 5));

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " PROBE CACHE CASE(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL PROBE CACHE CASES PASS (" + passed + ")");
    }

    private static Map<Integer, long[]> cache(int... ids) {
        Map<Integer, long[]> m = new LinkedHashMap<>();
        for (int id : ids) m.put(id, new long[]{40, 1000});
        return m;
    }

    private static boolean keeps(Map<Integer, long[]> m, int... ids) {
        for (int id : ids) if (!m.containsKey(id)) return false;
        return true;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}
