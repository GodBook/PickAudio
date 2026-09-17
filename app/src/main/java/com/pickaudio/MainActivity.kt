package com.pickaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.ui.components.MiniPlayer
import com.pickaudio.ui.screens.*
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String) {
    object Library : Screen("library", "曲库")
    object Playlists : Screen("playlists", "歌单")
    object Search : Screen("search", "搜索")
    object Download : Screen("download", "下载管理")
    object SourceManager : Screen("sources", "音乐源管理")
    object Settings : Screen("settings", "设置")
    object PlaylistDetail : Screen("playlist_detail/{id}/{name}", "歌单详情") {
        fun createRoute(id: String, name: String) = "playlist_detail/$id/$name"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PickAudioApplication

        setContent {
            val userPrefs = app.userPreferences
            val coordinator = app.playbackCoordinator
            val themeMode by userPrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            PickAudioTheme(themeMode = themeMode) {
                MainApp(app = app)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(app: PickAudioApplication) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val coordinator = app.playbackCoordinator
    val currentTrack by coordinator.currentTrack.collectAsState()
    val isPlaying by coordinator.isPlaying.collectAsState()
    val progressMs by coordinator.currentPositionMs.collectAsState()
    val durationMs by coordinator.durationMs.collectAsState()

    var showFullPlayer by remember { mutableStateOf(false) }
    var currentRoute by remember { mutableStateOf(Screen.Library.route) }

    // Favorite status for current track
    val isCurrentFav by remember(currentTrack?.id) {
        if (currentTrack != null) {
            app.playlistRepository.isFavoriteFlow(currentTrack!!.id)
        } else {
            kotlinx.coroutines.flow.flowOf(false)
        }
    }.collectAsState(initial = false)

    // Back handler for full player
    BackHandler(enabled = showFullPlayer) {
        showFullPlayer = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Mini player floating above bottom bar
                    if (currentTrack != null && !showFullPlayer) {
                        MiniPlayer(
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            progressMs = progressMs,
                            durationMs = durationMs,
                            onPlayPauseClick = { coordinator.playOrPause() },
                            onNextClick = { coordinator.next() },
                            onClick = { showFullPlayer = true }
                        )
                    }

                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Screen.Library.route,
                            onClick = {
                                currentRoute = Screen.Library.route
                                navController.navigate(Screen.Library.route) {
                                    popUpTo(Screen.Library.route) { inclusive = true }
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "曲库") },
                            label = { Text("曲库") }
                        )
                        NavigationBarItem(
                            selected = currentRoute == Screen.Playlists.route,
                            onClick = {
                                currentRoute = Screen.Playlists.route
                                navController.navigate(Screen.Playlists.route)
                            },
                            icon = { Icon(Icons.Default.QueueMusic, contentDescription = "歌单") },
                            label = { Text("歌单") }
                        )
                        NavigationBarItem(
                            selected = currentRoute == Screen.Search.route,
                            onClick = {
                                currentRoute = Screen.Search.route
                                navController.navigate(Screen.Search.route)
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                            label = { Text("搜索") }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Library.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(Screen.Library.route) {
                    LibraryScreen(
                        libraryRepository = app.libraryRepository,
                        playlistRepository = app.playlistRepository,
                        playbackCoordinator = coordinator,
                        onNavigateToPlaylistDetail = { id ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(id, "歌单"))
                        },
                        onNavigateToDownload = {
                            navController.navigate(Screen.Download.route)
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route)
                        }
                    )
                }

                composable(Screen.Playlists.route) {
                    PlaylistsScreen(
                        playlistRepository = app.playlistRepository,
                        onPlaylistClick = { id, name ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(id, name))
                        }
                    )
                }

                composable(Screen.Search.route) {
                    SearchScreen(
                        userPreferences = app.userPreferences,
                        playbackCoordinator = coordinator,
                        downloadCoordinator = app.downloadCoordinator,
                        playlistRepository = app.playlistRepository,
                        sourceManager = app.sourceManager,
                        onNavigateToSourceManager = {
                            navController.navigate(Screen.SourceManager.route)
                        }
                    )
                }

                composable(Screen.Download.route) {
                    DownloadScreen(
                        downloadCoordinator = app.downloadCoordinator,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.SourceManager.route) {
                    SourceManagerScreen(
                        sourceManager = app.sourceManager,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        userPreferences = app.userPreferences,
                        backupManager = app.backupManager,
                        appUpdateManager = app.appUpdateManager,
                        onNavigateToSourceManager = {
                            navController.navigate(Screen.SourceManager.route)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.PlaylistDetail.route) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: ""
                    val name = backStackEntry.arguments?.getString("name") ?: "歌单"
                    PlaylistDetailScreen(
                        playlistId = id,
                        playlistName = name,
                        playlistRepository = app.playlistRepository,
                        playbackCoordinator = coordinator,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        // Animated Full Screen Player Overlay
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PlayerScreen(
                    coordinator = coordinator,
                    onCollapse = { showFullPlayer = false },
                    onToggleFavorite = { trackId ->
                        scope.launch { app.playlistRepository.toggleFavorite(trackId) }
                    },
                    isFavorite = isCurrentFav
                )
            }
        }
    }
}
