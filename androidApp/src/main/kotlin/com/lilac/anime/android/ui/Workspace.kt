package com.lilac.anime.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp

/** The desktop layout (ott.css), as iOS DesktopWorkspace: a header across the top with the brand, the destinations as
 *  words, search and settings, and the page under it. */
@Composable
fun Workspace() {
    val app = LocalApp.current
    val navigation = app.navigation
    val library = app.library
    val source = library.preferences.source
    BackHandler(enabled = navigation.details.isNotEmpty() || navigation.section != "home") {
        if (!navigation.back()) navigation.go("home")
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LilacStyle.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        WorkspaceHeader()
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            // A source chosen starts each page again for it (desktop location.reload()); 설정 stays where it is.
            key(if (navigation.section == "settings") "settings" else navigation.section + "|" + source) {
                Box(Modifier.fillMaxSize().lilacAppear()) {
                    when (navigation.section) {
                        "catalog" -> FullCatalogScreen()
                        "search" -> SearchScreen()
                        "history" -> HistoryScreen()
                        "favorites" -> SavedScreen()
                        "local" -> LocalVideoScreen()
                        "settings" -> SettingsScreen()
                        else -> HomeScreen()
                    }
                }
            }
            // A work opened from the page covers it, under the header (iOS NavigationStack in each page).
            navigation.details.lastOrNull()?.let { route ->
                key(navigation.details.size, route.source + ":" + route.anime.id) {
                    Box(Modifier.fillMaxSize().background(LilacStyle.background).lilacAppear()) { DetailScreen(route.anime, route.source) }
                }
            }
        }
    }
    // The player covers everything (iOS fullScreenCover), the page kept under it; a new episode starts it again.
    navigation.playing?.let { item ->
        key(item.anime.id + "#" + item.episodeID) {
            com.lilac.anime.android.player.PlayerScreen(item) { navigation.playing = null }
        }
    }
    }
}

@Composable
private fun WorkspaceHeader() {
    val app = LocalApp.current
    val navigation = app.navigation
    val wide = LilacStyle.wide
    val destinations = listOf("home" to "홈", "catalog" to "전체", "history" to "시청기록", "favorites" to "내 목록")
    var search by remember { mutableStateOf("") }
    LaunchedEffect(navigation.query) { search = navigation.query }
    Column {
        Row(
            Modifier.fillMaxWidth().height(54.dp).background(LilacStyle.background).padding(horizontal = LilacStyle.gutter),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 4.dp),
        ) {
            val brand = remember { MutableInteractionSource() }
            Text("LiLac", fontSize = if (wide) 24.sp else 19.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.accent,
                modifier = Modifier.lilacPress(brand).clickable(brand, null) { navigation.go("home") })
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = if (wide) 0.dp else 4.dp)) {
                destinations.forEach { (key, title) ->
                    val active = navigation.section == key
                    val press = remember { MutableInteractionSource() }
                    Text(title, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) LilacStyle.text else LilacStyle.muted, maxLines = 1,
                        modifier = Modifier.lilacPress(press).clickable(press, null) { navigation.go(key) }
                            .padding(horizontal = if (wide) 12.dp else 5.dp, vertical = 8.dp))
                }
            }
            if (wide) {
                Row(
                    Modifier.width(270.dp).height(38.dp).background(LilacStyle.card, CircleShape).border(1.dp, LilacStyle.line, CircleShape).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(Icons.Filled.Search, null, tint = LilacStyle.muted, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (search.isEmpty()) Text("보고 싶은 애니메이션을 검색해 보세요", fontSize = 14.sp, color = LilacStyle.muted, maxLines = 1)
                        BasicTextField(search, { search = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = LilacStyle.text),
                            cursorBrush = SolidColor(LilacStyle.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { navigation.search(search) }), modifier = Modifier.fillMaxWidth())
                    }
                    if (search.isNotEmpty()) Icon(Icons.Filled.Cancel, "검색어 지우기", tint = LilacStyle.muted, modifier = Modifier.size(18.dp).clickable { search = "" })
                }
            } else {
                HeaderIcon(Icons.Filled.Search, "검색", navigation.section == "search") { navigation.go("search") }
            }
            HeaderIcon(Icons.Outlined.Settings, "설정", navigation.section == "settings") { navigation.go("settings") }
            var menu by remember { mutableStateOf(false) }
            Box {
                HeaderIcon(Icons.Filled.MoreHoriz, "더보기", false) { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = LilacStyle.card) {
                    DropdownMenuItem(text = { Text("로컬 영상 열기", color = LilacStyle.text) }, leadingIcon = { Icon(Icons.Filled.Folder, null, tint = LilacStyle.text) },
                        onClick = { menu = false; navigation.go("local") })
                    HorizontalDivider(color = LilacStyle.line)
                    Text("영상 소스", fontSize = 12.sp, color = LilacStyle.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    ContentSources.keys.forEach { key ->
                        DropdownMenuItem(text = { Text(ContentSources.name(key), color = LilacStyle.text) },
                            trailingIcon = { if (key == app.library.preferences.source) Icon(Icons.Filled.Check, null, tint = LilacStyle.accent) },
                            onClick = { menu = false; app.library.update { it.copy(source = key) } })
                    }
                }
            }
        }
        if (navigation.section != "home" || navigation.details.isNotEmpty()) HorizontalDivider(color = LilacStyle.line, thickness = 1.dp)
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(Modifier.size(34.dp, 36.dp).lilacPress(press).clickable(press, null, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (active) LilacStyle.accent else LilacStyle.text.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
    }
}
