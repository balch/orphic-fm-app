package org.balch.orpheus.djapp.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.djapp.AdaptiveSheet
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjRoute
import org.balch.orpheus.djapp.HornTab
import org.balch.orpheus.djapp.ai.chat.ChatColumn
import org.balch.orpheus.djapp.ai.chat.ChatInk
import org.balch.orpheus.djapp.ai.chat.ChatInputFocus
import org.balch.orpheus.djapp.ai.chat.LocalChatInputFocus
import org.balch.orpheus.djapp.ai.chat.legibleDp
import org.balch.orpheus.djapp.ai.mixer.AiMixerFeature
import org.balch.orpheus.djapp.ai.mixer.AiMixerPanel
import org.balch.orpheus.djapp.ai.mixer.AiMixerViewModel
import org.balch.orpheus.djapp.ai.mixer.MixerSheetContent
import org.balch.orpheus.djapp.ai.mixer.MixerSheetShape
import org.balch.orpheus.djapp.ai.mixer.SHEET_MIXER_TAG
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.ui.theme.OrpheusColors

/** The chat column beside the Mixer, and the rail it folds to (grown with the legible text, so ~48dp on any screen). */
internal val MixerChatWidth = 440.dp
internal val ChatRailWidth = 48.dp

internal const val SHEET_CHAT_TAG = "ai-sheet-chat"
internal const val SHEET_RAIL_TAG = "ai-sheet-rail"

/** The portrait sheet's height: the chat alone, or the Mixer stacked above it. */
private const val ChatPeekFraction = 0.66f
private const val MixerPeekFraction = 0.85f

/** The Mixer's share of the portrait sheet in Mixer mode. */
private const val StackedMixerWeight = 0.45f

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<DjTabContribution>())
class AiTabContribution : DjTabContribution {
    override val route: DjRoute = AiTab
    override val replaces: DjRoute? = HornTab

    @Composable
    override fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    ) {
        // Closing the sheet does NOT cancel an in-flight run. The feature and agent are
        // app-scoped, so a generation keeps streaming into state while the sheet is away and
        // reopening shows live progress; the panel's explicit ✕ Cancel is the only abort. A
        // vibe that completes while the sheet is closed still applies and starts playback —
        // fulfilling the request is the point of having asked for it. The agent is greeting-
        // free (ON_FIRST_PROMPT), so resolving the feature while closed issues no LLM traffic.
        val feature = DjAiViewModel.feature()
        val mixer = AiMixerViewModel.feature()
        if (isOpen) AiSheet(feature, mixer, isLandscape, onDismiss, modifier)
    }
}

/**
 * The open AI sheet in its last mode: the chat alone (a side sheet in landscape, a bottom sheet in portrait), or the
 * Mixer with the chat beside it (landscape) or below it (portrait). The sheet counts as open, and the Mixer as visible
 * to the agent, only while composed here.
 */
@Composable
internal fun AiSheet(
    chat: DjAiFeature,
    mixer: AiMixerFeature,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mixerMode by remember(chat) {
        chat.stateFlow.map { it.sheetMode == AiSheetMode.MIXER }.distinctUntilChanged()
    }.collectAsState(chat.stateFlow.value.sheetMode == AiSheetMode.MIXER)
    DisposableEffect(chat) {
        chat.actions.setSheetOpen(true)
        onDispose { chat.actions.setSheetOpen(false) }
    }
    DisposableEffect(chat, mixerMode) {
        chat.actions.setMixerVisible(mixerMode)
        onDispose { chat.actions.setMixerVisible(false) }
    }
    var chatCollapsed by rememberSaveable { mutableStateOf(false) }
    val railWidth = legibleDp(ChatRailWidth)

    // One input focus for the sheet, so closing the Mixer's changes hands focus back to the chat's input.
    CompositionLocalProvider(LocalChatInputFocus provides remember { ChatInputFocus() }) {
        // The layout branch is taken here in composition, never under BoxWithConstraints: a bottom sheet's dialog layer
        // added or removed mid-measure crashes desktop Compose.
        when {
            isLandscape && mixerMode -> MixerSheetContent(mixer, onDismiss) { overhang ->
                val side = Modifier.fillMaxHeight().padding(bottom = overhang)
                if (chatCollapsed) {
                    ChatRail(onExpand = { chatCollapsed = false }, side.width(railWidth))
                } else {
                    ChatSurface(side.width(MixerChatWidth)) {
                        ChatColumn(chat, Modifier.fillMaxSize(), showMixerToggle = true, onCollapse = { chatCollapsed = true })
                    }
                }
            }
            isLandscape -> AdaptiveSheet(isLandscape = true, portraitPeekHeight = 0.dp, onDismiss = onDismiss) {
                ChatColumn(chat, Modifier.fillMaxSize().testTag(SHEET_CHAT_TAG), showMixerToggle = true)
            }
            else -> BoxWithConstraints(modifier.fillMaxSize()) {
                val peek = maxHeight * if (mixerMode) MixerPeekFraction else ChatPeekFraction
                AdaptiveSheet(isLandscape = false, portraitPeekHeight = peek, onDismiss = onDismiss) {
                    Column(Modifier.fillMaxSize()) {
                        if (mixerMode) {
                            AiMixerPanel(mixer, Modifier.fillMaxWidth().weight(StackedMixerWeight).testTag(SHEET_MIXER_TAG))
                        }
                        ChatColumn(
                            chat,
                            Modifier.fillMaxWidth().weight(1f - if (mixerMode) StackedMixerWeight else 0f).testTag(SHEET_CHAT_TAG),
                            showMixerToggle = true,
                        )
                    }
                }
            }
        }
    }
}

/** The chat's opaque card beside the Mixer, matching the Mixer's corners. */
@Composable
private fun ChatSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.testTag(SHEET_CHAT_TAG),
        shape = MixerSheetShape,
        color = OrpheusColors.deepPurple,
        contentColor = OrpheusColors.onSurfaceDark,
        content = content,
    )
}

/** The folded chat: a thin rail that is one button to open it again. */
@Composable
private fun ChatRail(onExpand: () -> Unit, modifier: Modifier) {
    Surface(
        modifier.testTag(SHEET_RAIL_TAG),
        shape = MixerSheetShape,
        color = OrpheusColors.deepPurple,
        contentColor = OrpheusColors.onSurfaceDark,
    ) {
        Box(
            Modifier.fillMaxSize().clickable(onClickLabel = "Show chat", role = Role.Button, onClick = onExpand),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Show chat",
                tint = ChatInk.dim,
                modifier = Modifier.padding(top = 12.dp).size(legibleDp(20.dp)),
            )
        }
    }
}
