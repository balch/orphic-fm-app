package org.balch.orpheus.djapp.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTextInboxTest {

    @Test
    fun offerThenTakeReturnsTextOnce() {
        val inbox = SharedTextInbox()
        inbox.offer("x\ny")
        assertEquals("x\ny", inbox.take())
        assertNull(inbox.take())
        assertNull(inbox.pending.value)
    }

    @Test
    fun blankOfferIsIgnored() {
        val inbox = SharedTextInbox()
        inbox.offer("  \n")
        assertNull(inbox.pending.value)
        assertEquals(0, inbox.openRequests.value)
    }

    @Test
    fun newerOfferReplacesUnconsumed() {
        val inbox = SharedTextInbox()
        inbox.offer("a")
        inbox.offer("b")
        assertEquals("b", inbox.take())
        assertEquals(2, inbox.openRequests.value)
    }

    @Test
    fun songPasteRule() {
        assertFalse(looksLikeSongPaste("one line"))
        assertTrue(looksLikeSongPaste("a\nb\nc\nd"))
        assertTrue(looksLikeSongPaste("x".repeat(2_000)))
        assertFalse(looksLikeSongPaste("x".repeat(99) + "\n" + "y".repeat(99)))
        assertTrue(looksLikeSongPaste("x".repeat(99) + "\n" + "y".repeat(100)))
    }

    @Test
    fun sharedUrlOnlyTakesALoneLink() {
        assertEquals("https://tabs.example/x", sharedUrlOnly("https://tabs.example/x"))
        assertEquals("https://a.b/c", sharedUrlOnly(" https://a.b/c \n"))
        assertNull(sharedUrlOnly("see https://a.b"))
        assertNull(sharedUrlOnly("G C D\nEm"))
        assertNull(sharedUrlOnly("Title\nhttps://a.b"))
    }

    @Test
    fun subjectIsNotPrefixedToALoneLink() {
        assertEquals("https://a.b/c", withShareSubject("https://a.b/c", "Page Title"))
        assertEquals("Shared Tab\nG C D\nEm", withShareSubject("G C D\nEm", " Shared Tab "))
        assertEquals("Shared Tab\nG C D", withShareSubject("Shared Tab\nG C D", "Shared Tab"))
        assertEquals("G C D", withShareSubject("G C D", null))
        assertEquals("G C D", withShareSubject("G C D", "  "))
    }

    @Test
    fun sameSessionOpensOnlyNewShares() {
        assertTrue(shouldOpenForShare(shareMarker(7L, 1), 7L, 2))
        assertFalse(shouldOpenForShare(shareMarker(7L, 2), 7L, 2))
        assertTrue(shouldOpenForShare(null, 7L, 1))
    }

    @Test
    fun markerFromAnotherSessionCountsAsZero() {
        assertTrue(shouldOpenForShare(shareMarker(1L, 3), 2L, 1))
        assertTrue(shouldOpenForShare("garbage", 2L, 1))
    }
}
