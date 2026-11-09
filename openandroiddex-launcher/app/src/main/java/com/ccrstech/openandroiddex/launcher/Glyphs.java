package com.ccrstech.openandroiddex.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The shell's icons, drawn — never typed.
 *
 * <p>Every one of these used to be an emoji in a TextView: 🔍 for search, 📁 for the
 * folder tile, 🔔 for the notification bell, 🖱 for the touchpad, ⚙ 📊 in the app menu.
 * An emoji is not an icon. It is whatever glyph the phone's emoji font happens to
 * carry: it renders differently on every OEM, it is coloured when everything around
 * it is not, it is a different optical size and weight from every other control in
 * the bar, and on a phone with an old font it is a box. Reported three times
 * ("ekhono emoji icon keno?") and right every time.
 *
 * <p>These are drawn from paths at 24×24, then scaled to whatever the caller asks
 * for — the same way {@link Win11} draws its logo and {@link DexCursors} draws its
 * pointers, and for the same reason: the desktop is drawn at whatever density
 * {@code wm density} last set, and a bitmap icon would have to be re-rendered for
 * each.
 *
 * <p>Stroke weight is in the 24-unit space and is scaled with the icon, so a 16 px
 * bell and a 32 px bell look like the same bell rather than like two weights.
 */
final class Glyphs {

    private Glyphs() {
    }

    // Names. Strings rather than an enum so a caller can hold one in a field and a
    // layout helper can be handed a default when the name is unknown.
    static final String SEARCH = "search";
    static final String FOLDER = "folder";
    static final String BELL = "bell";
    static final String GEAR = "gear";
    static final String CHART = "chart";
    static final String MOUSE = "mouse";
    static final String PHONE = "phone";
    static final String PERSON = "person";
    static final String BOLT = "bolt";
    static final String BATTERY = "battery";
    static final String GRID = "grid";
    static final String BACK = "back";
    static final String TASKS = "tasks";
    static final String MONITOR = "monitor";
    static final String POWER = "power";
    static final String TRASH = "trash";
    static final String DOT = "dot";
    static final String PLUG = "plug";
    static final String TEMP = "temp";
    static final String HEART = "heart";
    static final String FLASK = "flask";
    static final String LOOP = "loop";
    static final String WIFI = "wifi";
    static final String SIGNAL = "signal";
    static final String BLUETOOTH = "bluetooth";
    static final String AIRPLANE = "airplane";
    static final String MUTE = "mute";
    static final String ROTATE = "rotate";
    static final String LOCATION = "location";
    static final String TORCH = "torch";
    static final String CAMERA = "camera";
    static final String LOCK = "lock";
    static final String GLOBE = "globe";
    static final String IMAGE = "image";
    static final String CLIPBOARD = "clipboard";
    static final String INFO = "info";
    static final String PALETTE = "palette";
    static final String FILE = "file";
    static final String HALF = "half";
    static final String LAYERS = "layers";

    /** A drawn icon: fixed to its 24-unit grid, sized to whatever bounds it is given. */
    static final class Icon extends Drawable {
        private final String name;
        private final int colour;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Icon(String name, int colour) {
            this.name = name;
            this.colour = colour;
            fill.setColor(colour);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(colour);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            if (s <= 0f) return;
            c.save();
            c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f);
            c.scale(s, s);
            stroke.setStrokeWidth(1.9f);
            paint(c, name);
            c.restore();
        }

