package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.Button;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "There is a newer Dex-X" — asked once, on the connect screen, and never again
 * for the same build.
 *
 * <p>The phone half is a sideloaded APK: it has no store to check, and nothing
 * tells the person holding it that a build exists they have not got. So this
 * screen — the one place the app is opened on its own — asks the repository's
 * latest release what version it is and, if it is newer than this one, says so
 * with a link to it.
 *
 * <p>Three rules keep that from becoming a nag or a drain. It runs on a
 * background thread, once per launch, with short timeouts and no retry: on a
 * phone with no signal this costs nothing and says nothing. It asks at most
 * once a day, and never twice about the same version — a person who has seen
 * "0.7.2 is out" and chosen to wait is not asked again until something newer
 * exists. And it never installs anything: the answer is a link, which opens the
 * browser on the release page, where the APK is a download like any other.
 */
final class UpdateCheck {

    /** Where the newest build is announced. The same repository the APK came from. */
    private static final String API =
            "https://api.github.com/repos/Rabbihossainltd/Dex-x/releases/latest";
    /** The release page, if the JSON does not carry one: the human way to the same files. */
    private static final String RELEASES_PAGE = "https://github.com/Rabbihossainltd/Dex-x/releases/latest";

    private static final String PREFS = "dex-x-update";
    private static final String KEY_ASKED_AT = "asked-at";
    private static final String KEY_ASKED_VERSION = "asked-version";
    /** Once a day is a reminder; once a launch is a nuisance. */
    private static final long ASK_EVERY_MS = 24L * 60 * 60 * 1000;

    private static final int CONNECT_MS = 6000;
    private static final int READ_MS = 6000;

    private UpdateCheck() {
    }

    /**
     * Ask in the background, show a dialog on the calling (UI) thread if there is
     * something newer to say. Safe to call on every create: the checks that keep
     * it quiet are all here.
     */
    static void maybe(final Activity activity) {
        final Context app = activity.getApplicationContext();
        final String current = versionName(activity);
        if (current.isEmpty()) return;

        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long askedAt = prefs.getLong(KEY_ASKED_AT, 0L);
        long now = System.currentTimeMillis();
        if (now - askedAt < ASK_EVERY_MS) return;

        // Stamped before the request, not after: a phone that never reaches the
        // network must not try again on every single launch either.
        prefs.edit().putLong(KEY_ASKED_AT, now).apply();

        Thread t = new Thread(() -> {
            String json = get(API);
            if (json == null) return;
            String tag = field(json, "tag_name");
            if (tag == null) return;
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            if (compare(latest, current) <= 0) return;
            if (latest.equals(prefs.getString(KEY_ASKED_VERSION, ""))) return;

            String url = field(json, "html_url");
            if (url == null || !url.startsWith("https://")) url = RELEASES_PAGE;

            final String version = latest;
            final String link = url;
            activity.runOnUiThread(() -> show(activity, prefs, version, link));
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void show(Activity activity, SharedPreferences prefs, String latest, String url) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.upd_title, latest))
                .setMessage(activity.getString(R.string.upd_body, versionName(activity)))
                .setPositiveButton(activity.getString(R.string.upd_download), (d, w) -> {
                    prefs.edit().putString(KEY_ASKED_VERSION, latest).apply();
                    open(activity, url);
                })
                .setNegativeButton(activity.getString(R.string.upd_later), (d, w) ->
                        prefs.edit().putString(KEY_ASKED_VERSION, latest).apply())
                .create();
        dialog.show();
        // Brand the two buttons the way the rest of the app is drawn: the theme's
        // accent for the action, its dim text for the answer that ends the matter.
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        DexTheme theme = DexTheme.of(activity);
        if (positive != null) positive.setTextColor(theme.accent);
        if (negative != null) negative.setTextColor(theme.textDim);
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser: the address of the releases page is in the About block.
        }
    }

    /**
     * Ask now, and say what came back — for the About dialog's own button.
     *
     * <p>Deliberately separate from {@link #maybe}: that one keeps quiet on
     * purpose (no signal, nothing newer, asked already today), and silence is
     * exactly what makes a check the person pressed look broken. This runs on
     * the same background thread and reports all three outcomes.
     */
    static void checkNow(final Activity activity) {
        final String current = versionName(activity);
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, R.string.upd_checking, Toast.LENGTH_SHORT).show();
        Thread t = new Thread(() -> {
            String json = get(API);
            String message;
            final String[] link = new String[1];
            if (json == null) {
                message = activity.getString(R.string.upd_failed);
            } else {
                String tag = field(json, "tag_name");
                String latest = tag == null ? "" : (tag.startsWith("v") ? tag.substring(1) : tag);
                String url = field(json, "html_url");
                if (latest.isEmpty()) {
                    message = activity.getString(R.string.upd_failed);
                } else if (compare(latest, current) > 0) {
                    message = activity.getString(R.string.upd_found, latest, current);
                    link[0] = url != null && url.startsWith("https://") ? url : RELEASES_PAGE;
                } else {
                    message = activity.getString(R.string.upd_current, current);
                }
            }
            final String text = message;
            final String openUrl = link[0];
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new AlertDialog.Builder(activity)
                        .setMessage(text)
                        .setPositiveButton(openUrl != null ? R.string.upd_open : android.R.string.ok,
                                (d, w) -> {
                                    if (openUrl != null) open(activity, openUrl);
                                })
                        .show();
            });
        }, "update-check-now");
        t.setDaemon(true);
        t.start();
    }

    // ── the wire ───────────────────────────────────────────────────────────

    /** The body of a GET, or null for anything that is not a 200 in a few seconds. */
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Dex-X");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1 << 20) return null; // not a release document
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * The value of a top-level string field, without a JSON library.
     *
     * <p>Deliberate: this file is the whole of the app's network code, and it
     * reads two fields of one document — a parser would be more code than the
     * thing it parses. The search starts at the beginning of the object, and
     * GitHub's release JSON has {@code tag_name} and {@code html_url} once each
     * at that level.
     */
    private static String field(String json, String name) {
        String needle = "\"" + name + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return null;
    }

    // ── versions ───────────────────────────────────────────────────────────

    /** 0.7.10 is newer than 0.7.9, which a string compare gets wrong. */
    static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        }
        return 0;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        String[] bits = v.split("\\.");
        for (int i = 0; i < 3 && i < bits.length; i++) {
            try {
                // "-rc1" and "+build" tails are not part of the number.
                StringBuilder digits = new StringBuilder();
                for (char c : bits[i].toCharArray()) {
                    if (c < '0' || c > '9') break;
                    digits.append(c);
                }
                out[i] = digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** This build's version, from the manifest: never typed twice. */
    private static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
