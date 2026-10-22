package org.balch.orpheus.djapp.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OrphicFilesTest {

    @Test
    fun desktopPathsAreUnchanged() {
        val files = OrphicFilesModule.provideOrphicFiles()
        val root = File(System.getProperty("user.home"), ".config/orpheus-dj")
        assertEquals(root, files.root)
        assertEquals(File(root, "mix-captures"), files.mixCaptures)
        assertEquals(File(root, "stem-captures"), files.stemCaptures)
        assertEquals("~/.config/orpheus-dj/mcp.json", files.mcpJsonHint)
    }
}
