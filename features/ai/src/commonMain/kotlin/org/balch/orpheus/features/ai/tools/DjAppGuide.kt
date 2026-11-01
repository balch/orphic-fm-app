package org.balch.orpheus.features.ai.tools

import orpheus.features.ai.generated.resources.Res

/**
 * The DJ app's how-to guide (files/dj_app_guide.md): the Mixer, Clear All, Capture and saved AI vibes.
 * Lives beside vibe_guide.md because the generated [Res] is internal to this module.
 */
suspend fun loadDjAppGuide(): String = Res.readBytes("files/dj_app_guide.md").decodeToString().trim()
