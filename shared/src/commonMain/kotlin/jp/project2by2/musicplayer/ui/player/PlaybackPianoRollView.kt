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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
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
// Match the original physical-pixel dimensions at the 3x Android reference density.
// Keep these in dp so low-density desktops and HiDPI displays use the same proportions.
private val MarkerWidth = (5f / 3f).dp
private val MeasureLineWidth = 0.5.dp
private val MeasureLabelGap = (10f / 3f).dp
private val ActiveNoteGlowExpansion = (4f / 3f).dp
private val MinimumNoteWidth = (2f / 3f).dp


// Display delay in milliseconds: negative values show the piano roll ahead of audio.
private const val PIANO_ROLL_DELAY = -120

// The measure being played flashes white on entering and fades back toward the next measure.
private const val MEASURE_AREA_FLASH_ALPHA = 0.10f
// Extra scale of the current measure number on entering (0.3 = 130% at its start).
private const val MEASURE_LABEL_FLASH_SCALE = 0.3f
private val MeasureLineColor = Color.Gray.copy(alpha = 0.45f)
private val MeasureLineFlashColor = Color.White.copy(alpha = 0.95f)

/** The measure containing [tick]: its index and how much of the entry flash remains (1 at its start, 0 at the next). */
internal data class MeasureProgress(val index: Int, val startTick: Int, val endTick: Int, val flash: Float)

internal fun currentMeasureProgress(tick: Int, measureTicks: List<Int>, lastTick: Int): MeasureProgress? {
    val found = measureTicks.binarySearch(tick)
    val index = if (found >= 0) found else -found - 2
    if (index < 0) return null
    val start = measureTicks[index]
    val end = if (index + 1 < measureTicks.size) measureTicks[index + 1] else lastTick
    val flash = if (end > start) 1f - ((tick - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) else 0f
    return MeasureProgress(index, start, end, flash)
}


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
            val markerWidth = MarkerWidth.toPx()
            val measureLineWidth = MeasureLineWidth.toPx()
            val measureLabelGap = MeasureLabelGap.toPx()
            val glowExpansion = ActiveNoteGlowExpansion.toPx()
            val minimumNoteWidth = MinimumNoteWidth.toPx()
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
            val currentMeasure = currentMeasureProgress(currentTick, measureTickPositions, endDisplayTick)

            currentMeasure?.let { measure ->
                val areaStart = maxOf(measure.startTick, visibleStart)
                val areaEnd = minOf(measure.endTick, drawableEndTick)
                if (areaEnd > areaStart) {
                    val areaX = ((areaStart - visibleStart).toFloat() / viewport.toFloat()) * size.width
                    val areaW = ((areaEnd - areaStart).toFloat() / viewport.toFloat()) * size.width
                    drawRect(
                        color = Color.White.copy(alpha = MEASURE_AREA_FLASH_ALPHA * measure.flash),
                        topLeft = Offset(areaX, 0f),
                        size = Size(areaW, size.height)
                    )
                }
            }

            measureTickPositions.forEachIndexed { index, measureDisplayTick ->
                if (measureDisplayTick !in visibleStart..drawableEndTick) return@forEachIndexed
                val x = ((measureDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                val measureColor = if (currentMeasure?.index == index) {
                    lerp(MeasureLineColor, MeasureLineFlashColor, currentMeasure.flash)
                } else {
                    MeasureLineColor
                }
                drawLine(
                    color = measureColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = measureLineWidth
                )
                // Logical text size: legacy 32 physical pixels was about 11sp on a 3x phone,
                // but became 32sp on a 1x desktop display.
                // Measure independently of the remaining viewport width so edge labels never wrap.
                val label = textMeasurer.measure(
                    text = "${index + 1}",
                    style = TextStyle(color = measureColor, fontSize = 11.sp),
                    softWrap = false,
                    maxLines = 1
                )
                val labelOrigin = Offset(x + measureLabelGap, 0f)
                val labelScale = if (currentMeasure?.index == index) 1f + MEASURE_LABEL_FLASH_SCALE * currentMeasure.flash else 1f
                clipRect {
                    // Grow from the label's top-left so it stays next to its measure line.
                    scale(labelScale, labelScale, pivot = labelOrigin) {
                        drawText(label, topLeft = labelOrigin)
                    }
                }
            }

            // Sounding notes, glossed once every note is drawn.
            val glossNotes = ArrayList<NoteGlossNote>()
            // Drawn last-to-first so that earlier notes end up on top of overlapping later ones.
            for (index in notes.indices.reversed()) {
                val note = notes[index]
                val startDisplayTick = note.startTick.coerceIn(0, durationTicks)
                val endNoteTick = note.endTick.coerceIn(0, durationTicks)
                if (startDisplayTick >= drawableEndTick || endNoteTick <= visibleStart) continue
                val clippedStartTick = maxOf(startDisplayTick, visibleStart)
                val clippedEndTick = minOf(endNoteTick, drawableEndTick)
                if (clippedEndTick <= clippedStartTick) continue
                val x = ((clippedStartTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                val w = ((clippedEndTick - clippedStartTick).toFloat() / viewport.toFloat()) * size.width
                val y = ((127 - note.noteNumber).toFloat() / 127f) * size.height
                val h = size.height / 128f * 2f
                val channelColor = MidiChannelNeonPalette[note.channel.mod(MidiChannelNeonPalette.size)]
                val reveal = if (index >= chunkStartIndex) chunkReveal.value else 1f
                val animatedWidth = (w.coerceAtLeast(minimumNoteWidth) * reveal).coerceAtLeast(minimumNoteWidth)
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
                        topLeft = Offset(x - glowExpansion, y - glowExpansion),
                        size = Size(
                            animatedWidth + glowExpansion * 2f,
                            h + glowExpansion * 2f
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
                    glossNotes.add(NoteGlossNote(x, y, animatedWidth, h, channelColor, highlightStrength * glossStrengthOf(note.velocity, note.volume, note.expression)))
                }
            }
            drawNoteGloss(glossNotes)

            fun drawMarker(ms: Long, color: Color) {
                val markerDisplayTick = msToTick(ms, tickTimeAnchors, durationTicks).coerceIn(0, durationTicks)
                if (markerDisplayTick !in visibleStart..visibleEnd) return
                if (markerDisplayTick > endDisplayTick && color != Color.Red) return
                val x = ((markerDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = markerWidth)
            }

            drawMarker(endPointMs, Color.Red)
            drawMarker(loopPointMs, Color.Green)
            drawMarker(displayPositionMs, Color.White)
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
                        strokeWidth = markerWidth
                    )
                }
            }
        }
    }
}
