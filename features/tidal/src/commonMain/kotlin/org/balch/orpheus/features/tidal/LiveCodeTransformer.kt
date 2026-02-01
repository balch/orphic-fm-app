package org.balch.orpheus.features.tidal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Syntax highlighter for Tidal/Live coding.
 * 
 * Highlights:
 * - Keywords: gates, tune, delay, lfo, fast, slow, stack, etc.
 * - Numbers: integers and floats
 * - Brackets: [] for grouping, <> for alternation
 * - Operators: :, *, /, ~, $
 * - Comments: # or //
 * - Strings: "..."
 */
class LiveCodeTransformer : VisualTransformation {
    
    // Active token highlights (character ranges that should flash)
    var activeHighlights: List<IntRange> = emptyList()
    
    // Theme colors - expanded palette for richer highlighting
    private val colorKeyword = OrpheusColors.neonCyan
    private val colorNumber = OrpheusColors.tidalNumber // Orange/Gold
    private val colorComment = OrpheusColors.tidalComment // Greyish Blue
    private val colorOperator = OrpheusColors.synthPink
    private val colorBracket = OrpheusColors.tidalBracket // Purple for brackets
    private val colorString = OrpheusColors.tidalString // Yellow for strings
    private val colorSilence = OrpheusColors.tidalSilence // Cyan-ish for silence (~)
    private val colorHighlight = OrpheusColors.tidalHighlight // Bright green for active tokens
    
    // All supported keywords from TidalParser and LiveCodeViewModel
    private val keywords = setOf(
        // Pattern types
        "gates", "voices", "tune", "delay", "lfo", "note",
        // Transformations  
        "fast", "slow", "stack",
        // Tidal-style channels
        "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8",
        // Sound and future expansion
        "s", "sound", "n", "gain", "pan", "speed", "room", "size"
    )
    
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            text = highlight(text),
            offsetMapping = OffsetMapping.Identity
        )
    }
    
    private fun highlight(text: AnnotatedString): AnnotatedString {
        return buildAnnotatedString {
            val str = text.text
            append(str)
            
            // 1. First pass: highlight strings (highest priority after comments)
            Regex("\"[^\"]*\"").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorString),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 2. Keywords - bold and cyan
            Regex("\\b(${keywords.joinToString("|")})\\b").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorKeyword, fontWeight = FontWeight.Bold),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 3. Numbers - orange/gold
            Regex("\\b-?\\d+(\\.\\d+)?\\b").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorNumber),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 4. Brackets - purple for grouping [] and alternation <>
            Regex("[\\[\\]<>]").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorBracket, fontWeight = FontWeight.Bold),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 5. Operators - pink for : $ * /
            Regex("[:\\$*/,]").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorOperator),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 6. Silence markers - special highlight for ~ - .
            Regex("(?<=\\s|^)[~\\-.](?=\\s|$)").findAll(str).forEach { result ->
                if (!isInsideComment(str, result.range.first)) {
                    addStyle(
                        style = SpanStyle(color = colorSilence, fontWeight = FontWeight.Bold),
                        start = result.range.first,
                        end = result.range.last + 1
                    )
                }
            }
            
            // 7. Comments - grey, applied last to override everything
            // Only match # or // at line start or after whitespace (not inside strings)
            Regex("(^|\\n)\\s*(#|//).*").findAll(str).forEach { result ->
                // Find where the actual comment starts (skip the newline/whitespace)
                val commentStart = result.range.first + result.value.indexOfAny(charArrayOf('#', '/'))
                addStyle(
                    style = SpanStyle(color = colorComment),
                    start = commentStart,
                    end = result.range.last + 1
                )
            }
            
            // 8. Active token highlights - warm glow background for triggered tokens
            activeHighlights.forEach { range ->
                if (range.first >= 0 && range.last < str.length) {
                    addStyle(
                        style = SpanStyle(background = colorHighlight.copy(alpha = 0.4f)),
                        start = range.first,
                        end = range.last + 1
                    )
                }
            }
        }
    }
    
    private fun isInsideComment(text: String, position: Int): Boolean {
        // Find the start of the line
        val lineStart = text.lastIndexOf('\n', position - 1) + 1
        val linePrefix = text.substring(lineStart, position)
        
        // Check if inside a string by counting unescaped quotes before position
        val quotesBeforePosition = linePrefix.count { it == '"' }
        if (quotesBeforePosition % 2 == 1) {
            return false // Inside a string, not a comment
        }
        
        // Check for comment markers at line start (with optional leading whitespace)
        val trimmedLine = linePrefix.trimStart()
        return trimmedLine.startsWith("#") || trimmedLine.startsWith("//")
    }
}
