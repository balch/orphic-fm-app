package org.balch.orpheus.djapp.ai

import java.io.File

/**
 * Where the AI edition keeps `mcp.json` and its captures: `~/.config/orpheus-dj` on desktop, `filesDir` on Android.
 * [mcpJsonHint] tells an MCP client where to read the token.
 */
class OrphicFiles(val root: File, val mcpJsonHint: String) {
    val mixCaptures = File(root, "mix-captures")
    val stemCaptures = File(root, "stem-captures")
    val mixChanges = File(root, "mix-changes")
}
