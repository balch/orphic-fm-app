package org.balch.orpheus.features.visualizations.viz.sprite

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteBatchTest {
    private val soft = SpriteCell(0f to 1f, 1f to 0f)

    private fun blank(size: Int = 64) = ImageBitmap(size, size)

    @Test fun `a disc takes its tint at the centre and fades out by the rim`() {
        val image = blank()
        SpriteBatch(listOf(soft)).draw(Canvas(image), BlendMode.SrcOver) {
            disc(0, 32f, 32f, 20f, Color.Red)
        }
        val pixels = image.toPixelMap()
        val centre = pixels[32, 32]
        assertTrue(centre.red > 0.85f && centre.green < 0.1f, "centre was $centre")
        assertEquals(0f, pixels[32, 54].alpha, "just past the rim")
        assertTrue(pixels[32, 42].alpha in 0.3f..0.7f, "halfway out was ${pixels[32, 42]}")
    }

    @Test fun `a negative radius draws nothing`() {
        val image = blank()
        SpriteBatch(listOf(SOLID_DISC)).draw(Canvas(image), BlendMode.SrcOver) {
            disc(0, 32f, 32f, -20f, Color.White)
        }
        assertEquals(0f, image.toPixelMap()[32, 32].alpha)
    }

    @Test fun `cells stay apart in the atlas`() {
        val image = blank()
        val hollow = SpriteCell(0f to 0f, 0.5f to 0f, 0.75f to 1f, 1f to 0f)
        SpriteBatch(listOf(soft, hollow)).draw(Canvas(image), BlendMode.SrcOver) {
            disc(1, 32f, 32f, 20f, Color.White)
        }
        val pixels = image.toPixelMap()
        assertEquals(0f, pixels[32, 32].alpha, "the second cell is hollow in the middle")
        assertTrue(pixels[32, 47].alpha > 0.7f, "its ring was ${pixels[32, 47]}")
    }

    @Test fun `plus blending adds overlapping sprites`() {
        val image = blank()
        SpriteBatch(listOf(SOLID_DISC)).draw(Canvas(image), BlendMode.Plus) {
            disc(0, 32f, 32f, 20f, Color.Red.copy(alpha = 0.5f))
            disc(0, 32f, 32f, 20f, Color.Red.copy(alpha = 0.5f))
        }
        val centre = image.toPixelMap()[32, 32]
        assertTrue(centre.alpha > 0.95f, "two half-alpha sprites should sum, got $centre")
    }

    @Test fun `a quad lies along its direction`() {
        val image = blank()
        SpriteBatch(listOf(SOLID_DISC)).draw(Canvas(image), BlendMode.SrcOver) {
            quad(0, 32f, 32f, 24f, 4f, 0f, 1f, Color.White)
        }
        val pixels = image.toPixelMap()
        assertTrue(pixels[32, 14].alpha > 0.1f, "along its width, turned to point down")
        assertEquals(0f, pixels[14, 32].alpha, "across it")
    }

    @Test fun `more sprites than one mesh holds all draw in order`() {
        val image = blank()
        SpriteBatch(listOf(SOLID_DISC)).draw(Canvas(image), BlendMode.SrcOver) {
            repeat(2500) { disc(0, 32f, 32f, 20f, Color.Red) }
            disc(0, 32f, 32f, 20f, Color.Blue)
        }
        val centre = image.toPixelMap()[32, 32]
        assertTrue(centre.blue > 0.95f && centre.red < 0.05f, "the last sprite added belongs on top, got $centre")
    }

    @Test fun `a smaller frame leaves nothing of a larger one`() {
        val batch = SpriteBatch(listOf(SOLID_DISC))
        batch.draw(Canvas(blank()), BlendMode.SrcOver) {
            disc(0, 10f, 10f, 8f, Color.White)
            disc(0, 50f, 50f, 8f, Color.White)
        }
        val image = blank()
        batch.draw(Canvas(image), BlendMode.SrcOver) { disc(0, 10f, 10f, 8f, Color.White) }
        val pixels = image.toPixelMap()
        assertTrue(pixels[10, 10].alpha > 0.9f)
        assertEquals(0f, pixels[50, 50].alpha, "the second sprite is from the frame before")
    }
}
