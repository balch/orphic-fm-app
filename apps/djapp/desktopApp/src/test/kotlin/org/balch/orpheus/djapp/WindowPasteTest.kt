package org.balch.orpheus.djapp

import org.balch.orpheus.djapp.share.SharedTextInbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowPasteTest {
    @Test
    fun offerClipboardIgnoresSmallText() {
        val inbox = SharedTextInbox()
        assertFalse(offerClipboard("G", inbox))
        assertNull(inbox.pending.value)
    }

    @Test
    fun offerClipboardTakesTab() {
        val inbox = SharedTextInbox()
        val tab = "e|--0--|\nB|--1--|\nG|--0--|\nD|--2--|"
        assertTrue(offerClipboard(tab, inbox))
        assertEquals(tab, inbox.pending.value)
    }

    @Test
    fun offerClipboardNull() {
        val inbox = SharedTextInbox()
        assertFalse(offerClipboard(null, inbox))
        assertNull(inbox.pending.value)
    }
}
