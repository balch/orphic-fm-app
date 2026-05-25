package org.balch.orpheus.features.pulsar

/*
 * Manual smoke test only.
 *
 * The project does not currently have Compose UI testing infrastructure
 * (no `compose-ui-test` / `createComposeRule` dependency is wired into any
 * module's source sets, and `features/pulsar/build.gradle.kts` jvmTest only
 * pulls in `kotlin.test` + `kotlinx.coroutines.test`). Adding that dependency
 * just to cover a single 25-line composable pill is not justified at this
 * point, so an automated test for the song-transition pill is deferred.
 *
 * Verify the pill manually as part of Task 24's end-to-end smoke test:
 *
 *   1. Run `./gradlew :apps:orpheus:jvmRun`.
 *   2. Open the Pulsar panel.
 *   3. With song-ending DISABLED (default), the pill at the top of the
 *      panel reads "PLAYS".
 *   4. Tap the pill once — it should toggle to the current transition
 *      style label, e.g. "FADE", "CUT", "GAP", "CROSSFADE", "SCRATCH",
 *      or "RANDOM" (whichever style is selected in TransitionSpec).
 *   5. Tap again — it should toggle back to "PLAYS".
 *   6. Long-press the pill (~500ms) — the TransitionSettingsSheet should
 *      open, allowing the style + handoff to be changed. Changing the
 *      style and dismissing the sheet should update the pill label on the
 *      next enable.
 *
 * The pill source lives inline in
 *   features/pulsar/src/commonMain/kotlin/.../PulsarPanel.kt
 * (search for `pillLabel`). If the pill is ever extracted into its own
 * composable, replace this file with real Compose UI tests at that time
 * (which will also require adding the compose-ui-test dependency to
 * features/pulsar/build.gradle.kts jvmTest dependencies).
 */
