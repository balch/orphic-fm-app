#!/usr/bin/env python3
"""Keep the burst frame with the most detail (luma std-dev); animated viz varies frame to frame."""
import shutil, sys
from PIL import Image, ImageStat

out, *frames = sys.argv[1:]
best = max(frames, key=lambda p: ImageStat.Stat(Image.open(p).convert("L")).stddev[0])
shutil.copyfile(best, out)
print(best)
