package com.ccrstech.openandroiddex.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The ONLY screen this app shows on the phone.
 *
 * <p>Everything the app is for happens on a display of its own: the desktop the
 * computer makes, drawn by {@link LauncherActivity} and controlled through
 * {@link CaptionService} and the window daemon — none of it on the phone's own
 * screen, none of it something the person has to start, arrange or maintain.
 * This screen exists for the two questions that are left: is a computer
 * connected, and how do I end it. There is deliberately nothing else on it —
 * no settings, no launcher, no desktop. The phone keeps its own home screen,
 * its own apps, its own everything.
 *
 * <p>That is also why it holds no state of its own: what it shows is
 * {@link HostLink}'s beacon (the computer saying hello) and nothing else, so a
 * session that starts or ends while this is in the foreground is reflected on
 * the next tick rather than needing to be told.
 */
public class ConnectActivity extends android.app.Activity {

    /** How often the card re-reads the beacon. Two round trips of a beacon. */
    private static final long TICK_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private DexTheme theme;

    /** The card's pieces, kept so a tick can restyle them instead of rebuilding. */
    private View dot;
    private TextView status;
    private TextView detail;
    private TextView action;

    private boolean shownConnected = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * The computer's beacon, which is what "connected" means here.
     *
     * Registered for this screen's life only: the desktop's own copy of this
     * receiver lives in {@link LauncherActivity}, on the display the beacon is
     * actually addressed to.
     */
    private final BroadcastReceiver beacon = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            HostLink.seen(intent.getStringExtra("os"), intent.getStringExtra("token"));
            render();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep this process's own last few hundred log lines where the
        // diagnostics screen can read them — logcat needs a permission this
        // app does not have.
        DexLog.init(getApplicationContext());
        theme = DexTheme.of(this);
        // This screen is reachable by tapping the app's icon, so it is the one
        // place a phone left holding the home role can be handed back: with the
        // HOME filter gone from the manifest (see AndroidManifest), a phone
        // that had chosen us is asked once, politely, to choose its own.
        maybeReleaseHomeRole();
        setContentView(build());
        // A sideloaded app has no store to tell its owner that a build exists
        // they have not got. This screen answers it, once a day at most.
        UpdateCheck.maybe(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(LauncherActivity.ACTION_FILES);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(beacon, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(beacon, f);
        }
        tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(beacon);
        } catch (Exception ignored) {
        }
    }

    // ── the one screen ─────────────────────────────────────────────────────

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.windowBg());

        // A scrolling column: on a short screen (or a large font) the status,
        // both buttons and the About block do not fit, and a screen whose
        // bottom is cut off on a phone with no scroll is a screen whose last
        // row is unreachable.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(26);
        col.setPadding(pad, pad, pad, pad);

        // The mark itself — the project's own wordmark, the same art as the
        // app icon, on the white tile it was drawn on.
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setContentDescription("Dex-X");
        col.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

        // ── the status, as the screen's headline ──
        // This is the one thing the app is for, so it is not a line inside a
        // card any more: it is the sentence you see first, in the accent
        // colour that means "connected" and in grey when it does not. The card
        // below it carries the detail and the one button.

        // ── the status, big, above the card ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(18);
        col.addView(head, headLp);

        dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.rightMargin = dp(10);
        head.addView(dot, dotLp);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(28));
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(status);

        // ── the card under it: the detail and the one button ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.cardSolid);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), theme.divider);
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(26);
        col.addView(card, cardLp);

        detail = new TextView(this);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        detail.setTextColor(theme.textDim);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(10);
        card.addView(detail, detailLp);

        action = new TextView(this);
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        action.setTextColor(0xFFFFFFFF);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(26), dp(11), dp(26), dp(11));
        GradientDrawable btn = new GradientDrawable();
        btn.setColor(theme.accent);
        btn.setCornerRadius(dp(11));
        action.setBackground(btn);
        action.setOnClickListener(v -> disconnect());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(16);
        actionLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(action, actionLp);

        // ── Two buttons, then the credits ──
        //
        // "Scan a code" is the pairing route the Wi-Fi panel's QR code is for:
        // the computer puts the code on screen, and the phone's own pairing
        // scanner reads it. That scanner belongs to the system (Settings >
        // Developer options > Wireless debugging > Pair device with QR code),
        // not to this app, and there is no public way to draw it ourselves —
        // so the button opens it, with the three steps spelled out. Silently
        // omitting the route is what made "the apk has no scan option" true.
        LinearLayout extras = new LinearLayout(this);
        extras.setOrientation(LinearLayout.HORIZONTAL);
        extras.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams extrasLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        extrasLp.topMargin = dp(22);
        col.addView(extras, extrasLp);

        extras.addView(pill(getString(R.string.cx_scan), () -> scanToPair(false)));
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapLp.leftMargin = dp(10);
        extras.addView(pill(getString(R.string.cx_about), this::showAbout), gapLp);
        // Third pill: the state report. On this screen rather than buried in a
        // menu because the phone is the only place the three answers exist —
        // accessibility enabled, accessibility bound, window daemon answering —
        // and every bug report so far has been a guess at them.
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap2.leftMargin = dp(10);
        extras.addView(pill(getString(R.string.cx_diag), this::showDiagnostics), gap2);

        // ── About, the short form ──
        // Who made this, where to find them, and what the licences say. Part of
        // the same scrolling column, under everything else, so it is there when
        // someone looks for it and out of the way when they do not.
        col.addView(about(), aboutParams());

        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        render();
        return root;
    }

    /** The build this screen belongs to, from the manifest — never a literal. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** One quiet, outlined button — the second tier under the card's action. */
    private TextView pill(String label, Runnable onTap) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        b.setTextColor(theme.text);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.cardSolid);
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), theme.divider);
        b.setBackground(bg);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    /**
     * About, as a dialog worth opening: the author, the site (a link), the
     * contact, the version and the licence.
     *
     * <p>The column at the bottom of this screen is the one-line version —
     * a phone that is plugged in should not have to scroll past a brochure.
     * The button is for the person who taps on purpose.
     */
    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(6));

        TextView title = new TextView(this);
        title.setText("Dex-X " + versionName());
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(19));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView by = new TextView(this);
        by.setText(getString(R.string.cx_about_made) + " " + getString(R.string.cx_about_author));
        by.setTextColor(theme.textDim);
        by.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        by.setPadding(0, dp(6), 0, 0);
        box.addView(by);

        TextView site = new TextView(this);
        site.setText(getString(R.string.cx_about_site));
        site.setTextColor(theme.accent);
        site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        site.setPadding(0, dp(10), 0, 0);
        site.setOnClickListener(v -> open(getString(R.string.cx_about_site_url)));
        box.addView(site);

        TextView mail = new TextView(this);
        mail.setText(getString(R.string.cx_about_contact));
        mail.setTextColor(theme.accent);
        mail.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        mail.setPadding(0, dp(8), 0, 0);
        mail.setOnClickListener(v -> open("mailto:" + getString(R.string.cx_about_contact)));
        box.addView(mail);

        TextView licence = new TextView(this);
        licence.setText(getString(R.string.cx_about_licence, versionName()));
        licence.setTextColor(theme.textFaint);
        licence.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        licence.setLineSpacing(dp(2), 1f);
        licence.setPadding(0, dp(14), 0, 0);
        box.addView(licence);

        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.upd_check, (d, w) -> UpdateCheck.checkNow(this))
                .show();
    }

    /**
     * The diagnostics screen: what THIS phone believes about the desktop.
     *
     * <p>Deliberately not a debug-only build flag. Every bug in this project so
     * far has been reported as a screenshot of a symptom, and the three states
     * that matter — accessibility enabled, accessibility bound, window daemon
     * answering — are invisible from the outside, so the person reporting is
     * the only one who can see them. One button, selectable text, and a copy.
     */
    private void showDiagnostics() {
        // OFF the main thread. Half of this report is socket I/O to the window
        // daemon, and Android throws NetworkOnMainThreadException for exactly
        // that — the first build of this screen reported "PING: NO ANSWER" for
        // a daemon that was answering, because the exception BECAME the answer.
        // A diagnostic that invents failures is worse than none.
        Toast.makeText(this, getString(R.string.cx_diag_reading), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String text = Diagnostics.report(getApplicationContext());
            // Into the on-disk log as well: the same text survives the dialog,
            // so `adb pull .../files/dex-x.log` works when copying is awkward.
            DexLog.dump("diag", text);
            // …and into Downloads as a file, because a bug report is only useful if it
            // can leave the phone. Copying a 300-line dialog on a phone is not something
            // anyone should have to do.
            saveDiagnostics(text);
            runOnUiThread(() -> showDiagnosticsReport(text));
        }, "diagnostics").start();
    }

    /**
     * Write the report to Downloads/Dex-X-diagnostics.txt.
     *
     * <p>MediaStore, so no storage permission is involved and the file is where a person
     * would look for it — Attach on an email or a chat finds it without a file manager.
     * Every failure is silent: the dialog and the app's own log still carry the text.
     */
    private void saveDiagnostics(String text) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Dex-X-diagnostics.txt");
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                    if (os != null) {
                        os.write(text.getBytes("UTF-8"));
                        os.close();
                    }
                    DexLog.step("diag", "report written to Downloads/Dex-X-diagnostics.txt");
                    return;
                }
            }
            java.io.File dir = getExternalFilesDir(null);
            java.io.File out = new java.io.File(dir, "Dex-X-diagnostics.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            DexLog.step("diag", "report written to " + out.getAbsolutePath());
        } catch (Throwable t) {
            DexLog.warn("diag", "could not write the report to a file", t);
        }
    }

    private void showDiagnosticsReport(String text) {
        if (isFinishing()) return;
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        body.setTextColor(theme.text);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.cx_diag_title))
                .setView(scroll)
                .setPositiveButton(getString(R.string.cx_diag_copy), (d, w) -> {
                    android.content.ClipboardManager cb =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Dex-X", text));
                        Toast.makeText(this, getString(R.string.cx_diag_copied),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(getString(R.string.cx_diag_share), (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text);
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.cx_diag_title)));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Open the phone's own "pair with a QR code" scanner, or say how to reach it.
     *
     * <p>A developer-options screen is not public API, and the pairing scanner
     * inside it has no intent of its own: every Android release moves the bricks
     * under it, and a deep link that works on one OEM opens the wrong page on
     * the next. So the intent below is the best-known one, and the dialog is the
     * answer when it lands somewhere else — which is why it is written as
     * instructions rather than as a promise.
     */
    private void scanToPair(boolean ignored) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.cx_scan_title))
                .setMessage(getString(R.string.cx_scan_body))
                .setPositiveButton(getString(R.string.cx_scan_open), (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                        } catch (Exception ignored2) {
                            // A phone with no Settings app is not a phone.
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser: the address is on screen either way.
        }
    }

    /**
     * The About block: the author, the site, and the two facts a sideloaded app
     * owes the person who installed it.
     *
     * Deliberately plain text and one link. This screen exists for one question
     * and one action; About is where someone goes when they already know that,
     * and every row added here is a row between them and Disconnect.
     */
    private View about() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        TextView made = new TextView(this);
        made.setText(getString(R.string.cx_about_made));
        made.setTextColor(theme.textDim);
        made.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
        made.setGravity(Gravity.CENTER);
        box.addView(made);

        TextView author = new TextView(this);
        author.setText(getString(R.string.cx_about_author));
        author.setTextColor(theme.text);
        author.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
        author.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        author.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams authorLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authorLp.topMargin = dp(3);
        box.addView(author, authorLp);

        TextView site = new TextView(this);
        site.setText(getString(R.string.cx_about_site));
        site.setTextColor(theme.accent);
        site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        site.setGravity(Gravity.CENTER);
        site.setPadding(0, dp(6), 0, dp(2));
        site.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(getString(R.string.cx_about_site_url))));
            } catch (Exception ignored) {
                // No browser on a locked-down phone: the address is on screen anyway.
            }
        });
        box.addView(site);

        TextView licence = new TextView(this);
        licence.setText(getString(R.string.cx_about_licence, versionName()));
        licence.setTextColor(theme.textFaint);
        licence.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11));
        licence.setGravity(Gravity.CENTER);
        licence.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams licenceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        licenceLp.topMargin = dp(8);
        box.addView(licence, licenceLp);
        return box;
    }

    /** The About block's place in the column: under everything, never squashed. */
    private LinearLayout.LayoutParams aboutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        return lp;
    }

    /** One pass over the beacon: connected or not, and what to say about it. */
    private void render() {
        if (status == null) return;
        boolean connected = HostLink.available();
        // The quick-settings tile reads the same beacon, so it goes stale at the
        // same moment this card does. Ask the system to re-read it on the CHANGE
        // only: a request per tick would be a binder call every 1.2 s for as long
        // as this screen is open, to say nothing new.
        if (connected != shownConnected) DexTileService.refresh(this);
        shownConnected = connected;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(connected ? theme.positive : theme.textFaint);
        dot.setBackground(d);
        if (connected) {
            status.setText(getString(R.string.cx_connected));
            status.setTextColor(theme.text);
            String pc = deviceName();
            detail.setText(pc.isEmpty()
                    ? getString(R.string.cx_connected_sub)
                    : getString(R.string.cx_connected_to, pc));
            action.setText(getString(R.string.cx_disconnect));
            action.setVisibility(View.VISIBLE);
        } else {
            status.setText(getString(R.string.cx_not_connected));
            status.setTextColor(theme.textDim);
            detail.setText(getString(R.string.cx_not_connected_sub));
            action.setVisibility(View.GONE);
        }
    }

    /**
     * What to call the computer.
     *
     * Only the OS the beacon reports: the computer's own name travels over the
     * file service's HELLO, which is per-session and lives in the transfer
     * stack — not something this screen should spin up a socket to learn, and
     * not something worth showing if it costs that.
     */
    private String deviceName() {
        String os = HostLink.os();
        if (os == null) return "";
        switch (os) {
            case "win": return "Windows PC";
            case "mac": return "Mac";
            case "linux": return "Linux PC";
            default: return os;
        }
    }

    /**
     * End the session, from the phone.
     *
     * The same request the desktop's own exit raises: the display belongs to
     * the computer, and so does everything the desktop profile switched on
     * here, so the computer is the only side that can put it all back. Raising
     * it is all this button does — the card then follows the beacon, and says
     * "not connected" once it stops.
     */
    private void disconnect() {
        if (!shownConnected) return;
        RequestProvider.enqueue("exit", "dex");
        DexLog.step("connect", "disconnect requested from the app screen");
        Toast.makeText(this, getString(R.string.cx_disconnecting), Toast.LENGTH_SHORT).show();
        // Nothing else on this side to undo: the display, the caption service
        // and the window daemon are all the computer's to take down.
    }

    /**
     * Give the home role back, once, on a phone that had given it to us.
     *
     * Older builds offered to become the phone's home screen. That is not what
     * this app is for any more, and a stale choice would keep the phone's own
     * launcher from coming back after every restart — so the first time this
     * screen is opened on such a phone, the chooser is put up. Once per phone:
     * a user who genuinely wants another home screen is not asked again.
     */
    private void maybeReleaseHomeRole() {
        if (!LauncherActivity.isDefaultHome(this)) return;
        if (DexPrefs.getBool(this, DexPrefs.KEY_HOME_RELEASED, false)) return;
        DexPrefs.put(this, DexPrefs.KEY_HOME_RELEASED, true);
        DexLog.step("connect", "this app still holds the home role — handing it back");
        LauncherActivity.openHomeChooser(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
