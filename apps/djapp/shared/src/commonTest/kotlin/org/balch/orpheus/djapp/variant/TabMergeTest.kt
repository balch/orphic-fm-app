package org.balch.orpheus.djapp.variant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.orpheus.djapp.DjTab
import org.balch.orpheus.djapp.HornTab
import org.balch.orpheus.djapp.MixTab
import org.balch.orpheus.djapp.TimerTab
import org.balch.orpheus.djapp.DjRoute
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeContribution(
    override val route: DjRoute,
    override val replaces: DjRoute?,
) : DjTabContribution {
    @Composable override fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    ) {}
}

class TabMergeTest {
    // Annotated, like djTabs itself: the objects' least upper bound is Any on Kotlin/Native,
    // so an unannotated listOf here compiles on JVM and fails the iOS targets.
    private val base: List<DjRoute> = listOf(DjTab, MixTab, HornTab, TimerTab)

    @Test fun emptyContributionsLeavesBaseUnchanged() {
        assertEquals(base, mergeTabContributions(base, emptyList()))
    }

    @Test fun replaceSwapsRouteInPlace() {
        val ai = FakeContribution(route = org.balch.orpheus.djapp.AiTab, replaces = HornTab)
        assertEquals(
            listOf(DjTab, MixTab, org.balch.orpheus.djapp.AiTab, TimerTab),
            mergeTabContributions(base, listOf(ai)),
        )
    }

    @Test fun nullReplacesAppendsAtEnd() {
        val extra = FakeContribution(route = org.balch.orpheus.djapp.AiTab, replaces = null)
        assertEquals(base + org.balch.orpheus.djapp.AiTab, mergeTabContributions(base, listOf(extra)))
    }
}
