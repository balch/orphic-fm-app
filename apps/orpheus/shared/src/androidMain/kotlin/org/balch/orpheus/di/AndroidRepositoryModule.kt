package org.balch.orpheus.di

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.mediapipe.AndroidHandTracker
import org.balch.orpheus.core.mediapipe.HandTracker
import org.balch.orpheus.core.preferences.AndroidAppPreferencesRepository
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.AndroidSynthPresetRepository
import org.balch.orpheus.core.presets.SynthPresetRepository

/**
 * Android-specific bindings. These live in a `@ContributesTo` supertype rather than on the impls'
 * own `@ContributesBinding`, because the impls are androidMain types and commonMain can't see them.
 * `OrpheusGraphAndroid` is declared in androidMain for the same reason, which is what makes this
 * module visible at merge time.
 *
 * Scoping note: `@Binds` cannot carry a scope in Metro, so `@SingleIn(AppScope::class)` sits on the
 * impl classes. That also scopes the concrete type, not just the interface, so injecting
 * `AndroidSynthPresetRepository` directly returns the same instance the interface hands out.
 */
@ContributesTo(AppScope::class)
interface AndroidRepositoryModule {
    @Binds val AndroidSynthPresetRepository.bindSynthPresetRepository: SynthPresetRepository

    @Binds val AndroidAppPreferencesRepository.bindAppPreferencesRepository: AppPreferencesRepository

    companion object {
        /**
         * Provide Context from Application for components that need it.
         * Application is passed to the factory, and extends Context.
         */
        @Provides
        fun provideContext(application: Application): Context = application

        /** Not `@Binds`: [AndroidHandTracker] is not `@Inject`, it is constructed here. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideHandTracker(context: Context): HandTracker = AndroidHandTracker(context)
    }
}
