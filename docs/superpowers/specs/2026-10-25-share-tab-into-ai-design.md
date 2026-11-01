# Share a tab into the AI edition

Status: landed on main 2026-10-25 (bac8b748c..797cd68d4), not yet device-tested. This is a dated
snapshot; the code is the source of truth.

## Intent

Today the user gets a guitar tab into the AI chat with a workaround. On Android they share it from the
Ultimate Guitar app into an email, copy it there, and paste it into the chat. On the Mac they copy it
from the UG website and paste it into the chat box. The chat already turns a big paste into a
song-reference chip (`classifyDraftEdit` → `ReferenceAttacher.attach` in
`apps/djapp/ai/.../PromptPaste.kt`), and the agent reads it with `song_reference`. This work removes
the detour: the tab goes straight from where the user is reading it into that same chip.

## Behaviour (decided by the user)

- After a share, the AI sheet opens in chat mode with the tab attached as the reference chip. **Nothing
  is sent.** The user types a note ("slower, more dub") and sends it as they do now.
- If a conversation is already going, the tab joins **that conversation**. No new chat is started.
  Like a paste, it replaces any chip already attached (that is what `ReferenceAttacher.attach` does).

## Goals

- **Android, AI flavor:** Orphic DJ AI shows up in the share sheet for text. Sharing from the UG app
  (which sends the chord text as `EXTRA_TEXT`; the user has confirmed it carries the chords) lands in
  the chip.
- **iOS, `DjAppAi` target:** a share extension accepts shared text and hands it to the app.
- **Desktop JVM, `-Pedition=ai`:** pressing Cmd+V (Ctrl+V on other OSes) anywhere in the window, when
  focus is not in a text field, attaches the clipboard the same way. A paste into the chat box keeps
  working as it does now.

## Non-goals

- Fetching a page from a shared **URL**. Browsers often share only a link. That's a possible follow-up,
  not this work.
- The `og` edition. It has no AI chat, so it must not register as a share target (Android manifest, iOS
  target, desktop key handling).
