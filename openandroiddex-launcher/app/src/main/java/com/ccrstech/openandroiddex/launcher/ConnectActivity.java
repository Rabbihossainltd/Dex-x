package com.ccrstech.openandroiddex.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
        theme = DexTheme.of(this);
        // This screen is reachable by tapping the app's icon, so it is the one
        // place a phone left holding the home role can be handed back: with the
        // HOME filter gone from the manifest (see AndroidManifest), a phone
        // that had chosen us is asked once, politely, to choose its own.
        maybeReleaseHomeRole();
        setContentView(build());
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

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(26);
        col.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        col.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = new TextView(this);
        title.setText("Dex-X");
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        col.addView(title, titleLp);

        // ── the status card ──
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head);

        dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(9);
        head.addView(dot, dotLp);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(status);

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

        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                Math.min(dp(430), ViewGroup.LayoutParams.MATCH_PARENT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        wrap.addView(col, colLp);
        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        render();
        return root;
    }

    /** One pass over the beacon: connected or not, and what to say about it. */
    private void render() {
        if (status == null) return;
        boolean connected = HostLink.available();
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
