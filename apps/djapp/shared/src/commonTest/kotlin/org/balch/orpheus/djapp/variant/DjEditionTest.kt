package org.balch.orpheus.djapp.variant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjRoute
import org.balch.orpheus.djapp.TimerTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StubContribution(override val route: DjRoute) : DjTabContribution {
    override val replaces: DjRoute? = null

    @Composable override fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    ) {}
}

class DjEditionTest {
    @Test fun noContributionsIsTheOgEdition() {
        assertEquals(DjEdition.OG, djEditionOf(emptySet()))
    }

    @Test fun anAiTabContributionIsTheAiEdition() {
        assertEquals(DjEdition.AI, djEditionOf(setOf(StubContribution(AiTab))))
    }

    @Test fun otherContributionsDoNotMakeTheAiEdition() {
        assertEquals(DjEdition.OG, djEditionOf(setOf(StubContribution(TimerTab))))
    }

    @Test fun bothEditionsHaveACustomizableNav() {
        assertTrue(DjEdition.AI.customizableNav)
        assertTrue(DjEdition.OG.customizableNav)
    }
}
