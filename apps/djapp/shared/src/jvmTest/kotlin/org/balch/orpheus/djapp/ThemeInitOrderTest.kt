package org.balch.orpheus.djapp

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The app touches OrpheusColors long before OrpheusTheme runs; a test usually does the opposite. If
 * either class read the other while initializing, the second would see zeroed colours: the dark
 * scheme's greys went transparent in the running app and hid Shuffle and the album chips.
 */
class ThemeInitOrderTest {
    private val colors = "org.balch.orpheus.ui.theme.OrpheusColors"
    private val theme = "org.balch.orpheus.ui.theme.OrpheusThemeKt"

    /** The [scheme] field of OrpheusTheme.kt, read in a fresh class loader after [first] has initialized. */
    private fun schemeAfter(first: String, scheme: String): String {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator)
            .map { File(it).toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            Class.forName(first, true, loader)
            val field = Class.forName(theme, true, loader).getDeclaredField(scheme)
            field.isAccessible = true
            return field.get(null).toString()
        }
    }

    private fun assertSameWhicheverClassInitializesFirst(scheme: String) {
        val colorsFirst = schemeAfter(colors, scheme)
        assertFalse("Color(0.0, 0.0, 0.0, 0.0" in colorsFirst, "a $scheme colour was read before it was set: $colorsFirst")
        assertEquals(schemeAfter(theme, scheme), colorsFirst)
    }

    @Test
    fun theDarkSchemeIsTheSameWhicheverClassInitializesFirst() = assertSameWhicheverClassInitializesFirst("DarkColorScheme")

    @Test
    fun theLightSchemeIsTheSameWhicheverClassInitializesFirst() = assertSameWhicheverClassInitializesFirst("LightColorScheme")
}
