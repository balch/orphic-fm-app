#!/usr/bin/env python3
"""Report the top-impact and subsumed keep rules from R8 Configuration Analyzer JSON.

Usage: python report_impactful_rules.py [keepruleradius.json]
"""
import json
import sys


def report(path):
    try:
        with open(path, "r") as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    bi = data.get("build_info", {})
    live = sum(int(bi.get(k, 0)) for k in ("live_class_count", "live_field_count", "live_method_count"))
    denom = live if live > 0 else sum(
        len(data.get(tbl, []))
        for tbl in ("kept_class_info_table", "kept_field_info_table", "kept_method_info_table")
    )

    processed = []
    for r in data.get("keep_rule_blast_radius_table", []):
        br = r.get("blast_radius", {})
        c, f, m = (
            len(br.get("class_blast_radius", [])),
            len(br.get("field_blast_radius", [])),
            len(br.get("method_blast_radius", [])),
        )
        impact = c + f + m
        if impact == 0:
            continue
        impact_pct = (impact / denom * 100) if denom > 0 else 0.0
        processed.append({
            "id": r.get("id"),
            "source": r.get("source"),
            "impact": impact,
            "impact_pct": f"{impact_pct:.2f}%",
            "classes": c,
            "fields": f,
            "methods": m,
            "subsumed_by": br.get("subsumed_by", []),
        })

    processed.sort(key=lambda x: x["impact"], reverse=True)

    print(json.dumps({
        "top_5_impact_keep_rules": [r for r in processed if not r["subsumed_by"]][:5],
        "subsumed": [r for r in processed if r["subsumed_by"]],
    }, indent=2))


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "tmp/r8analysis/keepruleradius.json"
    report(path)
