package org.balch.orpheus.djapp.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android lifecycle manager for the DJ app.
 *
 * Observes activity lifecycle for diagnostic logging only. Media-session
 * lifetime is owned by PulsarViewModel via MediaSessionStateManager —
 * this manager intentionally does NOT force the session active/inactive on
 * background, since doing so previously raced with PulsarViewModel's mute
 * collector (dual writers to setPulsarActive) and required force-killing the
 * app to recover. If a "deactivate when backgrounded + muted" policy is ever
 * needed again, route it through PulsarViewModel so there is one writer.
 */
@SingleIn(AppScope::class)
@Inject
class DjAppLifecycleManager(
    private val application: Application,
) {
    private val log = logging("DjAppLifecycleManager")

    init {
        registerActivityLifecycleCallbacks()
        log.info { "DjAppLifecycleManager initialized" }
    }

    private fun registerActivityLifecycleCallbacks() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (!activity.isChangingConfigurations) {
                    log.info { "App backgrounded — media session lifetime owned by PulsarViewModel" }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
