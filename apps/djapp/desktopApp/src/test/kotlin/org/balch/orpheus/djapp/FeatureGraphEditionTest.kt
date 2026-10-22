package org.balch.orpheus.djapp

import org.balch.orpheus.core.features.FeatureGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The MCP server reads its tools by casting the FeatureGraph to McpToolAccess, so the AI edition's generated
 * graph must merge it. Building the graph needs the native engine, so this checks the generated class instead.
 */
class FeatureGraphEditionTest {
    private val edition = System.getProperty("orpheus.djapp.edition", "core")

    private fun featureGraphImpl(): Class<*> {
        val nested = generateSequence(listOf<Class<*>>(DjAppGraphDesktop::class.java)) { level ->
            level.flatMap { it.declaredClasses.toList() }.ifEmpty { null }
        }.flatten()
        return assertNotNull(
            nested.firstOrNull { !it.isInterface && FeatureGraph::class.java.isAssignableFrom(it) },
            "no generated FeatureGraph implementation inside DjAppGraphDesktop",
        )
    }

    private fun mcpToolAccess(): Class<*>? =
        runCatching { Class.forName("org.balch.orpheus.djapp.ai.mcp.McpToolAccess") }.getOrNull()

    @Test
    fun theFeatureGraphMergesMcpToolAccessExactlyInTheAiEdition() {
        val impl = featureGraphImpl()
        if (edition == "ai") {
            val access = assertNotNull(mcpToolAccess(), "McpToolAccess is not on the AI edition's classpath")
            assertTrue(access.isAssignableFrom(impl), "${impl.name} does not implement McpToolAccess")
        } else {
            assertEquals(null, mcpToolAccess(), "the $edition edition must not carry the MCP server")
        }
    }
}
