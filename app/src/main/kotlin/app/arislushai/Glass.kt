package app.arislushai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val BgTop = Color(0xFF0B1026)
    val BgMid = Color(0xFF1A1245)
    val BgBottom = Color(0xFF0B2E3D)
    val Violet = Color(0xFF7C5CFF)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFFF6BD6)
    val Good = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Rec = Color(0xFFFB7185)
    val TextHi = Color.White.copy(alpha = 0.95f)
    val TextLo = Color.White.copy(alpha = 0.62f)
}

/** Dark gradient with three soft colour orbs; the glass cards sit on top and pick the colour up. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.BgTop, Palette.BgMid, Palette.BgBottom)))
    ) {
        Orb(Palette.Violet, 340.dp, Modifier.align(Alignment.TopStart).offset((-100).dp, (-30).dp))
        Orb(Palette.Cyan, 300.dp, Modifier.align(Alignment.CenterEnd).offset(120.dp, 40.dp))
        Orb(Palette.Pink, 280.dp, Modifier.align(Alignment.BottomStart).offset((-90).dp, 90.dp))
        content()
    }
}

@Composable
private fun Orb(color: Color, size: Dp, modifier: Modifier) {
    Box(
        modifier
            .size(size)
            // No blur modifier: it clips each orb to a visible rectangle. The radial falloff is already soft.
            .background(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent)),
                CircleShape,
            )
    )
}

private val CardShape = RoundedCornerShape(26.dp)

/** Frosted card: translucent gradient fill + bright top-left edge highlight fading to the opposite corner. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.17f), tint.copy(alpha = 0.06f))))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.22f))),
                CardShape,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Palette.TextLo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(54.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.5f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.1f))), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    val shape = RoundedCornerShape(50)
    val fill: Brush = if (selected) {
        Brush.horizontalGradient(listOf(Palette.Violet.copy(alpha = 0.75f), Palette.Cyan.copy(alpha = 0.55f)))
    } else SolidColor(Color.White.copy(alpha = 0.07f))
    Row(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.55f else 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, color = Palette.TextHi, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, color = Palette.TextHi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Palette.TextLo, fontSize = 11.sp)
    }
}

@Composable
fun StatusBadge(label: String, color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).alpha(a).background(color, CircleShape))
        Text(label, color = Palette.TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
