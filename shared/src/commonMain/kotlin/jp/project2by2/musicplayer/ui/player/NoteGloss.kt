package jp.project2by2.musicplayer.ui.player

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

// The gloss of a sounding note: a soft halo of four layers around the note and a thin light streak through its
// middle, longer than the note. It is stronger the higher the velocity of the note and the louder its channel.
private const val GLOSS_STRENGTH = 0.15f
private val HaloExpansionsDp = floatArrayOf(2f, 5f, 9f, 14f)
private val HaloAlphas = floatArrayOf(0.26f, 0.15f, 0.09f, 0.05f)
private val StreakReach = 14.dp
private val StreakThickness = 1.2.dp
// The streak is only a hint along the note; the halo grows less in height than in length so the two do not make a cross.
private const val STREAK_ALPHA = 0.45f
private const val HALO_HEIGHT_SHARE = 0.4f

// Channel volume at which the gloss is as strong as velocity alone makes it: the General MIDI default. A louder
// channel is not brighter; a quieter one is dimmer in proportion.
private const val REFERENCE_VOLUME = 100f

/**
 * How strong the gloss of a note is, 0..1: proportional to its [velocity], to the channel [volume] (up to the
 * reference volume) and to the channel [expression].
 */
internal fun glossStrengthOf(velocity: Int, volume: Int = 100, expression: Int = 127): Float =
    (velocity / 127f).coerceIn(0f, 1f) * (volume / REFERENCE_VOLUME).coerceIn(0f, 1f) * (expression / 127f).coerceIn(0f, 1f)

/** A sounding note: its box and colour on the canvas, and its gloss strength (velocity and channel volume, fading out after the note ends). */
internal class NoteGlossNote(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color, val strength: Float)

internal fun DrawScope.drawNoteGloss(notes: List<NoteGlossNote>) {
    if (notes.isEmpty()) return
    val reach = StreakReach.toPx()
    val thickness = StreakThickness.toPx()
    for (note in notes) {
        val base = lerp(note.color, Color.White, 0.55f)
        val strength = note.strength * GLOSS_STRENGTH
        for (i in HaloExpansionsDp.indices) {
            val e = HaloExpansionsDp[i].dp.toPx()
            drawRoundRect(
                color = base.copy(alpha = HaloAlphas[i] * strength),
                topLeft = Offset(note.x - e, note.y - e * HALO_HEIGHT_SHARE),
                size = Size(note.w + e * 2f, note.h + e * 2f * HALO_HEIGHT_SHARE),
                cornerRadius = CornerRadius(e * HALO_HEIGHT_SHARE, e * HALO_HEIGHT_SHARE),
                blendMode = BlendMode.Plus
            )
        }
        val centre = note.y + note.h / 2f
        val left = note.x - reach
        val right = note.x + note.w + reach
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = STREAK_ALPHA * strength), Color.White.copy(alpha = STREAK_ALPHA * strength), Color.Transparent),
                startX = left,
                endX = right
            ),
            topLeft = Offset(left, centre - thickness / 2f),
            size = Size(right - left, thickness),
            blendMode = BlendMode.Plus
        )
    }
}
