package org.balch.orpheus.djapp.ai

import android.content.Context
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.NoOpAiVibeArchive
import org.balch.orpheus.features.ai.aiVibeFileName
import java.io.File

/**
 * Android [AiVibeArchive]: writes each AI-applied vibe as its own JSON file under
 * `filesDir/ai-vibes/`. Retrieve later e.g. via
 * `adb exec-out run-as org.balch.djapp.ai[.debug] ls files/ai-vibes/`.
 */
@ContributesBinding(AppScope::class, replaces = [NoOpAiVibeArchive::class])
@Inject
class AndroidAiVibeArchive(private val context: Context) : AiVibeArchive {
    private val log = logging("AndroidAiVibeArchive")

    override suspend fun archive(vibeName: String, vibeJson: String) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, ARCHIVE_DIR).apply { mkdirs() }
                val file = File(dir, aiVibeFileName(vibeName, System.currentTimeMillis()))
                file.writeText(vibeJson)
                log.debug { "Archived AI vibe '$vibeName' -> ${file.absolutePath}" }
            } catch (e: Exception) {
                log.error { "Failed to archive AI vibe '$vibeName': ${e.message}" }
            }
        }
    }

    private companion object {
        const val ARCHIVE_DIR = "ai-vibes"
    }
}
