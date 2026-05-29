package org.balch.orpheus.djapp.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DjWidgetRefreshTest {

    @Test
    fun `coalesces rapid emissions into a single refresh`() = runTest {
        val a = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        var refreshes = 0
        val job = launch {
            DjWidgetRefresh.observe(listOf(a), debounceMs = 250L) { refreshes++ }
        }
        runCurrent()

        a.emit(1); a.emit(2); a.emit(3)
        advanceTimeBy(100L); runCurrent()
        assertEquals(0, refreshes) // still within the debounce window

        advanceTimeBy(200L); runCurrent()
        assertEquals(1, refreshes) // burst coalesced to one refresh

        job.cancel()
    }

    @Test
    fun `each settled change from any source triggers a refresh`() = runTest {
        val a = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val b = MutableSharedFlow<String>(extraBufferCapacity = 8)
        var refreshes = 0
        val job = launch {
            DjWidgetRefresh.observe(listOf(a, b), debounceMs = 250L) { refreshes++ }
        }
        runCurrent()

        a.emit(1); advanceTimeBy(300L); runCurrent()
        assertEquals(1, refreshes)

        b.emit("x"); advanceTimeBy(300L); runCurrent()
        assertEquals(2, refreshes)

        job.cancel()
    }
}
