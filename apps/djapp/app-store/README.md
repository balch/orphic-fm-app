# App Store listing copy (Orphic DJ, iOS)

Mirrors `../androidApp/src/main/play/listings/en-US/` for the Apple side. **Nothing consumes
these files** — the iOS pipeline (`xcodebuild` → `altool`) only reaches TestFlight, and App Store
metadata is set through App Store Connect. They exist so the copy is diffable in review instead of
living solely in a web form, which is how a broken sentence shipped in 2.0.5.

| File | ASC field | Limit |
|---|---|---|
| `en-US/description.txt` | Description (version page) | 4,000 |
| `en-US/promotional-text.txt` | Promotional Text (version page) | 170 |
| `en-US/keywords.txt` | Keywords (version page), comma-separated | 100 |
| `en-US/subtitle.txt` | Subtitle — **App Information**, not the version page | 30 |

Deliberately unlike Play: no `• 1 home-screen widget` bullet (Glance is Android-only) and no
PLAYS EVERYWHERE paragraph (it names Android TV).

## What's editable when

Description, keywords, screenshots, and What's New are **version-scoped**: once a version reaches
`READY_FOR_DISTRIBUTION` they are frozen, and changing them needs a new version record, which needs
its own build. Promotional Text is the exception — editable on a live version, no review.

Push a field with the ASC API rather than the web UI (key + issuer per the `release-djapp` skill;
JWT `exp` must be ≤ 20 min or every call 401s):

```
GET   /v1/apps/{APP_ID}/appStoreVersions
GET   /v1/appStoreVersions/{vid}/appStoreVersionLocalizations
PATCH /v1/appStoreVersionLocalizations/{lid}   # description, promotionalText, keywords, whatsNew
```

`app-store/` holds copy only. Screenshots live in `../play-store/assets/appstore/`.
