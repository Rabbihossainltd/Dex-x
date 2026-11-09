package com.ccrstech.openandroiddex.launcher;

/**
 * The shared folder's settings.
 *
 * Everything else that used to live here was the Web viewer — a browser as a
 * second seat at this phone — and it is gone: it was a whole MediaProjection +
 * WebRTC stack, four megabytes of native code per ABI, for a feature the
 * desktop app does not use. What remains is the one preference the file
 * transfer reads, under the same key it has always used, so a phone that
 * upgraded keeps the folder it was told to open at.
 *
 * <p>The prefix is also the reason none of these repaints the shell:
 * {@link DexPrefs#affectsShell} skips it, exactly as it skips the PC's own
 * settings. Nothing on the desktop is drawn from a file-transfer setting.
 */
final class Web {

    private Web() {
    }

    static final String PREFIX = "web_";

    static final String KEY_ROOT = PREFIX + "root";

    /** Where a transfer opens when the user has never chosen: the whole phone. */
    static final String DEF_ROOT = "/sdcard";

    /** Where a dropped file lands: Downloads. */
    static final String DEF_UPLOAD_DIR = "/sdcard/Download";

    /** The folder a transfer opens at: the user's choice, else the whole phone. */
    static String root(android.content.Context ctx) {
        String r = DexPrefs.getString(ctx, KEY_ROOT, DEF_ROOT);
        return r.isEmpty() ? DEF_ROOT : r;
    }
}
