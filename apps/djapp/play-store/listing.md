# Orphic DJ — Play Store listing draft

This is the source-of-truth document for everything that goes into the Play Console
"Main store listing" form. Fill in each section, then paste from here into the
Console UI. Keeping it under version control means you can iterate, get review
comments, and recover the text if you need to re-submit.

---

## Decided already

| Field | Value |
|-------|-------|
| App name | `Orphic DJ` |
| Default language | English (US) |
| Category — primary | Music & Audio |
| Category — secondary | _(optional, see "Tags" below)_ |
| Contact email | `orphic.fm.apps@gmail.com` |
| Website | `https://orphic.fm` |
| Privacy policy | `https://orphic.fm/dj/privacy/` |

---

## Short description — 80 char hard limit

This is the line that appears under the app name in search results and on the
detail page. It's the most read piece of copy in the listing — your one-line
pitch.

**Original generative music. Four knobs. Endless mixes.**

### Constraints

- ≤ 80 characters (Play Console hard-blocks longer text).
- No ALL-CAPS marketing words ("FREE", "BEST", etc.).
- No trademark of other apps or services.
- No emoji.
- No "Spotify-like" or comparisons to specific competitors.

---

## Full description — 4000 char hard limit

Shown on the listing detail page. Most users scan; few read every word. Format
matters more than total length.

```
Orphic DJ is an interactive album.

The music is original — generative algorithms playing themselves, composing
endlessly different arrangements every time you press play. There is no
streaming, no library, no playlist. The album lives inside the app and
extends as long as you want to listen.

This app generates the music based on semi-random math constrained
by structure you control with four simple knobs. Energy, Complexity, Mood, and 
Space capture the essence of music and life. Dial-in the algorithms according
to your current head space. Use the DJ Module at all night House Parties, or the 
timer for chill music before bed.

Built for the long hours:
• Generative beats — original compositions that never repeat
• Four-knob interactive control — reflects your current mood 
• DJ Module — tweak, layer, drop on the fly
• A built-in timer — for bedtime, focus blocks, or a long drive
• Always offline — no account, no network, no telemetry

Enjoy the Vibes.
```

### Recommended structure (~600–1200 chars total — short is fine)

1. **Opening (1–2 sentences).** What the app *is* and what makes it different.
   Don't bury the lead — Play truncates after a few lines on smaller phones.
2. **Feature bullets (4–7).** Each line ≤ 80 chars, action verb at the front.
   Group features by what the user does, not by internal subsystem.
3. **For whom / when** (1 short paragraph). Sets the use-case context.
4. **Optional — what's NOT in the app.** Especially powerful when honesty is a
   feature. ("No accounts. No ads. No streaming. No internet.")
5. **Optional — a 1-line dev story or "why".** Caveat: keep it brief — anything
   that reads like a manifesto pushes users away.

### Style rules Play actually checks

- **No claims you can't substantiate.** Skip "the best", "the fastest", "AI-
  powered" (unless you can defend it on the data-safety form).
- **No comparisons to specific competitors by name.** "Like Spotify" / "better
  than Apple Music" → review fails.
- **No "FREE" in headers when the app has IAPs or ads** — we have neither so
  this is safe to mention, but Play prefers you let the price tag speak.
- **No promo codes, sale language, or "act now"** — Play's policy treats those
  as spam.

### Things I would NOT put in the description

- Internal subsystem names ("Pulsar engine", "C++ DSP graph") — meaningless to
  users; save them for marketing pages later if you write any.
- Third-party DSP module names. Trademark issue + irrelevant to the listener.
- Long technical pedigree. The Play Store listing is a sales surface, not a
  GitHub README.
- Roadmap promises with specific timelines. "More creation tools will follow"
  is fine; "Q3 2026 update" boxes you in.

---

## Tags — up to 5

Play uses these for categorization and surfacing in related-apps. The list
Play offers is fixed; you pick from a dropdown. Strong matches for Orphic DJ:

- `Music`
- `Music & Audio`
- `Audio Player`

DO NOT pick `DJ` if your app doesn't actually let users mix tracks — we don't,
so leaving it off is more honest and reduces review friction.

---

## Release notes — what's new in this version

Shown next to the version number. Keep it short — most users glance at the
first 80 chars.

**For v1.0.0 (first release):**

> _"v1.0.0 — Orphic DJ — throwing against the wall to see what sticks."_

Examples that read well for v1.0.0:

- _"First public release. Hello, beat-driven roadtrips."_
- _"v1.0.0 — Orphic DJ enters early access. Feedback welcome."_

---

## What I still need from you

- [ ] Short description (one of the example structures above, or your own — 80 chars)
- [ ] Full description (~600–1200 chars; longer is fine, just rarely better)
- [ ] Release notes for v1.0.0
- [ ] Pick which tags from the menu above
- [ ] Confirm primary category is `Music & Audio` (vs. `Tools`?)
- [ ] Decide whether to include the GitHub repo link in the description
