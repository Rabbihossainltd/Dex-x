"""Dex-X's mark, drawn from ONE pair of source images, for every place it ships.

    python3 scripts/brand-art.py

Why this exists: the launcher icon on the phone was never part of the rebrand.
It kept the original hand-written `ic_launcher_foreground.xml` — a laptop with a
phone in front of it, i.e. the mark this project was forked from — so the phone's
home screen showed the OLD logo while the desktop showed the new one (reported as
"logo ager ta keno?"). And the desktop's own icon set carried the WORDMARK at
every size, including 16 and 32 px, where a 3.7:1 wordmark is about six pixels
tall and reads as a smudge.

The rule, applied here and nowhere else:

    below 64 px   the X alone, full bleed      (a mark that is legible at 16 px)
    64 px and up  the full Dex-X wordmark      (the name, once there is room)

Everything is written from `scripts/brand-src/{x,wordmark}.png`, so the two
cannot drift apart again.

Writes:

    src-tauri/icons/*.png, icon.ico, icon.icns
        the Windows exe, its installer and its taskbar button
    src-tauri/resources/icon/scrcpy.png
        the mirror window's icon (scrcpy reads it by that name)
    public/logo*.png
        the app's own UI (TitleBar)
    ../openandroiddex-launcher/app/src/main/res/drawable-nodpi/
        the launcher icon's adaptive foreground + monochrome layers
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)                       # open-android-dex-tauri
REPO = os.path.dirname(APP)

SRC_X = os.path.join(HERE, "brand-src", "x.png")
SRC_WORD = os.path.join(HERE, "brand-src", "wordmark.png")

TILE = (255, 255, 255, 255)     # the white tile both marks were drawn on

# How much of the square the art spans. The X is a mark and may be large; the
# wordmark needs air around it or it runs into the tile's edge at small sizes.
X_SCALE = 0.62
WORD_SCALE = 0.86
# What the adaptive icon's 108 dp layer guarantees a launcher shows (the mask
# keeps the middle 66 dp). Art must live inside that or a round mask cuts it.
SAFE_ZONE = 66.0 / 108.0


def load(path):
    im = Image.open(path).convert("RGBA")
    box = im.getbbox()
    return im.crop(box) if box else im


def tile(art, size, scale):
    """`art`, centred on a white square of `size`, spanning `scale` of it."""
    out = Image.new("RGBA", (size, size), TILE)
    inner = max(1, int(size * scale))
    w, h = art.size
    k = min(inner / w, inner / h)
    r = art.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    out.alpha_composite(r, ((size - r.width) // 2, (size - r.height) // 2))
    return out


def flat(art, size, scale, colour=(255, 255, 255, 255)):
    """`art`'s silhouette in one colour on transparency — the themed-icon layer."""
    inner = max(1, int(size * scale))
    w, h = art.size
    k = min(inner / w, inner / h)
    r = art.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    mask = r.split()[-1]                       # alpha only: shape, not colour
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    solid = Image.new("RGBA", r.size, colour)
    out.paste(solid, ((size - r.width) // 2, (size - r.height) // 2), mask)
    return out


def main():
    if not os.path.exists(SRC_X) or not os.path.exists(SRC_WORD):
        sys.exit(f"missing brand sources: {SRC_X} / {SRC_WORD}")
    x = load(SRC_X)
    word = load(SRC_WORD)

    def small(size):
        return tile(x, size, X_SCALE)

    def large(size):
        return tile(word, size, WORD_SCALE)

    def art(size):
        """The X below 64 px, the wordmark from 64 up — see the module docstring."""
        return small(size) if size < 64 else large(size)

    icons = os.path.join(APP, "src-tauri", "icons")
    os.makedirs(icons, exist_ok=True)

    # The exe, its taskbar button and Start-menu entry. 128@2x is 256; every
    # Windows shell size from 16 to 256 is covered by icon.ico below.
    for name, size in [
        ("32x32.png", 32),
        ("64x64.png", 64),
        ("128x128.png", 128),
        ("128x128@2x.png", 256),
        ("icon.png", 512),
        ("StoreLogo.png", 50),
        ("Square30x30Logo.png", 30),
        ("Square44x44Logo.png", 44),
        ("Square71x71Logo.png", 71),
        ("Square89x89Logo.png", 89),
        ("Square107x107Logo.png", 107),
        ("Square142x142Logo.png", 142),
        ("Square150x150Logo.png", 150),
        ("Square284x284Logo.png", 284),
        ("Square310x310Logo.png", 310),
    ]:
        art(size).save(os.path.join(icons, name))
        print("wrote", name, size)

    # icon.ico: seven images, each drawn at its OWN size. This is the one that
    # matters — Explorer picks from it by DPI, and a single 256 scaled down is
    # exactly the mud this replaces.
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    base = art(256)
    base.save(os.path.join(icons, "icon.ico"),
              sizes=[(s, s) for s in ico_sizes])
    print("wrote icon.ico", ico_sizes)
    # …but PIL resamples one image for all seven, which puts the WORDMARK in the
    # 16 px slot. Redraw it properly, one image per size.
    _ico_exact(os.path.join(icons, "icon.ico"), ico_sizes,
               {s: art(s) for s in ico_sizes})

    # macOS (not built here, but shipped in the tree — keep it the same art).
    try:
        icns = os.path.join(icons, "icon.icns")
        if os.path.exists(icns):
            art(1024).save(icns, format="ICNS")
            print("wrote icon.icns")
    except Exception as e:             # no ICNS writer: not worth failing a build
        print("icon.icns left alone:", e)

    # The mirror window's icon: scrcpy loads this name from SCRCPY_ICON_DIR.
    res_icon = os.path.join(APP, "src-tauri", "resources", "icon")
    os.makedirs(res_icon, exist_ok=True)
    large(256).save(os.path.join(res_icon, "scrcpy.png"))
    print("wrote resources/icon/scrcpy.png")

    # The app's own UI.
    pub = os.path.join(APP, "public")
    for name, size in [("logo.png", 256), ("logo-128.png", 128),
                       ("logo-64.png", 64), ("logo-32.png", 32), ("logo-16.png", 16)]:
        art(size).save(os.path.join(pub, name))
        print("wrote public/" + name)

    # ── the installer's own artwork ───────────────────────────────────────
    # The setup window uses four pictures: the exe's icon, the small icon in its
    # title bar, a 150x57 strip beside the step text and a 164x314 panel down
    # the left. NSIS wants 24-bit BMPs for the last two.
    win = os.path.join(APP, "src-tauri", "windows")
    os.makedirs(win, exist_ok=True)
    _ico_exact(os.path.join(win, "installer.ico"),
               [16, 24, 32, 48, 64, 128, 256],
               {s: tile(x, s, X_SCALE) for s in (16, 24, 32, 48, 64, 128, 256)})
    _ico_exact(os.path.join(win, "installer-header.ico"), [64],
               {64: tile(x, 64, X_SCALE)})
    _panel(word, 150, 57, 0.90, (255, 255, 255)).save(
        os.path.join(win, "header.bmp"), format="BMP")
    _panel(tile(x, 164, X_SCALE), 164, 314, 1.0, (242, 244, 248)).save(
        os.path.join(win, "sidebar.bmp"), format="BMP")
    print("wrote installer.ico / installer-header.ico / header.bmp / sidebar.bmp")

    # ── the launcher's adaptive icon ──────────────────────────────────────
    # 432 px = 108 dp at xxxhdpi. The mask keeps the middle 66 dp, so the art
    # spans SAFE_ZONE of the canvas and no launcher can crop it.
    out = os.path.join(REPO, "openandroiddex-launcher", "app", "src", "main",
                       "res", "drawable-nodpi")
    os.makedirs(out, exist_ok=True)
    # The mark is drawn hard-edged, so a 256-colour palette is lossless here and
    # costs a third of the bytes of a full RGBA layer — the APK is downloaded
    # once per phone, so this is worth the four lines.
    _png(tile(x, 432, SAFE_ZONE * 0.98), os.path.join(out, "ic_launcher_foreground.png"))
    _png(flat(x, 432, SAFE_ZONE * 0.98), os.path.join(out, "ic_launcher_monochrome.png"))
    print("wrote launcher ic_launcher_foreground.png / _monochrome.png (432 px)")


def _png(img, path):
    """Save an RGBA layer as a palettised PNG, keeping its transparency."""
    a = img.convert("RGBA")
    q = a.quantize(colors=256, method=Image.FASTOCTREE)
    q.save(path, "PNG", optimize=True)


def _panel(art, w, h, scale, bg):
    """`art` centred on a w x h panel — NSIS's header strip and side panel."""
    out = Image.new("RGB", (w, h), bg)
    inner = max(1, min(int(w * scale), int(h * scale)))
    aw, ah = art.size
    k = min(inner / aw, inner / ah)
    r = art.resize((max(1, int(aw * k)), max(1, int(ah * k))), Image.LANCZOS)
    out.paste(r, ((w - r.width) // 2, (h - r.height) // 2), r)
    return out


def _ico_exact(path, sizes, images):
    """An .ico whose every entry is its own drawing (PIL only downsamples one)."""
    import struct
    entries, blobs = [], []
    offset = 6 + 16 * len(sizes)
    for s in sizes:
        import io
        buf = io.BytesIO()
        images[s].save(buf, format="PNG")
        data = buf.getvalue()
        entries.append(struct.pack("<BBBBHHII",
                                   s if s < 256 else 0, s if s < 256 else 0,
                                   0, 0, 1, 32, len(data), offset))
        offset += len(data)
        blobs.append(data)
    with open(path, "wb") as f:
        f.write(struct.pack("<HHH", 0, 1, len(sizes)))
        for e in entries:
            f.write(e)
        for b in blobs:
            f.write(b)


if __name__ == "__main__":
    main()
