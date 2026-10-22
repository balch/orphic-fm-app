package org.balch.orpheus.djapp.ai

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
interface OrphicFilesModule {
    companion object {
        /** The app's private `filesDir`, beside `ai-vibes`. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideOrphicFiles(context: Context): OrphicFiles =
            OrphicFiles(context.filesDir, "files/mcp.json (adb exec-out run-as ${context.packageName} cat files/mcp.json)")
    }
}
