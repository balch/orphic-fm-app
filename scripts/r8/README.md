# R8 Configuration Analyzer

Helper scripts for the `r8-analyzer` skill (Path A, quantitative).

## Run

```bash
./scripts/r8/analyze.sh                                # orpheus android
./scripts/r8/analyze.sh :apps:djapp:androidApp:assembleRelease   # dj android
```

Requires Python 3 with `protobuf` (`pip3 install protobuf`).

## Output

Artifacts land in `tmp/r8analysis/`:

| File | Purpose |
| --- | --- |
| `*.pb` | Raw protobuf from R8 |
| `keepruleradius.json` | Decoded blast-radius graph (large) |
| `analysis_result.txt` | Optimization / Obfuscation / Shrinking scores |
| `impactful_rules.json` | Top-5 non-subsumed + subsumed rules |
| `history.txt` | Previous run's scores |

## Report

Once the artifacts exist, invoke the skill in Claude:

> "Run the r8-analyzer skill against tmp/r8analysis/"

Claude reads `analysis_result.txt` + `impactful_rules.json` and produces the
formatted Markdown report per the skill's REPORT_FORMAT.md.

## Path B fallback

If `analyze.sh` exits with "No .pb produced", your bundled R8 is < 9.3.7-dev.
Ask Claude:

> "Heuristic R8 review per the r8-analyzer skill"

It will compare your proguard-rules.pro against the skill's REDUNDANT-RULES.md
and KEEP-RULES-IMPACT-HIERARCHY.md without quantitative data.
