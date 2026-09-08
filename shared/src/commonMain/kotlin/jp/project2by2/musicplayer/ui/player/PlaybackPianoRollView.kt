@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp

private val MidiChannelNeonPalette = listOf(
    Color(0xFFFF5252),
    Color(0xFFFF6E40),
    Color(0xFFFFD740),
    Color(0xFFFFFF00),
    Color(0xFFEEFF41),
    Color(0xFFB2FF59),
    Color(0xFF69F0AE),
    Color(0xFF64FFDA),
    Color(0xFF18FFFF),
    Color(0xFFEEEEEE),
    Color(0xFF40C4FF),
    Color(0xFF448AFF),
    Color(0xFF536DFE),
    Color(0xFF7C4DFF),
    Color(0xFFB388FF),
    Color(0xFFFF4081)
)
private const val PIANO_ROLL_DEBUG_SAMPLE_LIMIT = 24
private const val ACTIVE_NOTE_FADE_OUT_MS = 400f
private const val ACTIVE_NOTE_WHITE_MIX = 0.25f
private const val ACTIVE_NOTE_OVERLAY_ALPHA = 0.75f
private const val ACTIVE_NOTE_GLOW_ALPHA = 0.15f
private const val ACTIVE_NOTE_GLOW_EXPAND_X = 4f
private const val ACTIVE_NOTE_GLOW_EXPAND_Y = 4f


// Display delay in milliseconds: negative values show the piano roll ahead of audio.
private const val PIANO_ROLL_DELAY = -100


