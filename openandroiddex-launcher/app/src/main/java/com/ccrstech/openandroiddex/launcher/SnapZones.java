package com.ccrstech.openandroiddex.launcher;

/**
 * Where a window lands when it is dropped against an edge or corner.
 *
 * <p>Geometry only — no Android types, no window manager — because this is the
 * one part of snapping that can be reasoned about on its own: the same zone
 * has to produce the same rect for the drag preview, for the release that
 * follows it, and for the layout picker on the maximise button. A preview that
 * computes its rect differently from the snap it is previewing is worse than no
 * preview at all, so both go through {@link #rect}.
 *
 * <p>Zones follow Windows, which is the muscle memory this desktop is borrowing:
 * an edge is a half, a corner is a quarter, and the top edge is a maximise.
 */
final class SnapZones {

    static final int NONE = 0;
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int TOP_LEFT = 3;
    static final int TOP_RIGHT = 4;
    static final int BOTTOM_LEFT = 5;
    static final int BOTTOM_RIGHT = 6;
    /** Top edge, middle: maximise — the same rect the ▢ button sends. */
    static final int MAXIMISE = 7;

    private SnapZones() {
    }

    /**
     * The zone a point is in, or {@link #NONE}.
     *
     * @param w      display width in its own pixels
     * @param bottom the lowest row a window may occupy: the display's height
     *               less the taskbar, when the taskbar takes one
     * @param edge   how close to an edge counts, in the same pixels
     */
    static int at(int w, int bottom, float x, float y, int edge) {
        if (w <= 0 || bottom <= 0) return NONE;
        // A band wider than a third of the display would make the middle
        // unreachable on a small one, so the edges give way rather than tile
        // the whole screen in corner.
        int e = Math.min(edge, Math.min(w, bottom) / 3);
        if (e <= 0) return NONE;
        boolean left = x <= e;
        boolean right = x >= w - e;
        boolean top = y <= e;
        boolean low = y >= bottom - e;
        if (left && top) return TOP_LEFT;
        if (right && top) return TOP_RIGHT;
        if (left && low) return BOTTOM_LEFT;
        if (right && low) return BOTTOM_RIGHT;
        if (left) return LEFT;
        if (right) return RIGHT;
        if (top) return MAXIMISE;
        return NONE;
    }

    /** The rect a zone occupies, as {left, top, right, bottom}. */
    static int[] rect(int zone, int w, int bottom) {
        if (zone == NONE || w <= 0 || bottom <= 0) return null;
        int hx = w / 2;
        int hy = bottom / 2;
        switch (zone) {
            case LEFT:
                return new int[]{0, 0, hx, bottom};
            case RIGHT:
                return new int[]{hx, 0, w, bottom};
            case TOP_LEFT:
                return new int[]{0, 0, hx, hy};
            case TOP_RIGHT:
                return new int[]{hx, 0, w, hy};
            case BOTTOM_LEFT:
                return new int[]{0, hy, hx, bottom};
            case BOTTOM_RIGHT:
                return new int[]{hx, hy, w, bottom};
            case MAXIMISE:
                // The same 4px width inset the PC enforcer and the caption
                // agree on: that inset is how a window WE maximised is told
                // apart from one the platform made full width, and it is the
                // only reason the enforcer's `fills` test does not undo this.
                return new int[]{0, 0, w - 4, bottom};
            default:
                return null;
        }
    }

    /** Does this zone lay the window over the full height of the display? */
    static boolean isHalf(int zone) {
        return zone == LEFT || zone == RIGHT || zone == MAXIMISE;
    }

    static String name(int zone) {
        switch (zone) {
            case LEFT: return "left half";
            case RIGHT: return "right half";
            case TOP_LEFT: return "top left";
            case TOP_RIGHT: return "top right";
            case BOTTOM_LEFT: return "bottom left";
            case BOTTOM_RIGHT: return "bottom right";
            case MAXIMISE: return "maximise";
            default: return "none";
        }
    }
}
