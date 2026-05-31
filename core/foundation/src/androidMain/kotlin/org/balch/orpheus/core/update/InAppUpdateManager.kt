package org.balch.orpheus.core.update

import android.app.Activity
import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.diamondedge.logging.logging
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.google.android.play.core.ktx.requestCompleteUpdate
import com.google.android.play.core.ktx.requestUpdateFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps Google Play's AppUpdateManager. Exposes a lifecycle-collectable [Flow] of
 * [InAppUpdateUiState], deciding Immediate vs Flexible via [UpdatePolicy] and launching the
 * appropriate Play flow when an update becomes available.
 *
 * All Play calls are guarded: on non-Play builds (debug / sideloaded) the flow degrades to
 * [InAppUpdateUiState.Hidden] instead of throwing, so JVM/desktop and local Android debug builds
 * are unaffected.
 *
 * Lives in shared core/foundation so either app can adopt it; currently only Orphic DJ wires it.
 */
@SingleIn(AppScope::class)
@Inject
class InAppUpdateManager(application: Application) {

    private val manager = AppUpdateManagerFactory.create(application)
    private val log = logging("InAppUpdate")

    /**
     * Guards the consent flow so we launch it at most once per process. Without this, each
     * lifecycle re-subscription of [uiStateFlow] re-emits `Available` and would re-prompt the
     * Play consent dialog on every foreground. A fresh process (app relaunch) resets it.
     * (An accepted-but-interrupted Immediate update is resumed separately via
     * [resumeImmediateIfInProgress].)
     */
    private val updateFlowLaunched = AtomicBoolean(false)

    /**
     * The [AppUpdateType] of the most recently launched flow, so [onUpdateFlowResult]
     * can distinguish a cancelled mandatory (IMMEDIATE) flow from a deferrable Flexible
     * one. Written/read only on the main thread; [Volatile] for visibility safety.
     */
    @Volatile
    private var inFlightUpdateType: Int? = null

    /**
     * Play's update flow mapped to [InAppUpdateUiState]. Collect under the Activity lifecycle
     * (e.g. collectAsStateWithLifecycle) so the underlying install listener registers on START
     * and unregisters on STOP. Side effect: when an update is Available, the appropriate flow is
     * launched via [launcher].
     */
    fun uiStateFlow(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ): Flow<InAppUpdateUiState> =
        manager.requestUpdateFlow()
            .onEach { result ->
                if (result is AppUpdateResult.Available && updateFlowLaunched.compareAndSet(false, true)) {
                    startUpdate(result.updateInfo, launcher)
                }
            }
            .map { it.toUiState() }
            .catch { e ->
                log.warn(e) { "in-app update flow unavailable (non-Play build?)" }
                emit(InAppUpdateUiState.Hidden)
            }

    /** Install a downloaded Flexible update (restarts the app). */
    suspend fun completeUpdate() {
        runCatching { manager.requestCompleteUpdate() }
            .onFailure { log.warn(it) { "completeUpdate failed" } }
    }

    /**
     * Feed the update launcher's [resultCode] back in. If a mandatory IMMEDIATE flow was
     * cancelled or failed (the user backed out before the download started), clear the
     * one-shot launch guard so the next foreground re-prompts. Flexible results need no
     * action — the user is allowed to defer those.
     */
    fun onUpdateFlowResult(resultCode: Int) {
        val wasImmediate = inFlightUpdateType == AppUpdateType.IMMEDIATE
        inFlightUpdateType = null
        if (resultCode != Activity.RESULT_OK && wasImmediate) {
            log.info { "IMMEDIATE update dismissed (result=$resultCode) — re-prompting on next foreground" }
            updateFlowLaunched.set(false)
        }
    }

    /** Re-launch an Immediate update that was interrupted (call on each ON_RESUME). */
    suspend fun resumeImmediateIfInProgress(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        runCatching {
            val info = manager.requestAppUpdateInfo()
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                inFlightUpdateType = AppUpdateType.IMMEDIATE
                manager.startUpdateFlowForResult(
                    info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                )
            }
        }.onFailure { log.warn(it) { "resumeImmediate failed (non-Play build?)" } }
    }

    private fun startUpdate(
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        val decision = UpdatePolicy.decide(
            priority = info.updatePriority(),
            stalenessDays = info.clientVersionStalenessDays(),
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
        )
        val type = when (decision) {
            UpdateDecision.IMMEDIATE -> AppUpdateType.IMMEDIATE
            UpdateDecision.FLEXIBLE -> AppUpdateType.FLEXIBLE
            UpdateDecision.NONE -> return
        }
        inFlightUpdateType = type
        runCatching {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        }.onFailure {
            log.warn(it) { "startUpdateFlowForResult failed" }
            // Transient failure (e.g. Play not ready yet): clear the one-shot guard so the
            // next Available emission retries instead of silencing the update for the process.
            updateFlowLaunched.set(false)
            inFlightUpdateType = null
        }
    }

    private fun AppUpdateResult.toUiState(): InAppUpdateUiState = when (this) {
        AppUpdateResult.NotAvailable -> InAppUpdateUiState.Hidden
        is AppUpdateResult.Available -> InAppUpdateUiState.Hidden // hidden until download begins
        is AppUpdateResult.InProgress -> {
            val total = installState.totalBytesToDownload()
            val downloaded = installState.bytesDownloaded()
            InAppUpdateUiState.Downloading(
                progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null,
            )
        }
        is AppUpdateResult.Downloaded -> InAppUpdateUiState.Ready
    }
}
