package com.rvillarroel.djrodri.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rvillarroel.djrodri.AppContainer
import com.rvillarroel.djrodri.data.party.PartyState
import com.rvillarroel.djrodri.spike.LabScreen
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjTheme
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Motion
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space

/** The five places of the app. Discover → listen → organize → party, and the decks for your own files. */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Inicio", DjIcons.Home),
    SEARCH("Buscar", DjIcons.Search),
    LIBRARY("Biblioteca", DjIcons.Library),
    PARTY("Fiesta", DjIcons.Party),
    DJ("DJ", DjIcons.Mixer),
}

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Library : Screen
    data object Party : Screen
    data object Decks : Screen
    data class Playlist(val id: String) : Screen
    data object Settings : Screen
    data object Lab : Screen
}

private fun Tab.root(): Screen = when (this) {
    Tab.HOME -> Screen.Home
    Tab.SEARCH -> Screen.Search
    Tab.LIBRARY -> Screen.Library
    Tab.PARTY -> Screen.Party
    Tab.DJ -> Screen.Decks
}

/**
 * The shell: each tab keeps its own history, a bottom bar on the phone and a side rail when there is room, and a
 * mini player that follows you while a party is on. A navigation library would be more code than this.
 */
@Composable
fun DjRodriRoot(container: AppContainer, loginError: String?, onLogin: () -> Unit) {
    val loggedIn by container.auth.loggedIn.collectAsStateWithLifecycle()
    val party by container.party.state.collectAsStateWithLifecycle()
    val theme by container.settings.theme.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(if (party.active) Tab.PARTY else Tab.HOME) }
    val saveable = rememberSaveableStateHolder()
    val stacks = remember { mutableStateMapOf<Tab, List<Screen>>().apply { Tab.entries.forEach { put(it, listOf(it.root())) } } }
    val stack = stacks.getValue(tab)
    val top = stack.last()

    fun push(screen: Screen) {
        stacks[tab] = stacks.getValue(tab) + screen
    }
    fun pop() {
        val current = stacks.getValue(tab)
        if (current.size > 1) stacks[tab] = current.dropLast(1) else tab = Tab.HOME
    }
    fun select(target: Tab) {
        // Tapping the tab you are on takes you back to its top, as every music app does.
        if (target == tab) stacks[tab] = listOf(tab.root()) else tab = target
    }

    BackHandler(enabled = stack.size > 1 || tab != Tab.HOME) { pop() }

    DjTheme(theme) {
        Box(Modifier.fillMaxSize().background(Dj.colors.background).safeDrawingPadding()) {
            if (!loggedIn) {
                LoginScreen(loginError, onLogin)
                return@Box
            }
            val content: @Composable () -> Unit = {
                AnimatedContent(
                    targetState = top,
                    transitionSpec = { fadeIn(tween(Motion.BASE)) togetherWith fadeOut(tween(Motion.FAST)) },
                    label = "screen",
                ) { screen ->
                    // Each screen keeps its scroll position and state when you go deeper and come back.
                    saveable.SaveableStateProvider(screen.toString()) {
                        ScreenContent(
                            screen = screen,
                            onOpenPlaylist = { push(Screen.Playlist(it)) },
                            onBack = ::pop,
                            onSettings = { push(Screen.Settings) },
                            onLab = { push(Screen.Lab) },
                            onSearch = { select(Tab.SEARCH) },
                            onLibrary = { select(Tab.LIBRARY) },
                            onParty = {
                                stacks[Tab.PARTY] = listOf(Screen.Party)
                                tab = Tab.PARTY
                            },
                        )
                    }
                }
            }
            val miniPlayer = party.active && tab != Tab.PARTY

            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= WIDE) {
                    Row(Modifier.fillMaxSize()) {
                        SideRail(tab, ::select)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f)) { content() }
                            if (miniPlayer) MiniPlayer(party, container) { select(Tab.PARTY) }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        if (miniPlayer) MiniPlayer(party, container) { select(Tab.PARTY) }
                        BottomBar(tab, ::select)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    onOpenPlaylist: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onLab: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onParty: () -> Unit,
) {
    when (screen) {
        Screen.Home -> HomeScreen(onSearch = onSearch, onOpenPlaylist = onOpenPlaylist, onParty = onParty, onSettings = onSettings, onLibrary = onLibrary)
        Screen.Search -> SearchScreen(onParty = onParty)
        Screen.Library -> LibraryScreen(onOpenPlaylist = onOpenPlaylist, onSettings = onSettings)
        Screen.Party -> PartyScreen(onLibrary = onLibrary)
        Screen.Decks -> DjScreen()
        is Screen.Playlist -> PlaylistDetailScreen(playlistId = screen.id, onBack = onBack, onPartyStarted = onParty)
        Screen.Settings -> SettingsScreen(onBack = onBack, onLab = onLab)
        Screen.Lab -> LabScreen(onBack = onBack)
    }
}

/** Phone navigation: four items, the active one in red with a short bar above it. */
@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Dj.colors.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dj.colors.border))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab -> NavItem(tab, tab == current, Modifier.weight(1f)) { onSelect(tab) } }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) Dj.colors.accent else Dj.colors.textTertiary, tween(Motion.FAST), label = "nav")
    val bar by animateColorAsState(if (selected) Dj.colors.accent else Dj.colors.background, tween(Motion.BASE), label = "navBar")
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(20.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(bar))
        Icon(tab.icon, tab.label, tint = color, modifier = Modifier.padding(top = Space.xs).size(IconSize.md))
        Text(tab.label, style = DjType.label, color = color, modifier = Modifier.padding(top = Space.xxs))
    }
}

/** Tablet and landscape: the same four places down the side, so content gets the width. */
@Composable
private fun SideRail(current: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(88.dp).fillMaxHeight().padding(vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Text("DJ", style = DjType.section, color = Dj.colors.accent, modifier = Modifier.padding(bottom = Space.md))
            Tab.entries.forEach { tab -> NavItem(tab, tab == current, Modifier.height(64.dp)) { onSelect(tab) } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Dj.colors.border))
    }
}

/** The party follows you around the app: what plays, a play/pause, a skip, and one tap back to it. */
@Composable
private fun MiniPlayer(party: PartyState, container: AppContainer, onOpen: () -> Unit) {
    val track = party.current ?: return
    val progress = if (party.durationMs > 0) party.positionMs.toFloat() / party.durationMs else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xs, vertical = Space.xxs)
            .clip(RoundedCornerShape(Radius.md))
            .background(Dj.colors.surfaceSecondary)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(track.imageUrl, transitionSpec = { fadeIn(tween(Motion.BASE)) togetherWith fadeOut(tween(Motion.BASE)) }, label = "miniArt") { url ->
                Artwork(url, Modifier.size(44.dp), radius = Radius.sm)
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                Text(track.name, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = DjType.meta, color = Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DjIconButton(if (party.isPaused) DjIcons.Play else DjIcons.Pause, if (party.isPaused) "Reproducir" else "Pausa", container.party::togglePause)
            DjIconButton(DjIcons.Next, "Siguiente", container.party::next)
        }
        ProgressLine(progress, Modifier.padding(horizontal = Space.sm).padding(bottom = Space.xxs))
    }
}

private val WIDE = 600.dp