@Composable
fun PlaybackPianoRollView(
    notes: List<PianoRollNote>,
    measureTickPositions: List<Int>,
    tickTimeAnchors: List<TickTimeAnchor>,
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    totalDurationMs: Long,
    totalTicks: Int,
    zoomLevel: Float = 10f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var previousNoteCount by remember { mutableIntStateOf(0) }
    var chunkStartIndex by remember { mutableIntStateOf(0) }
    val chunkReveal = remember { Animatable(1f) }

    LaunchedEffect(notes.size) {
        val newSize = notes.size
        if (newSize > previousNoteCount) {
            chunkStartIndex = previousNoteCount
            previousNoteCount = newSize
            chunkReveal.snapTo(0f)
            chunkReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        } else {
            previousNoteCount = newSize
            chunkStartIndex = newSize
            chunkReveal.snapTo(1f)
        }
    }

    Box(modifier = modifier.background(Color(0xFF161616))) {
        Canvas(Modifier.fillMaxSize()) {
            val displayPositionMs = (currentPositionMs - PIANO_ROLL_DELAY)
                .coerceIn(0L, totalDurationMs.coerceAtLeast(0L))
            val durationTicks = totalTicks.coerceAtLeast(1)
            val displayDurationTicks = durationTicks
            val viewport = (displayDurationTicks / zoomLevel).toInt().coerceAtLeast(1)
            val half = viewport / 2
            val currentTick = msToTick(displayPositionMs, tickTimeAnchors, durationTicks)
            val currentDisplayTick = currentTick
            val visibleStart = (currentDisplayTick - half).coerceIn(0, (displayDurationTicks - viewport).coerceAtLeast(0))
            val visibleEnd = visibleStart + viewport
            val endDisplayTick = msToTick(endPointMs, tickTimeAnchors, durationTicks).coerceIn(0, durationTicks)
            val drawableEndTick = minOf(visibleEnd, endDisplayTick)
            val endX = ((endDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width


            measureTickPositions.forEachIndexed { index, measureDisplayTick ->
                if (measureDisplayTick !in visibleStart..drawableEndTick) return@forEachIndexed
                val x = ((measureDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                drawLine(
                    color = Color.Gray.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f
                )
                // Logical text size: legacy 32 physical pixels was about 11sp on a 3x phone,
                // but became 32sp on a 1x desktop display.
                drawText(textMeasurer, "${index + 1}", topLeft = Offset(x + 10f, 0f), style = TextStyle(color = Color.Gray.copy(alpha = 0.45f), fontSize = 11.sp))
            }

            notes.forEachIndexed { index, note ->
                val startDisplayTick = note.startTick.coerceIn(0, durationTicks)
                val endNoteTick = note.endTick.coerceIn(0, durationTicks)
                if (startDisplayTick >= drawableEndTick || endNoteTick <= visibleStart) return@forEachIndexed
                val clippedStartTick = maxOf(startDisplayTick, visibleStart)
                val clippedEndTick = minOf(endNoteTick, drawableEndTick)
                if (clippedEndTick <= clippedStartTick) return@forEachIndexed
                val x = ((clippedStartTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                val w = ((clippedEndTick - clippedStartTick).toFloat() / viewport.toFloat()) * size.width
                val y = ((127 - note.noteNumber).toFloat() / 127f) * size.height
                val h = size.height / 128f * 2f
                val channelColor = MidiChannelNeonPalette[note.channel.mod(MidiChannelNeonPalette.size)]
                val reveal = if (index >= chunkStartIndex) chunkReveal.value else 1f
                val animatedWidth = (w.coerceAtLeast(2f) * reveal).coerceAtLeast(2f)
                val animatedAlpha = 0.15f + (0.60f * reveal)
                val highlightStrength = when {
                    displayPositionMs < note.startMs -> 0f
                    displayPositionMs <= note.endMs -> 1f
                    else -> {
                        val elapsedSinceOff = (displayPositionMs - note.endMs).toFloat()
                        (1f - (elapsedSinceOff / ACTIVE_NOTE_FADE_OUT_MS)).coerceIn(0f, 1f)
                    }
                }
                val noteColor = lerp(channelColor, Color.White, highlightStrength * ACTIVE_NOTE_WHITE_MIX)
                if (highlightStrength > 0f) {
                    drawRect(
                        color = lerp(channelColor, Color.White, 0.35f).copy(
                            alpha = highlightStrength * ACTIVE_NOTE_GLOW_ALPHA
                        ),
                        topLeft = Offset(x - ACTIVE_NOTE_GLOW_EXPAND_X, y - ACTIVE_NOTE_GLOW_EXPAND_Y),
                        size = Size(
                            animatedWidth + ACTIVE_NOTE_GLOW_EXPAND_X * 2f,
                            h + ACTIVE_NOTE_GLOW_EXPAND_Y * 2f
                        ),
                        blendMode = BlendMode.Plus
                    )
                }
                drawRect(
                    color = noteColor.copy(alpha = animatedAlpha),
                    topLeft = Offset(x, y),
                    size = Size(animatedWidth, h)
                )
                if (highlightStrength > 0f) {
                    drawRect(
                        color = Color.White.copy(alpha = highlightStrength * ACTIVE_NOTE_OVERLAY_ALPHA),
                        topLeft = Offset(x, y),
                        size = Size(animatedWidth, h)
                    )
                }
            }

            fun drawMarker(ms: Long, color: Color, width: Float) {
                val markerDisplayTick = msToTick(ms, tickTimeAnchors, durationTicks).coerceIn(0, durationTicks)
                if (markerDisplayTick !in visibleStart..visibleEnd) return
                if (markerDisplayTick > endDisplayTick && color != Color.Red) return
                val x = ((markerDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = width)
            }

            drawMarker(endPointMs, Color.Red, 5f)
            drawMarker(loopPointMs, Color.Green, 5f)
            drawMarker(displayPositionMs, Color.White, 5f)
            if (endX < size.width) {
                drawRect(
                    color = Color(0xFF161616),
                    topLeft = Offset(endX.coerceAtLeast(0f), 0f),
                    size = Size((size.width - endX).coerceAtLeast(0f), size.height)
                )
                if (endX in 0f..size.width) {
                    drawLine(
                        color = Color.Red,
                        start = Offset(endX, 0f),
                        end = Offset(endX, size.height),
                        strokeWidth = 5f
                    )
                }
            }
        }
    }
}
