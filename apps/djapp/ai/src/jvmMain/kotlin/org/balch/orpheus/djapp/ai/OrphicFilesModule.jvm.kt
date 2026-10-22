package org.balch.orpheus.djapp.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.io.File

@ContributesTo(AppScope::class)
@BindingContainer
interface OrphicFilesModule {
    companion object {
        /** `~/.config/orpheus-dj`, beside `settings.json` and `ai-vibes`. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideOrphicFiles(): OrphicFiles =
            OrphicFiles(File(System.getProperty("user.home"), ".config/orpheus-dj"), "~/.config/orpheus-dj/mcp.json")
    }
}
