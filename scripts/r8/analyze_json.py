#!/usr/bin/env python3
"""Analyze R8 keep-rule blast radius JSON for optimization/obfuscation/shrinking scores.

Usage: python analyze_json.py [keepruleradius.json]
"""
import json
import sys


def analyze(path):
    try:
        with open(path, "r") as f:
            d = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    c_map = {c.get("id"): set(c.get("constraints", [])) for c in d.get("keep_constraints_table", [])}
    r_map = {r.get("id"): c_map.get(r.get("constraints_id"), set()) for r in d.get("keep_rule_blast_radius_table", [])}

    tot_opt = tot_obf = tot_shr = tot_items = 0

    for tbl in ("kept_class_info_table", "kept_field_info_table", "kept_method_info_table"):
        for i in d.get(tbl, []):
            tot_items += 1
            kb = i.get("kept_by", [])
            if any("DONT_OPTIMIZE" in r_map.get(r, set()) for r in kb):
                tot_opt += 1
            if any("DONT_OBFUSCATE" in r_map.get(r, set()) for r in kb):
                tot_obf += 1
            if any("DONT_SHRINK" in r_map.get(r, set()) for r in kb):
                tot_shr += 1

    bi = d.get("build_info", {})
    live = sum(int(bi.get(k, 0)) for k in ("live_class_count", "live_field_count", "live_method_count"))
    denom = live if live > 0 else tot_items

    globals_src = [g.get("source", "").lower() for g in d.get("global_keep_rule_blast_radius_table", [])]

    def score(cnt, flag):
        if any(flag in src for src in globals_src):
            return 0.0
        return max(0.0, 100.0 - ((cnt / denom * 100) if denom > 0 else 0))

    result = [
        f"Optimization Score: {score(tot_opt, '-dontoptimize'):.2f}%",
        f"Obfuscation Score:  {score(tot_obf, '-dontobfuscate'):.2f}%",
        f"Shrinking Score:    {score(tot_shr, '-dontshrink'):.2f}%",
    ]
    for line in result:
        print(line)
    with open("tmp/r8analysis/analysis_result.txt", "w") as f:
        f.write("\n".join(result))


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "tmp/r8analysis/keepruleradius.json"
    analyze(path)
