@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.ui.components.SongMenuSheet
import com.maheshkatakam.melo.ui.home.HomeScreen
import com.maheshkatakam.melo.ui.library.AlbumScreen
import com.maheshkatakam.melo.ui.library.ArtistScreen
import com.maheshkatakam.melo.ui.library.CollectionScreen
import com.maheshkatakam.melo.ui.library.FolderScreen
import com.maheshkatakam.melo.ui.library.LibraryScreen
import com.maheshkatakam.melo.ui.library.PlaylistScreen
import com.maheshkatakam.melo.ui.onboarding.PermissionScreen
import com.maheshkatakam.melo.ui.player.MiniPlayer
import com.maheshkatakam.melo.ui.player.PlayerScreen
import com.maheshkatakam.melo.ui.search.SearchScreen
import com.maheshkatakam.melo.ui.settings.SettingsScreen
import com.maheshkatakam.melo.ui.sound.SoundScreen
import kotlinx.coroutines.flow.StateFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home),
    Tab("library", "Library", Icons.Rounded.LibraryMusic),
    Tab("search", "Search", Icons.Rounded.Search),
)

@Composable
fun MeloRoot(openPlayerSignal: StateFlow<Int>) {
    val container = LocalContainer.current
    val hasPermission by container.music.hasPermission.collectAsStateWithLifecycle()
    if (!hasPermission) {
        PermissionScreen()
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val now by container.player.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var songMenu by remember { mutableStateOf<Pair<Song, Long?>?>(null) }

    LaunchedEffect(Unit) {
        container.lyrics.messages.collect { snackbar.showSnackbar(it) }
    }
    val openSignal by openPlayerSignal.collectAsStateWithLifecycle()
    LaunchedEffect(openSignal) {
        if (openSignal > 0 && nav.currentDestination?.route != "player") {
            nav.navigate("player") { launchSingleTop = true }
        }
    }

    val navigator = remember(nav) { NavigatorImpl(nav) }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalSongMenu provides SongMenuLauncher { song, playlistId -> songMenu = song to playlistId },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (route != "player") {
                    Column {
                        if (now.hasContent) MiniPlayer(onOpen = navigator::openPlayer)
                        BottomBar(route, nav)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(180)) },
            ) {
                composable("home") { HomeScreen() }
                composable("library") { LibraryScreen() }
                composable("search") { SearchScreen() }
                composable(
                    "player",
                    enterTransition = { slideInVertically(tween(320)) { it } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = { slideOutVertically(tween(280)) { it } },
                ) { PlayerScreen() }
                composable(
                    "album/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry -> AlbumScreen(entry.arguments?.getLong("id") ?: 0L) }
                composable("artist/{name}") { entry ->
                    ArtistScreen(entry.arguments?.getString("name").orEmpty())
                }
                composable("folder/{path}") { entry ->
                    FolderScreen(entry.arguments?.getString("path").orEmpty())
                }
                composable(
                    "playlist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry -> PlaylistScreen(entry.arguments?.getLong("id") ?: 0L) }
                composable("collection/{kind}") { entry ->
                    CollectionScreen(entry.arguments?.getString("kind").orEmpty())
                }
                composable("sound") { SoundScreen() }
                composable("settings") { SettingsScreen() }
            }
        }

        songMenu?.let { (song, playlistId) ->
            SongMenuSheet(song, playlistId) { songMenu = null }
        }
    }
}

@Composable
private fun BottomBar(route: String?, nav: NavHostController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

private class NavigatorImpl(private val nav: NavHostController) : Navigator {
    override fun openPlayer() = nav.navigate("player") { launchSingleTop = true }
    override fun openAlbum(albumId: Long) = nav.navigate("album/$albumId")
    override fun openArtist(name: String) = nav.navigate("artist/${Uri.encode(name)}")
    override fun openFolder(path: String) = nav.navigate("folder/${Uri.encode(path)}")
    override fun openPlaylist(id: Long) = nav.navigate("playlist/$id")
    override fun openCollection(kind: String) = nav.navigate("collection/${Uri.encode(kind)}")
    override fun openSound() = nav.navigate("sound") { launchSingleTop = true }
    override fun openSettings() = nav.navigate("settings") { launchSingleTop = true }
    override fun back() {
        nav.popBackStack()
    }
}