        private void paint(Canvas c, String n) {
            path.reset();
            if (SEARCH.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(10.5f, 10.5f, 6.2f, stroke);
                c.drawLine(15.2f, 15.2f, 20.5f, 20.5f, stroke);
            } else if (BACK.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f);
                c.drawPath(path, stroke);
            } else if (FOLDER.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                r.set(3f, 6.5f, 21f, 19f);
                c.drawRoundRect(r, 2.4f, 2.4f, stroke);
                path.moveTo(3f, 10f); path.lineTo(3f, 8.5f);
                path.quadTo(3f, 6.5f, 5.2f, 6.5f);
                path.lineTo(9.4f, 6.5f); path.lineTo(11.2f, 9f); path.lineTo(18.6f, 9f);
                c.drawPath(path, stroke);
            } else if (BELL.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(6f, 16.5f);
                path.lineTo(6.6f, 15f); path.lineTo(6.6f, 10.5f);
                path.cubicTo(6.6f, 7.4f, 9f, 5f, 12f, 5f);
                path.cubicTo(15f, 5f, 17.4f, 7.4f, 17.4f, 10.5f);
                path.lineTo(17.4f, 15f); path.lineTo(18f, 16.5f);
                path.close();
                c.drawPath(path, stroke);
                c.drawLine(10.2f, 19.2f, 13.8f, 19.2f, stroke);
            } else if (GEAR.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                float[] teeth = {0, 45, 90, 135, 180, 225, 270, 315};
                for (float deg : teeth) {
                    c.save();
                    c.rotate(deg, 12f, 12f);
                    c.drawRoundRect(new RectF(10.6f, 2.6f, 13.4f, 6.4f), 1f, 1f, fill);
                    c.restore();
                }
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 12f, 6.4f, stroke);
                c.drawCircle(12f, 12f, 2.3f, stroke);
            } else if (CHART.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(4.5f, 12.5f, 7.5f, 19.5f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(10.5f, 7.5f, 13.5f, 19.5f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(16.5f, 10f, 19.5f, 19.5f), 1f, 1f, fill);
            } else if (MOUSE.equals(n) || PHONE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                if (MOUSE.equals(n)) {
                    r.set(6.5f, 2.8f, 17.5f, 21.2f);
                    c.drawRoundRect(r, 5.5f, 5.5f, stroke);
                    c.drawLine(12f, 5.6f, 12f, 10.2f, stroke);
                } else {
                    r.set(6.8f, 2.6f, 17.2f, 21.4f);
                    c.drawRoundRect(r, 2.6f, 2.6f, stroke);
                    c.drawLine(10.4f, 18.2f, 13.6f, 18.2f, stroke);
                }
            } else if (PERSON.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 8.4f, 3.7f, stroke);
                path.reset();
                path.moveTo(4.8f, 20.2f);
                path.cubicTo(5.6f, 15.6f, 8.4f, 13.6f, 12f, 13.6f);
                path.cubicTo(15.6f, 13.6f, 18.4f, 15.6f, 19.2f, 20.2f);
                c.drawPath(path, stroke);
            } else if (BOLT.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(13.4f, 2.4f); path.lineTo(5.6f, 13.6f); path.lineTo(11f, 13.6f);
                path.lineTo(10.2f, 21.6f); path.lineTo(18.4f, 10.2f); path.lineTo(12.8f, 10.2f);
                path.close();
                c.drawPath(path, fill);
            } else if (BATTERY.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                r.set(2.6f, 7.4f, 18.6f, 16.6f);
                c.drawRoundRect(r, 2.2f, 2.2f, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(19.6f, 10.2f, 21.6f, 13.8f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(4.8f, 9.4f, 12.4f, 14.6f), 0.8f, 0.8f, fill);
            } else if (GRID.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                float x0 = 3.6f, y0 = 3.6f, w = 7.2f, gap = 2.4f;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        c.drawRoundRect(new RectF(x0 + i * (w + gap), y0 + j * (w + gap),
                                x0 + i * (w + gap) + w, y0 + j * (w + gap) + w), 1.4f, 1.4f, fill);
                    }
                }
            } else if (TASKS.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3.4f, 4.2f, 13.4f, 14.2f), 1.8f, 1.8f, stroke);
                c.drawRoundRect(new RectF(10.6f, 9.8f, 20.6f, 19.8f), 1.8f, 1.8f, stroke);
            } else if (MONITOR.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(2.8f, 4.4f, 21.2f, 16.2f), 1.8f, 1.8f, stroke);
                c.drawLine(12f, 16.2f, 12f, 19.4f, stroke);
                c.drawLine(8.4f, 20.4f, 15.6f, 20.4f, stroke);
            } else if (POWER.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawArc(new RectF(4.4f, 4.8f, 19.6f, 20f), -60f, 300f, false, stroke);
                c.drawLine(12f, 2.6f, 12f, 10.6f, stroke);
            } else if (TRASH.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawLine(4.2f, 6.8f, 19.8f, 6.8f, stroke);
                c.drawLine(9.2f, 4.2f, 14.8f, 4.2f, stroke);
                path.moveTo(6.2f, 6.8f); path.lineTo(7.4f, 20.2f);
                path.lineTo(16.6f, 20.2f); path.lineTo(17.8f, 6.8f);
                c.drawPath(path, stroke);
                c.drawLine(10.4f, 10f, 10.6f, 17f, stroke);
                c.drawLine(13.6f, 10f, 13.4f, 17f, stroke);
            } else if (DOT.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 12f, 4.2f, fill);
            } else if (PLUG.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawLine(9f, 3.4f, 9f, 7f, stroke);
                c.drawLine(15f, 3.4f, 15f, 7f, stroke);
                r.set(6.4f, 7f, 17.6f, 14.4f);
                c.drawRoundRect(r, 2f, 2f, stroke);
                c.drawLine(12f, 14.4f, 12f, 20.6f, stroke);
            } else if (TEMP.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawLine(12f, 4.2f, 12f, 14.6f, stroke);
                c.drawCircle(12f, 17.4f, 3.4f, stroke);
                c.drawLine(14.4f, 6.6f, 16.4f, 6.6f, stroke);
                c.drawLine(14.4f, 10f, 16.4f, 10f, stroke);
            } else if (HEART.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(12f, 20.4f);
                path.cubicTo(3.4f, 14.6f, 2.8f, 9.6f, 5.6f, 6.6f);
                path.cubicTo(8.4f, 3.6f, 11.4f, 4.6f, 12f, 7.2f);
                path.cubicTo(12.6f, 4.6f, 15.6f, 3.6f, 18.4f, 6.6f);
                path.cubicTo(21.2f, 9.6f, 20.6f, 14.6f, 12f, 20.4f);
                path.close();
                c.drawPath(path, fill);
            } else if (FLASK.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(9.4f, 3.4f); path.lineTo(14.6f, 3.4f);
                path.moveTo(10.4f, 3.6f); path.lineTo(10.4f, 9.4f);
                path.lineTo(5.6f, 18.6f); path.quadTo(4.4f, 20.6f, 6.6f, 20.6f);
                path.lineTo(17.4f, 20.6f); path.quadTo(19.6f, 20.6f, 18.4f, 18.6f);
                path.lineTo(13.6f, 9.4f); path.lineTo(13.6f, 3.6f);
                c.drawPath(path, stroke);
                c.drawLine(7.6f, 14.4f, 16.4f, 14.4f, stroke);
            } else if (LOOP.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawArc(new RectF(4.2f, 4.2f, 19.8f, 19.8f), 40f, 250f, false, stroke);
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(18.6f, 2.6f); path.lineTo(21.4f, 8.4f); path.lineTo(15.4f, 7.6f);
                path.close();
                c.drawPath(path, fill);
            } else if (WIFI.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawArc(new RectF(2.4f, 3.6f, 21.6f, 19.4f), 215f, 110f, false, stroke);
                c.drawArc(new RectF(5.8f, 7.4f, 18.2f, 19.4f), 215f, 110f, false, stroke);
                c.drawArc(new RectF(9.2f, 11.2f, 14.8f, 19.4f), 215f, 110f, false, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 18.4f, 1.7f, fill);
            } else if (SIGNAL.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(3.6f, 15.6f, 6.6f, 20.4f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(8.4f, 11.4f, 11.4f, 20.4f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(13.2f, 7.2f, 16.2f, 20.4f), 1f, 1f, fill);
                c.drawRoundRect(new RectF(18f, 3.4f, 21f, 20.4f), 1f, 1f, fill);
            } else if (BLUETOOTH.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(8.4f, 7.4f); path.lineTo(15.6f, 16.6f);
                path.lineTo(12f, 20.4f); path.lineTo(12f, 3.6f);
                path.lineTo(15.6f, 7.4f); path.lineTo(8.4f, 16.6f);
                c.drawPath(path, stroke);
            } else if (AIRPLANE.equals(n)) {
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(12f, 2.6f);
                path.lineTo(13.8f, 9.4f); path.lineTo(21.4f, 13.6f);
                path.lineTo(21.4f, 15.4f); path.lineTo(13.8f, 13.8f);
                path.lineTo(13.4f, 18.4f); path.lineTo(16.2f, 20.2f);
                path.lineTo(16.2f, 21.4f); path.lineTo(12f, 20.2f);
                path.lineTo(7.8f, 21.4f); path.lineTo(7.8f, 20.2f);
                path.lineTo(10.6f, 18.4f); path.lineTo(10.2f, 13.8f);
                path.lineTo(2.6f, 15.4f); path.lineTo(2.6f, 13.6f);
                path.lineTo(10.2f, 9.4f); path.close();
                c.drawPath(path, fill);
            } else if (MUTE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(4.4f, 9.4f); path.lineTo(8.4f, 9.4f);
                path.lineTo(13.4f, 5.4f); path.lineTo(13.4f, 18.6f);
                path.lineTo(8.4f, 14.6f); path.lineTo(4.4f, 14.6f);
                path.close();
                c.drawPath(path, stroke);
                c.drawLine(16.6f, 9.4f, 21f, 14.6f, stroke);
                c.drawLine(21f, 9.4f, 16.6f, 14.6f, stroke);
            } else if (ROTATE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawArc(new RectF(4.2f, 4.6f, 19.8f, 19.4f), 120f, 250f, false, stroke);
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(19.4f, 2.8f); path.lineTo(22f, 8.6f); path.lineTo(16f, 8.2f);
                path.close();
                c.drawPath(path, fill);
            } else if (LOCATION.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(12f, 21f);
                path.cubicTo(16.4f, 16.4f, 18.4f, 13f, 18.4f, 10.2f);
                path.cubicTo(18.4f, 6.6f, 15.6f, 3.8f, 12f, 3.8f);
                path.cubicTo(8.4f, 3.8f, 5.6f, 6.6f, 5.6f, 10.2f);
                path.cubicTo(5.6f, 13f, 7.6f, 16.4f, 12f, 21f);
                path.close();
                c.drawPath(path, stroke);
                c.drawCircle(12f, 10.2f, 2.4f, stroke);
            } else if (TORCH.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(8.6f, 3.2f); path.lineTo(15.4f, 3.2f);
                path.lineTo(15.4f, 7.4f); path.lineTo(13.4f, 10f);
                path.lineTo(13.4f, 20.2f); path.lineTo(10.6f, 20.2f);
                path.lineTo(10.6f, 10f); path.lineTo(8.6f, 7.4f);
                path.close();
                c.drawPath(path, stroke);
            } else if (CAMERA.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(2.8f, 6.4f, 21.2f, 19.4f), 2.6f, 2.6f, stroke);
                path.moveTo(8.4f, 6.4f); path.lineTo(10f, 3.8f);
                path.lineTo(14f, 3.8f); path.lineTo(15.6f, 6.4f);
                c.drawPath(path, stroke);
                c.drawCircle(12f, 12.9f, 3.9f, stroke);
            } else if (GLOBE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 12f, 8.6f, stroke);
                c.drawLine(3.6f, 12f, 20.4f, 12f, stroke);
                c.drawOval(new RectF(8.2f, 3.4f, 15.8f, 20.6f), stroke);
            } else if (IMAGE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(3.2f, 4.6f, 20.8f, 19.4f), 2.2f, 2.2f, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(8.4f, 9.4f, 1.7f, fill);
                path.moveTo(5.6f, 17.6f); path.lineTo(10.4f, 12.4f);
                path.lineTo(14f, 16.2f); path.lineTo(16.6f, 13.4f); path.lineTo(19.4f, 17.6f);
                c.drawPath(path, stroke);
            } else if (CLIPBOARD.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(5f, 4.6f, 19f, 20.4f), 2.2f, 2.2f, stroke);
                path.moveTo(9f, 4.6f); path.lineTo(9f, 2.8f);
                path.lineTo(15f, 2.8f); path.lineTo(15f, 4.6f);
                c.drawPath(path, stroke);
                c.drawLine(8.4f, 10f, 15.6f, 10f, stroke);
                c.drawLine(8.4f, 14f, 15.6f, 14f, stroke);
                c.drawLine(8.4f, 18f, 12.6f, 18f, stroke);
            } else if (INFO.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 12f, 8.8f, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 7.9f, 1.5f, fill);
                c.drawRoundRect(new RectF(10.9f, 10.4f, 13.1f, 17f), 1.1f, 1.1f, fill);
            } else if (PALETTE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(12f, 3.2f);
                path.cubicTo(6f, 3.2f, 2.8f, 8f, 2.8f, 12f);
                path.cubicTo(2.8f, 16.6f, 6.4f, 20.4f, 11f, 20.4f);
                path.cubicTo(13.2f, 20.4f, 13.4f, 18.6f, 12.4f, 17.4f);
                path.cubicTo(11.4f, 16.2f, 12.2f, 14.4f, 14.2f, 14.4f);
                path.lineTo(17.4f, 14.4f);
                path.cubicTo(20.4f, 14.4f, 21.2f, 11.4f, 20.2f, 8.6f);
                path.cubicTo(19f, 5.4f, 15.8f, 3.2f, 12f, 3.2f);
                c.drawPath(path, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(8f, 9f, 1.5f, fill);
                c.drawCircle(12.6f, 7.2f, 1.5f, fill);
                c.drawCircle(7.2f, 13.6f, 1.5f, fill);
            } else if (FILE.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                path.moveTo(6f, 2.8f); path.lineTo(14.4f, 2.8f); path.lineTo(19f, 7.6f);
                path.lineTo(19f, 21.2f); path.lineTo(6f, 21.2f); path.close();
                c.drawPath(path, stroke);
                c.drawLine(14.2f, 3.2f, 14.2f, 8f, stroke);
                c.drawLine(14.2f, 8f, 18.6f, 8f, stroke);
            } else if (HALF.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 12f, 8.4f, stroke);
                fill.setStyle(Paint.Style.FILL);
                c.drawArc(new RectF(3.6f, 3.6f, 20.4f, 20.4f), 90f, 180f, true, fill);
            } else if (LAYERS.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(4f, 3.4f, 20f, 9.4f), 1.6f, 1.6f, stroke);
                c.drawRoundRect(new RectF(4f, 10.4f, 20f, 15.4f), 1.6f, 1.6f, stroke);
                c.drawRoundRect(new RectF(4f, 16.4f, 20f, 20.6f), 1.6f, 1.6f, stroke);
            } else if (LOCK.equals(n)) {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(4.8f, 10.6f, 19.2f, 20.6f), 2.2f, 2.2f, stroke);
                path.moveTo(8.2f, 10.6f); path.lineTo(8.2f, 8.4f);
                path.cubicTo(8.2f, 4.6f, 15.8f, 4.6f, 15.8f, 8.4f);
                path.lineTo(15.8f, 10.6f);
                c.drawPath(path, stroke);
            } else {
                stroke.setStyle(Paint.Style.STROKE);
                c.drawCircle(12f, 12f, 7.4f, stroke);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
            stroke.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 24;
        }

        @Override
        public int getIntrinsicHeight() {
            return 24;
        }
    }

    /** An icon at a colour, sized by its bounds when drawn. */
    static Drawable of(String name, int colour) {
        return new Icon(name, colour);
    }
}
