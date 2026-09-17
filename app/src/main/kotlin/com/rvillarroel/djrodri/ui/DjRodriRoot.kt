package com.rvillarroel.djrodri.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rvillarroel.djrodri.AppContainer
import com.rvillarroel.djrodri.spike.LabScreen
import com.rvillarroel.djrodri.ui.theme.DjColors
import com.rvillarroel.djrodri.ui.theme.DjTheme

sealed interface Screen {
    data object Playlists : Screen
    data class Playlist(val id: String) : Screen
    data object Party : Screen
    data object Settings : Screen
    data object Lab : Screen
}

/** Five screens and a back stack: a navigation library would be more code than the navigation itself. */
@Composable
fun DjRodriRoot(container: AppContainer, loginError: String?, onLogin: () -> Unit) {
    val loggedIn by container.auth.loggedIn.collectAsStateWithLifecycle()
    val party by container.party.state.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Screen>(if (party.active) Screen.Party else Screen.Playlists) }
    val top = stack.last()
    fun push(screen: Screen) = stack.add(screen)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        else if (stack.last() != Screen.Playlists) stack[0] = Screen.Playlists
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    DjTheme {
        Surface(Modifier.fillMaxSize(), color = DjColors.Night) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (!loggedIn) {
                    LoginScreen(loginError, onLogin)
                    return@Box
                }
                when (top) {
                    Screen.Playlists -> PlaylistsScreen(
                        onOpen = { push(Screen.Playlist(it)) },
                        onParty = { push(Screen.Party) },
                        onSettings = { push(Screen.Settings) },
                    )
                    is Screen.Playlist -> PlaylistDetailScreen(
                        playlistId = top.id,
                        onBack = ::pop,
                        onPartyStarted = {
                            stack.removeAll { it is Screen.Party || it is Screen.Playlist }
                            push(Screen.Party)
                        },
                    )
                    Screen.Party -> PartyScreen(onBack = ::pop, onSwitchPlaylist = {
                        stack.removeAll { it is Screen.Party }
                        if (stack.isEmpty()) stack.add(Screen.Playlists)
                    })
                    Screen.Settings -> SettingsScreen(onBack = ::pop, onLab = { push(Screen.Lab) })
                    Screen.Lab -> LabScreen(onBack = ::pop)
                }
            }
        }
    }
}
