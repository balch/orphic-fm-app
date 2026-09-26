#!/usr/bin/env python3
"""Assert every staged storefront slot matches store rules. Run from the repo root."""
import argparse, pathlib, sys
from PIL import Image, ImageStat

PLAY = pathlib.Path("apps/djapp/androidApp/src/main/play/listings/en-US/graphics")
ASC = pathlib.Path("apps/djapp/store-listings/app-store")
# Raw captures the framed slots are rendered from (Android in capture/, iPad beside its set).
SOURCES = [
    pathlib.Path("apps/djapp/store-listings/capture/screenshots/new"),
    pathlib.Path("apps/djapp/store-listings/app-store/raw"),
]


def exact(W, H):
    return lambda w, h: None if (w, h) == (W, H) else f"{w}x{h} != {W}x{H}"


def play_tablet(w, h):
    if not (320 <= min(w, h) and max(w, h) <= 3840):
        return f"side out of 320-3840: {w}x{h}"
    if not (9 / 16 <= w / h <= 16 / 9):
        return f"aspect {w / h:.3f} outside 9:16..16:9"


def phone(w, h):
    return None if (w, h) in ((1080, 1920), (1920, 1080)) else f"{w}x{h} not 1080x1920/1920x1080"


# slot: (dir, count, check(w, h) -> error or None)
SLOTS = {
    "phone": (PLAY / "phone-screenshots", 8, phone),
    "seven": (PLAY / "seven-inch-screenshots", 4, play_tablet),
    "ten": (PLAY / "ten-inch-screenshots", 4, play_tablet),
    "tv": (PLAY / "tv-screenshots", 4, exact(3840, 2160)),
    "feature": (PLAY / "feature-graphic", 1, exact(1024, 500)),
    "iphone": (ASC / "iphone-69", 5, exact(1320, 2868)),
    "ipad": (ASC / "ipad-13", 4, exact(2752, 2064)),
}
# A black capture is dark AND flat (mean ~5, std ~1). Dark vizzes like Galaxy run a mean of 15-17
# but keep a std near 28, so the floor needs both.
LUMA_FLOOR = 18
DETAIL_FLOOR = 8


def check(name, d, count, dims):
    errs = []
    files = sorted(p for p in d.glob("*") if p.suffix.lower() in (".png", ".jpg"))
    if len(files) != count:
        errs.append(f"{name}: {len(files)} files, want {count}: {[p.name for p in files]}")
    for p in files:
        im = Image.open(p)
        if im.mode not in ("RGB", "L"):
            errs.append(f"{p}: mode {im.mode} (alpha?)")
        if e := dims(*im.size):
            errs.append(f"{p}: {e}")
        luma = ImageStat.Stat(im.convert("L"))
        if luma.mean[0] < LUMA_FLOOR and luma.stddev[0] < DETAIL_FLOOR:
            errs.append(f"{p}: near-black")
    stray = [p.name for p in d.glob("*") if p.is_file() and p.suffix.lower() not in (".png", ".jpg")]
    if stray:
        errs.append(f"{name}: stray files {stray}")
    return errs


def check_sources():
    # Framed slots add a bright caption, so a black shot inside a frame still has detail. Check the raws.
    errs = []
    for p in sorted(f for d in SOURCES for f in d.glob("*.png")):
        luma = ImageStat.Stat(Image.open(p).convert("L"))
        if luma.mean[0] < LUMA_FLOOR and luma.stddev[0] < DETAIL_FLOOR:
            errs.append(f"{p}: near-black source")
    return errs


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=",".join(SLOTS))
    errs = check_sources() + [e for s in ap.parse_args().only.split(",") for e in check(s, *SLOTS[s])]
    print("\n".join(errs) or "all slots OK")
    sys.exit(1 if errs else 0)
