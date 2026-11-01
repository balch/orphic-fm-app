"""Cost of the DJ app's in-app AI per song per model, as a markdown report on stdout.

Reads the turns.jsonl the AI edition appends to (one line per agent turn or ideas refresh):

    python3 -I scripts/ai/cost_report.py                      # desktop: ~/.config/orpheus-dj/ai-usage/turns.jsonl
    python3 -I scripts/ai/cost_report.py --since 2026-10-25   # only rows on or after a date (local time)
    python3 -I scripts/ai/cost_report.py --song "Running Out"
    adb exec-out run-as org.balch.djapp.ai cat files/ai-usage/turns.jsonl > /tmp/turns.jsonl
    python3 -I scripts/ai/cost_report.py /tmp/turns.jsonl     # an Android phone's file

Costs are the app's estimates at its price table (pricesAsOf); rows with no price are counted, not costed.
"""
import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

DEFAULT = Path.home() / ".config/orpheus-dj/ai-usage/turns.jsonl"


def load(path):
    rows = []
    for n, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            print(f"skipping bad line {n}", file=sys.stderr)
    return rows


def k(n):
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    return f"{n / 1000:.1f}k" if n >= 1000 else str(n)


def usd(v):
    return f"${v:.4f}" if v < 0.01 else f"${v:.3f}"


def prompt_tokens(r):
    return r["inputTokens"] + r["cacheReadTokens"] + r["cacheWriteTokens"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file", nargs="?", type=Path, default=DEFAULT)
    ap.add_argument("--since", help="YYYY-MM-DD, local time")
    ap.add_argument("--song", help="one vibe's rows, ignoring case")
    a = ap.parse_args()

    if not a.file.exists():
        sys.exit(f"{a.file} not found: run an AI turn in the DJ app (AI edition) first")
    rows = load(a.file)
    if a.since:
        cut = datetime.strptime(a.since, "%Y-%m-%d").timestamp() * 1000
        rows = [r for r in rows if r["atEpochMs"] >= cut]
    if a.song:
        rows = [r for r in rows if r["song"].lower() == a.song.lower()]
    if not rows:
        sys.exit("no rows match")

    groups = defaultdict(list)
    for r in rows:
        groups[(r["song"], r["modelId"])].append(r)

    first = datetime.fromtimestamp(rows[0]["atEpochMs"] / 1000)
    last = datetime.fromtimestamp(rows[-1]["atEpochMs"] / 1000)
    total = sum(r.get("costUsd") or 0 for r in rows)
    print(f"# In-app AI cost, {first:%Y-%m-%d %H:%M} to {last:%Y-%m-%d %H:%M}\n")
    print(f"Total {usd(total)} over {len(rows)} rows. Prices as of {sorted({r['pricesAsOf'] for r in rows})}.\n")
    print("| Song | Model | Turns | $/turn | Ideas $ | Total $ | Prompt in | Cached % | Out | Unpriced |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    table = []
    for (song, model), rs in groups.items():
        agent = [r for r in rs if r.get("source", "AGENT") == "AGENT"]
        ideas = [r for r in rs if r.get("source") == "IDEAS"]
        priced = [r["costUsd"] for r in agent if r.get("costUsd") is not None]
        prompt = sum(prompt_tokens(r) for r in rs)
        cached = sum(r["cacheReadTokens"] for r in rs)
        table.append((
            sum(r.get("costUsd") or 0 for r in rs),
            f"| {song} | {model} | {len(agent)} | {usd(sum(priced) / len(priced)) if priced else '-'} | "
            f"{usd(sum(r.get('costUsd') or 0 for r in ideas))} | {usd(sum(r.get('costUsd') or 0 for r in rs))} | "
            f"{k(prompt)} | {100 * cached / prompt if prompt else 0:.0f}% | {k(sum(r['outputTokens'] for r in rs))} | "
            f"{sum(1 for r in rs if r.get('costUsd') is None)} |",
        ))
    for _, line in sorted(table, reverse=True):
        print(line)

    stopped = [r for r in rows if r.get("outcome") != "REPLIED"]
    if stopped:
        print(f"\n{len(stopped)} turns failed or were stopped; their tokens are still in the totals.")


if __name__ == "__main__":
    main()
