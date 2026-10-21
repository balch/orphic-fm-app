"""Turns the vibe_measure JSON files in a directory into one markdown report on stdout.

    python3 -I scripts/mix/summarize.py liborpheus_dsp/test/output/vibe_measure
"""
import json
import sys
from pathlib import Path

TRACKS = ["KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX"]
# Voices Pulsar mixes in outside the 8 tracks.
SOURCES = ["STORM", "STREET", "SPEECH"]


def channels(window):
    return window["tracks"] + window.get("sources", [])


def flags(v):
    """Mix problems worth a look, as short notes. Thresholds are judgment calls; tune them here."""
    w = v["whole"]
    out, bus, tracks = w["out"], w["bus"], w["tracks"]
    notes = []
    if bus["pre_limit_peak_dbfs"] > 6:
        notes.append(f"bus peaks {bus['pre_limit_peak_dbfs']:+.1f} dBFS before the clipper")
    if bus["over_knee_pct"] > 10:
        notes.append(f"{bus['over_knee_pct']:.0f}% of samples past the clipper knee")
    if bus["hpf_loss_db"] < -1:
        notes.append(f"35 Hz HPF removes {-bus['hpf_loss_db']:.1f} dB")
    if out["correlation"] > 0.95:
        notes.append(f"near mono (correlation {out['correlation']:.2f})")
    kick, bass = tracks[0]["rel_lu"], tracks[3]["rel_lu"]
    if kick > -60 and bass > -60 and abs(kick - bass) > 4:
        louder = "bass" if bass > kick else "kick"
        notes.append(f"{louder} {abs(kick - bass):.1f} LU over the other")
    for t in tracks:
        if t["sub_pct"] > 20:
            notes.append(f"{t['name']} {t['sub_pct']:.0f}% below 40 Hz, rumble the 35 Hz HPF only partly removes")
        if t["track"] not in (0, 3) and t["low_pct"] > 40 and t["rel_lu"] > -20:
            notes.append(f"{t['name']} {t['low_pct']:.0f}% in 40-120 Hz, crowding kick and bass")
        if t["audible_pct"] < 15 and t["rel_lu"] > -60:
            notes.append(f"{t['name']} audible only {t['audible_pct']:.0f}% of the time")
    # Peaks run near -4.5 dBFS at master 1.0; the master limiter starts at -0.9.
    if out["peak_dbfs"] > -2:
        notes.append(f"output spike {out['peak_dbfs']:.1f} dBFS at {out['peak_at_s']:.1f} s")
    lufs = [s["out"]["lufs"] for s in v["sections"] if s["seconds"] > 4 and s["out"]["lufs"] > -60]
    if lufs and max(lufs) - min(lufs) > 8:
        notes.append(f"sections span {max(lufs) - min(lufs):.1f} LU")
    return notes


def lu(x):
    return "-" if x < -60 else f"{x:.1f}"


def main(directory):
    runs = [json.loads(p.read_text()) for p in sorted(Path(directory).glob("*.json"))]
    names = [r["vibe"] + ("" if not r.get("set") and not any(r["trims_db"]) else " *") for r in runs]
    print("# Vibe mix measurements\n")
    print(f"{runs[0]['seconds'] if runs else 0} s per vibe through the DJ graph, DIST {runs[0]['dist'] if runs else 0}. "
          "`*` = run with VIBE_SET or VIBE_TRIM. Track columns are LU against the dry mix (K-weighted).\n")
    print("## Bus and output\n")
    print("| vibe | LUFS | peak dBFS | crest dB | pre-limit peak | past knee | clipper GR | HPF loss | correlation | dry sub % | dry 40-120 % |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for n, r in zip(names, runs):
        o, b, d = r["whole"]["out"], r["whole"]["bus"], r["whole"]["dry"]
        print(f"| {n} | {o['lufs']:.1f} | {o['peak_dbfs']:.1f} | {o['crest_db']:.1f} | {b['pre_limit_peak_dbfs']:+.1f} "
              f"| {b['over_knee_pct']:.1f}% | {b['limiter_gr_db']:.1f} | {b['hpf_loss_db']:.1f} | {o['correlation']:.2f} "
              f"| {d['sub_pct']:.0f} | {d['low_pct']:.0f} |")
    print("\n## Track balance (LU vs dry mix)\n")
    print("| vibe | " + " | ".join(TRACKS + SOURCES) + " |")
    print("|---|" + "---|" * len(TRACKS + SOURCES))
    for n, r in zip(names, runs):
        print(f"| {n} | " + " | ".join(lu(t["rel_lu"]) for t in channels(r["whole"])) + " |")
    print("\n## Pan (energy, -1 left to +1 right)\n")
    print("| vibe | " + " | ".join(TRACKS) + " |")
    print("|---|" + "---|" * len(TRACKS))
    for n, r in zip(names, runs):
        print(f"| {n} | " + " | ".join(f"{t['pan']:+.2f}" for t in r["whole"]["tracks"]) + " |")
    print("\n## Flags\n")
    for n, r in zip(names, runs):
        f = flags(r)
        print(f"- **{n}**: " + ("; ".join(f) if f else "none"))
    print("\n## Sections\n")
    for n, r in zip(names, runs):
        print(f"### {n}\n")
        print("| section | s | LUFS | past knee | " + " | ".join(TRACKS + SOURCES) + " |")
        print("|---|---|---|---|" + "---|" * len(TRACKS + SOURCES))
        for s in r["sections"]:
            print(f"| {s['name']} | {s['seconds']:.0f} | {lu(s['out']['lufs'])} | {s['bus']['over_knee_pct']:.0f}% | "
                  + " | ".join(lu(t["rel_lu"]) for t in channels(s)) + " |")
        print()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "liborpheus_dsp/test/output/vibe_measure")