- Wasm (the AI edition doesn't ship there).
- Drag-and-drop onto the desktop window (possible follow-up).
- Starting a new chat for each share.

## Anti-goals (a failure even if it technically works)

- A share that sends a message to the agent by itself.
- A desktop Cmd+V that steals a paste meant for a text field, or turns a one-word paste into a chip.
- A share made at cold start that gets lost because the AI ViewModel or the sheet wasn't ready yet.
- The `og` build gaining a share target or the INTERNET permission. The Variant API guard in
  `apps/djapp/androidApp/build.gradle.kts` must stay green.

## Constraints and facts the builder needs

- **One intake path.** Every platform puts its text into a single app-scoped inbox, and
  `DjAiViewModel` drains it into `ReferenceAttacher.attach`. Platform code never talks to the
  ViewModel directly.
  - The inbox holds a pending item until it is consumed, so a cold-start share survives until the
    ViewModel exists. `DjAiViewModel` is `@SingleIn(FeatureScope)` and is created lazily when
    `AiTabContribution.Content` first composes. That happens on every launch, because the
    contributions stay composed while closed.
  - Platform entry points live in the shared `apps/djapp` code (`MainActivity`, the iOS AppDelegate
    and SceneDelegate, desktop `main.kt`), which doesn't depend on `:apps:djapp:ai`. So the inbox type
    lives where both can see it (`apps/djapp/shared`). Only the AI edition consumes it.
- **Opening the sheet.** `activeSheet` is local Compose state in `DjAppScreen`
  (`DjAppScreen.kt:167`), and nothing outside it can open the AI sheet yet. The design needs an
  app-scoped way to request "open `AiTab`" that `DjAppScreen` collects. Chat mode is set through
  `DjAiUiState.sheetMode` / `setSheetMode`.
- **Size rule.** A share is an explicit act, so any non-blank shared text becomes a chip, whatever its
  size. The desktop Cmd+V path is ambient, so it uses the chat's own "big paste" thresholds from
  `classifyDraftEdit`: 2,000 or more chars, or multi-line with 200 or more chars or 4 or more lines.
  Smaller clipboard text is ignored at window level.
- **Android.**
  - The `ACTION_SEND` / `text/plain` intent-filter goes in `src/ai/AndroidManifest.xml` only (manifest
    merge onto `MainActivity`).
  - `MainActivity` is `singleTask` and today never reads `intent`. It needs to handle the share in both
    `onCreate` and a new `onNewIntent`, and must not re-handle the same intent on configuration
    change or recreation.
  - `EXTRA_SUBJECT`, when present, can serve as a title hint. Using it is the builder's call.
- **iOS.**
  - Add a share extension target for `DjAppAi` in `project.yml` (XcodeGen). It takes plain text and
    puts no Kotlin in the extension.
  - The extension writes the text into an app-group container, and the app picks it up when it becomes
    active (AppDelegate/SceneDelegate → Kotlin `DjAppHost` → inbox).
  - `DjAppAi` has no entitlements today, so it needs an app group shared with the new extension.
  - iOS has no supported API for a share extension to open its host app. The flow is: share → tap
    Post in the extension sheet → open Orphic DJ AI → the tab is waiting in the chip. The extension
    should say so in its confirmation ("Open Orphic DJ AI to use it").
- **Desktop.** `main.kt` already has a window-level `onPreviewKeyEvent` (`PanelKeyBridge`). Cmd+V must
  first go to a focused text field, and only otherwise read the AWT clipboard into the inbox.

## Decided by me (Claude), open to change

- The URL case: when the shared text is only a URL, it goes into the prompt draft as plain text
  instead of becoming a chip. Nothing is fetched. This keeps the share from silently doing nothing.
- iOS signing (the user agreed the builder tries this): use a new app group
  `group.org.balch.djapp.ai` for `DjAppAi` and its share extension, kept apart from the OG app's
  `group.org.balch.djapp`. `DjApp.entitlements` doesn't change. The extension gets its own bundle id
  (e.g. `org.balch.djapp.app.ai.share`). The widget's existing app ID (`org.balch.djapp.app.widget`)
  belongs to the OG widget and can't be reused for it. Set it up with Automatic signing and
  `-allowProvisioningUpdates`. The target is a local device install; TestFlight/App Store is out of
  scope. If automatic provisioning fails, stop and tell the user what to create in the portal.
- Desktop: Cmd+V only, no drag-and-drop.
- Sheet mode: a share opens `AiTab` and **leaves `sheetMode` alone**. Mixer mode already shows the
  chat beside the mixer, and setting CHAT would overwrite the user's saved layout (`SheetModeMemory`).
  The share must also survive the cold-start saved-mode load in `withSavedSheetMode`.
- In-progress input: the shared tab never touches the typed draft or a message queued behind a running
  turn. If an Enter is waiting on a paste still parsing (`whenAttached`), the share attaches only
  after that send has gone out, so the user's Enter carries the paste they meant. An unsent chip is
  replaced, as a second paste would replace it.

## Left to the builder

- The names and exact shape of the inbox and the open-sheet request (StateFlow, a one-shot channel, or
  another type), as long as a cold-start item isn't lost and each item is consumed once.
- How the desktop decides that a text field has focus.
- The extension's UI: use the system `SLComposeServiceViewController`, or a minimal custom view.
- Tests: unit tests for the inbox drain and the size rule; manual checks on each platform.

## Done when

- Android AI build: UG app → Share → Orphic DJ AI opens with the chip, warm and cold.
- iOS AI build: UG app → Share → Orphic DJ AI extension → Post → open app → chip is there.
- Desktop AI build: copy a tab on the UG website → Cmd+V on the dock → the sheet opens with the chip.
  Cmd+V into the chat box behaves as today.
- In every case nothing is sent until the user presses send. An ongoing conversation is kept.
- The `og` builds are unchanged.

## Rulings made during the build

- **Android share guard.** A share is offered on every `onCreate` and `onNewIntent`, unless the
  intent was relaunched from recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`). `EXTRA_TEXT` is removed
  once it's offered, so a rotation finds nothing to offer.
  - This replaces the plan's `savedInstanceState == null` guard, which would drop a share that
    arrives after Android killed the app.
- **Sheet open across process death.** The inbox carries a per-instance `session`, and
  `DjAppScreen` saves `"session:count"`. A marker left by a dead process counts as 0, so the sheet
  still opens after a restore, and a rotation doesn't reopen it.
- **Browser shares.** Android's `EXTRA_SUBJECT` (the page title) is never put in front of a lone
  link, so a Chrome share still lands in the draft as a link.
- **Queued shares.** A share waiting on a pending Enter checks that it wasn't cancelled before it
  attaches, so an older share can't overwrite a newer one.
- **iOS compose text.**
  - When the share sheet's box was pre-filled with the shared text, the box's contents replace that
    text: an edit doesn't double the tab, and a cleared box falls back to the original.
  - When the box was pre-filled with a page title, the title is dropped and only text typed in
    front of the link is kept.
- **iOS hint.** The "Open Orphic DJ AI to use it" hint is a sheet row, because a pre-filled box
  hides the placeholder.

## Known gaps

- Android: restoring the app after process death by any route other than recents offers the last
  share once more.
- iOS: if the hand-off file can't be deleted, that tab is offered once and then skipped. Sharing
  the identical tab again while the delete keeps failing is ignored.
- No automated test covers the queued-share cancellation window, `isPasteShortcut`,
  `readAndDeleteSharedTab`, or `DjAppScreen` opening the sheet. These are covered by manual checks.
