package org.balch.orpheus.features.record.store

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import java.io.File

/** Desktop keeps takes under the user's config folder. */
@ContributesTo(AppScope::class)
@BindingContainer
interface DesktopTakeStoreModule {
    companion object {
        @Provides
        fun provideTakeStore(): TakeStore =
            FileTakeStore(File(System.getProperty("user.home"), ".config/orpheus/recordings"))
    }
}
