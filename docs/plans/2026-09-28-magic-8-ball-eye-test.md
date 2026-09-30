# Magic 8-Ball Playlist: eye test (JVM desktop)

Launch: `./gradlew :apps:djapp:desktopApp:run` (Orpheus synth: `./gradlew :apps:orpheus:desktopApp:run`)

Results are kept on the published eye-test page; this list mirrors it.

## Verify
- [ ] Phone-size window: hold the dome about ½s. It never pauses; the ball rises, shows a phrase, and the playlist slides up in the stage.
- [ ] Tap the ball mid-reveal: the playlist opens at once.
- [ ] While the playlist is open the ring shows the 8-ball with nothing above it (no phrase, no name) on the phone bar and the dock; the sheet's header carries the phrase.
- [ ] Landscape (rail), playlist open: the label under the ring shows the vibe's name, not the phrase.
- [ ] Tap the 8-ball in the ring: the playlist closes and the ring is play/pause again.
- [ ] Phone-size window: the playlist stops above the bar; the ring sits over its bottom edge; tapping the dimmed stage or Esc closes it.
- [ ] Phone-size window: Shuffle and the album chips show in the playlist's header.
- [ ] A phrase too long for the header scrolls instead of cutting off.
- [ ] A quick tap still plays/pauses; a swipe still skips; a swipe held past ½s never opens the playlist.
- [ ] Landscape (rail) and a wide window (dock): the ball rises to the stage's centre; the sheet comes in from the right.
- [ ] Drag a vibe to the top of Up next: the dome's "Up next" peek and ▶ both land on it.
- [ ] Drag across the wrap (the last row to the top, and the top to the last): Up next shows the drop exactly.
- [ ] − on a row moves it to Set aside; + puts it back in its old slot.
- [ ] − is disabled on the last Up next row.
- [ ] The chips come in `AlbumCatalog.kt`'s album order (today RIF, 0-2-1, Anomalies, Stealth), and each row's badge names the album that lists it.
- [ ] Stealth holds every vibe no other album lists. Move one Stealth vibe onto RIF in `AlbumCatalog.kt` (as `VibeNames.X`) and relaunch: it leaves Stealth and plays in RIF's order.
- [ ] Playing, tap an album chip: its rows lift, glide to the top of Up next in the album's track order and settle; the chip glows meanwhile. The song plays on and the album follows it.
- [ ] Playing a vibe from that album: it stays NOW and the rest of the album follows it, wrapping round.
- [ ] Set one of an album's vibes aside, then tap its chip: that row rises out of Set aside with the rest.
- [ ] Paused, tap an album chip: its first track becomes NOW, still paused, the rest follow, and the old NOW keeps its place further down.
- [ ] Paused, tap the chip of the album whose first track is already NOW: nothing restarts.
- [ ] Edit `AlbumCatalog.kt` (move an album up, swap two of its tracks) and relaunch: the chips and a queued album follow the new order.
- [ ] macOS Now Playing shows the playing vibe's album as its subtitle, with that album's art.
- [ ] Scrolled down, tap a chip: the list scrolls to the top to show the album land.
- [ ] Let a song end (or use the Ends pill): the next vibe is the first Up next row.
- [ ] Shuffle: the list scrambles, the mini ball wobbles, and the phrase changes.
- [ ] Paused, tap Shuffle: the new first Up next becomes NOW, still paused, and the old NOW moves into the list.
- [ ] Reset order: catalog order, nothing set aside.
- [ ] Quit and relaunch: the order and the set-aside vibes come back.
- [ ] A skip landing mid-drag does not move rows under the pointer.
- [ ] Every layout: the playlist slides out when it closes (scrim, Esc, the ring ball) instead of vanishing; Esc closes the landscape side sheet too.
- [ ] Portrait, playlist open: tap Mix, Horn or DJ in the bar; the playlist closes and the tab shows.
- [ ] The AI side sheet (rail/dock) closes on Esc; Esc while typing in the AI prompt does what you'd expect.
- [ ] Tap the dimmed stage while the playlist is fading out: nothing underneath reacts.
- [ ] Close with Esc, then long-press again during the slide-out: the reveal and playlist still behave.

## Verify: the theme colour fix (both apps)
The theme's greys and outlines could load as transparent; they now always draw, which changes every Material default that uses them. Nothing should look new-but-wrong.
- [ ] Orpheus synth: the segmented buttons on the Duo LFO, Grains and Resonator panels (a default outline border now draws).
- [ ] Orpheus: the AI chat input's outline and labels.
- [ ] Orpheus: preset and confirm dialogs, and dropdown menus.
- [ ] Orpheus: any default-coloured text.
- [ ] DJ app beyond the playlist: the Vibe Info sheet.
- [ ] DJ app: the AI sheet (model selector, prompt field).
- [ ] DJ app: the header dropdowns and disabled controls.

## Judge
- [ ] The reveal's timing (rise about ½s, phrase about 1½s): too slow, too fast?
- [ ] The ball's look at 160dp and in the sheet header.
- [ ] The sheet's density and the struck-through rows.
- [ ] The album lift: size, glow and timing (about 200ms up, ½s travel, 200ms down).
- [ ] Your new phrases on the die, and the sheet title "Magic ∞".
- [ ] The ∞ on the ball (where a real one has its 8) as it rises, and the oversized ∞ in the sheet's title.
- [ ] A1's hop when paused (it lands at the top, then moves into NOW).
- [ ] Every Up next move now bounces (song advance, −/+, Shuffle, drops).
- [ ] The narrow-rail chip row (600×420) scrolls and clips "Anomalies".
- [ ] An AI vibe's lock-screen/Now Playing subtitle is now blank (was "Stealth").

## Optional: device pass
- [ ] Android system Back and the iOS edge swipe close the panel.
- [ ] iOS: a drag started on a row handle near the left edge isn't taken as Back.
- [ ] Android Auto: the albums and their track order.
