package com.lilac.anime.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.shared.Anime
import com.lilac.anime.shared.DesktopAnimeMetadata

/** 22% from the top, as the desktop's `background-position: center 22%`: a cropped poster keeps its upper part. */
val PosterFocus: Alignment = BiasAlignment(0f, -0.56f)

/** LilacPressFeedback: a press scales the control to 97%. */
@Composable
fun Modifier.lilacPress(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, LilacMotion.spec(140), label = "press")
    return this.scale(scale)
}

/** LilacAppearMotion: fades in and rises 8 points when shown. */
@Composable
fun Modifier.lilacAppear(): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, LilacMotion.spec(280), label = "appear")
    return this.graphicsLayer { alpha = progress; translationY = (1 - progress) * 8.dp.toPx() }
}

@Composable
private fun LilacButtonBase(
    onClick: () -> Unit, enabled: Boolean, background: Color, foreground: Color, border: BorderStroke?,
    horizontal: Dp, height: Dp?, vertical: Dp, fontSize: Int, weight: FontWeight, text: String, icon: ImageVector?, modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(LilacStyle.radius)
    Row(
        modifier.lilacPress(interaction).clip(shape)
            .background(if (pressed) background.copy(alpha = background.alpha * 0.8f) else background, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(if (height != null) Modifier.height(height) else Modifier)
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = foreground, modifier = Modifier.size((fontSize + 3).dp)); if (text.isNotEmpty()) Spacer(Modifier.width(6.dp)) }
        if (text.isNotEmpty()) Text(text, color = foreground, fontSize = fontSize.sp, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The desktop's filled white button (.primary: 자세히 보기, ○화 재생); dark on the light theme. */
@Composable
fun LilacPrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = LilacStyle.palette.dark
    LilacButtonBase(onClick, enabled, if (dark) Color.White else LilacStyle.onAccent, if (dark) Color(0xFF141414) else Color.White, null,
        20.dp, null, 11.dp, 15, FontWeight.Bold, text, icon, modifier)
}

/** The desktop's grey see-through button beside it (.ghost: 내 목록). */
@Composable
fun LilacGhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = LilacStyle.palette.dark
    LilacButtonBase(onClick, enabled, if (dark) Color(0xFF6E6E6E).copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.1f),
        tint ?: if (dark) Color.White else LilacStyle.text, null, 18.dp, null, 11.dp, 15, FontWeight.Bold, text, icon, modifier)
}

/** The desktop's small bordered tool button (오래된화순 · 전체 저장). */
@Composable
fun LilacOutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    LilacButtonBase(onClick, enabled, Color.Transparent, tint ?: LilacStyle.text, BorderStroke(1.dp, LilacStyle.line),
        13.dp, 34.dp, 0.dp, 13, FontWeight.SemiBold, text, icon, modifier)
}

/** A row's title with the desktop's lilac bar in front and 전체 보기 → on the right (ott.css .section-title). */
@Composable
fun LilacSectionTitle(title: String, modifier: Modifier = Modifier, seeAll: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 17.dp).background(LilacStyle.accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (seeAll != null) Text("전체 보기 →", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.accent, modifier = Modifier.clickable(onClick = seeAll).padding(4.dp))
    }
}

@Composable
fun AnimeArtwork(url: String, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    Box(modifier.background(LilacStyle.card2)) {
        if (url.isNotEmpty()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment, modifier = Modifier.matchParentSize())
    }
}

fun scoreText(score: Double): String {
    val rounded = Math.round(score * 100) / 100.0
    return "★ " + if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/** A poster as the desktop's card: the picture with its score, the title and a line of facts under it. */
@Composable
fun AnimePosterCard(anime: Anime, modifier: Modifier = Modifier, width: Dp? = null, height: Dp = 205.dp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier).lilacAppear().lilacPress(interaction)
            .combinedClickable(interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(LilacStyle.radius))) {
            AnimeArtwork(anime.poster, Modifier.matchParentSize())
            if (anime.score > 0) {
                Text(scoreText(anime.score), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        Text(com.lilac.anime.android.data.displayTitle(anime), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp).heightIn(min = 36.dp))
        val facts = listOf(anime.year, anime.format, anime.genres.firstOrNull() ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        Text(facts, fontSize = 12.sp, color = LilacStyle.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        val episodes = DesktopAnimeMetadata.episodeLabel(anime)
        if (episodes.isNotEmpty()) Text(episodes, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.accent, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The desktop's sort chips: the chosen one filled lilac with dark text, the others quiet. */
@Composable
fun LilacChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(LilacStyle.radius))
            .background(if (selected) LilacStyle.accent else LilacStyle.card2).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
            color = if (selected) LilacStyle.onAccent else LilacStyle.muted)
    }
}

/** Chips in the desktop's segmented tray (.padding(3) on card2). */
@Composable
fun LilacChipTray(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.background(LilacStyle.card2, RoundedCornerShape(8.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
fun LilacEmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = LilacStyle.accent, modifier = Modifier.size(40.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
        Text(message, fontSize = 15.sp, color = LilacStyle.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun LilacProgress(label: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(color = LilacStyle.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        if (label != null) Text(label, fontSize = 13.sp, color = LilacStyle.muted)
    }
}

/** A field box as the desktop's inputs: card2 with a line border, 38 high. */
@Composable
fun Modifier.lilacField(radius: Dp = 7.dp): Modifier =
    this.background(LilacStyle.card2, RoundedCornerShape(radius)).border(1.dp, LilacStyle.line, RoundedCornerShape(radius))

object ContentSources {
    val keys = listOf("linkkf", "ohli24", "linkani", "animenosub", "reanime", "miruro")
    fun name(key: String): String = when (key) {
        "local" -> "다운로드"; "ohli24" -> "애니24"; "linkani" -> "링크애니"; "reanime" -> "RE:Anime"
        "miruro" -> "Miruro"; "animenosub" -> "Animenosub"; else -> "Linkkf"
    }
}

/** .catalog-filter-fields label: the field's name over a select of its values; the values open in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSelect(title: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var selecting by remember { mutableStateOf(false) }
    var presented by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 12.sp, color = LilacStyle.muted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp).lilacField().clip(RoundedCornerShape(7.dp))
                .clickable { presented = options; selecting = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, fontSize = 13.sp, color = LilacStyle.text, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = LilacStyle.muted, modifier = Modifier.size(16.dp))
        }
    }
    if (selecting) {
        ModalBottomSheet(onDismissRequest = { selecting = false }, containerColor = LilacStyle.background,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, modifier = Modifier.weight(1f))
                Text("닫기", color = LilacStyle.accent, fontSize = 16.sp, modifier = Modifier.clickable { selecting = false }.padding(4.dp))
            }
            HorizontalDivider(color = LilacStyle.line)
            LazyColumn {
                itemsIndexed(presented) { _, option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(option.first); selecting = false }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.second, color = LilacStyle.text, modifier = Modifier.weight(1f))
                        if (value == option.first) Icon(Icons.Filled.Check, null, tint = LilacStyle.accent)
                    }
                }
            }
        }
    }
}

/** The progress bar inside a still (continue watching, history). */
@Composable
fun StillProgress(progress: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(8.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.36f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.0, 1.0).toFloat()).clip(CircleShape).background(LilacStyle.accent))
    }
}
