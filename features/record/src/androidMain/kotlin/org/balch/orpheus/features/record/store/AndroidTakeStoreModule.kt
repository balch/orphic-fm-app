package org.balch.orpheus.features.record.store

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import java.io.File

/** Android keeps takes in the app's private files folder. */
@ContributesTo(AppScope::class)
@BindingContainer
interface AndroidTakeStoreModule {
    companion object {
        @Provides
        fun provideTakeStore(context: Context): TakeStore =
            FileTakeStore(File(context.filesDir, "recordings"))
    }
}
