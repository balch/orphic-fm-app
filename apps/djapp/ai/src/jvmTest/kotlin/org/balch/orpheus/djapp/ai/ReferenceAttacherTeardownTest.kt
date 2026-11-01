package org.balch.orpheus.djapp.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.balch.orpheus.djapp.ai.songref.SongPaste
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceAttacherTeardownTest {
    private val store = SongPasteStore()
    private val state = MutableStateFlow(DjAiUiState())
    private val pool: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob())
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var puts = 0

    // The first put stores its paste, then parks until released; later puts run straight through.
    private fun parkingPut(text: String): SongPaste {
        val paste = store.put(text)
        if (++puts == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        return paste
    }

    private val attacher = ReferenceAttacher(store, scope, pool.asCoroutineDispatcher(), state, ::parkingPut)

    @AfterTest fun shutDown() { pool.shutdownNow() }

    @Test fun scopeCancelledMidParseLeavesNoOrphanPaste() = runBlocking {
        attacher.attach("Kettle Morning\nAm D\nWake up slow")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        scope.cancel()
        release.countDown()
        scope.coroutineContext.job.join()
        assertEquals(emptyList(), store.ids()); assertNull(state.value.reference); assertNull(state.value.parsing)
    }

    @Test fun attachWhileTheFirstIsParkedKeepsOnlyTheSecond() = runBlocking {
        attacher.attach("Kettle Morning\nAm D\nWake up slow")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        attacher.attach("Kettle Evening\nC G\nPour another cup")
        release.countDown()
        withTimeout(5_000) { state.first { it.parsing == null } }
        val chip = state.value.reference
        assertEquals("Kettle Evening", chip?.title); assertEquals(listOf(chip!!.id), store.ids())
    }
}
