# Store listings (Orphic DJ)

Everything about the Play and App Store listings except the one folder Gradle Play Publisher reads.

| Path | What | Consumed by |
|---|---|---|
| `../androidApp/src/main/play/` | Play listing copy, graphics (screenshots, feature graphic, icon, TV banner), release notes, contact info | **GPP**: `publish…ReleaseListing` / `publish…Bundle`. GPP only reads `src/<flavor>/play`, so it can't move here. |
| `play-store/` | Console-only fields (`listing.md`), console setup, distribution and testing docs, staged store graphics | people |
| `app-store/` | Apple copy (`en-US/`), `iphone-69/` and `ipad-13/` screenshot sets | ASC API, by hand (see `app-store/README.md`) |
| `capture/` | screenshot tooling, caption templates (`framed/`), raw captures (`screenshots/`) | both stores |
| `.secrets/` | Play service-account key, App Store Connect `.p8` keys (gitignored) | GPP (`androidApp/build.gradle.kts`), `release-djapp` skill |

Render a framed image with the server rooted at `apps/djapp/` (templates reach the emblem via
`../../../androidApp/…`), then check every slot:

```bash
python3 -m http.server 8765 --directory apps/djapp &
apps/djapp/store-listings/capture/render_frame.sh hero-card.html 0 1080 1920 out.png
python3 apps/djapp/store-listings/capture/verify_store_assets.py
```
