package com.pickaudio

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.ui.components.MiniPlayer
import com.pickaudio.ui.components.ConnectedMiniPlayer
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
        fun createRoute(id: String, name: String) = "playlist_detail/${android.net.Uri.encode(id)}/${android.net.Uri.encode(name)}"
    }
}
val LocalPlayerOverlayVisible = staticCompositionLocalOf { false }

class MainActivity : ComponentActivity() {
    var openDownloadsVersion by mutableIntStateOf(0)
        private set
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDownloadsVersion++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PickAudioApplication

        setContent {
            val userPrefs = app.userPreferences
            val coordinator = app.playbackCoordinator
            val themeMode by userPrefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            PickAudioTheme(themeMode = themeMode) {
                MainApp(app = app)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(app: PickAudioApplication, searchStateManager: SearchStateManager = app.searchStateManager) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val coordinator = app.playbackCoordinator
    val currentTrack by coordinator.currentTrack.collectAsStateWithLifecycle()
    val mainSnackbar = remember { SnackbarHostState() }

    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var pendingOpenPlayer by rememberSaveable { mutableStateOf(false) }
    val restored by coordinator.restored.collectAsStateWithLifecycle()
    var retryAfterSource by remember { mutableStateOf(false) }
    var currentRoute by remember { mutableStateOf(Screen.Library.route) }
    LaunchedEffect(currentTrack?.id, restored) { if (currentTrack == null && restored) showFullPlayer = false }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val activeRoute = navBackStackEntry?.destination?.route ?: currentRoute
    LaunchedEffect(activeRoute) {
        if (activeRoute != Screen.SourceManager.route && retryAfterSource) {
            retryAfterSource = false
            coordinator.retryCurrent()
            showFullPlayer = true
        }
    }
    LaunchedEffect((context as? MainActivity)?.openDownloadsVersion) {
        val activity = context as? MainActivity
        if (activity?.intent?.getBooleanExtra("open_downloads", false) == true) {
            activity.intent.removeExtra("open_downloads")
            navController.navigate(Screen.Download.route) { launchSingleTop = true }
        }
        if (activity?.intent?.getBooleanExtra("open_player", false) == true) {
            activity.intent.removeExtra("open_player")
            pendingOpenPlayer = true
        }
    }
    LaunchedEffect(pendingOpenPlayer, restored, currentTrack?.id) {
        if (pendingOpenPlayer && (currentTrack != null || restored)) {
            showFullPlayer = currentTrack != null
            pendingOpenPlayer = false
        }
    }

    // Favorite status for current track
    val isCurrentFav by remember(currentTrack?.id) {
        if (currentTrack != null) {
            app.playlistRepository.isFavoriteFlow(currentTrack!!.id)
        } else {
            kotlinx.coroutines.flow.flowOf(false)
        }
    }.collectAsStateWithLifecycle(initialValue = false)

    // Back handler for full player
    BackHandler(enabled = showFullPlayer) {
        showFullPlayer = false
    }

    CompositionLocalProvider(LocalPlayerOverlayVisible provides showFullPlayer) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(mainSnackbar) },
            bottomBar = {
                Column {
                    // Mini player floating above bottom bar
                    if (currentTrack != null && !showFullPlayer) {
                        ConnectedMiniPlayer(coordinator, onClick = { showFullPlayer = true })
                    }

                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        NavigationBarItem(
                            selected = activeRoute == Screen.Library.route,
                            onClick = {
                                currentRoute = Screen.Library.route
                                navController.navigate(Screen.Library.route) {
                                    popUpTo(Screen.Library.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(com.pickaudio.R.string.ui_mainactivity_001)) },
                            label = { Text(stringResource(com.pickaudio.R.string.ui_mainactivity_001)) }
                        )
                        NavigationBarItem(
                            selected = activeRoute == Screen.Playlists.route || activeRoute == Screen.PlaylistDetail.route,
                            onClick = {
                                currentRoute = Screen.Playlists.route
                                navController.navigate(Screen.Playlists.route) {
                                    popUpTo(Screen.Library.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(com.pickaudio.R.string.ui_mainactivity_002)) },
                            label = { Text(stringResource(com.pickaudio.R.string.ui_mainactivity_002)) }
                        )
                        NavigationBarItem(
                            selected = activeRoute == Screen.Search.route,
                            onClick = {
                                currentRoute = Screen.Search.route
                                navController.navigate(Screen.Search.route) {
                                    popUpTo(Screen.Library.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = stringResource(com.pickaudio.R.string.ui_mainactivity_003)) },
                            label = { Text(stringResource(com.pickaudio.R.string.ui_mainactivity_003)) }
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
                        downloadCoordinator = app.downloadCoordinator,
                        onNavigateToPlaylistDetail = { id ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(id, "歌单"))
                        },
                        onNavigateToDownload = {
                            navController.navigate(Screen.Download.route)
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route)
                        },
                        onNavigateToSource = { navController.navigate(Screen.SourceManager.route) },
                        onNavigateToSearch = { navController.navigate(Screen.Search.route) { launchSingleTop = true } }
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
                        libraryRepository = app.libraryRepository,
                        sourceManager = app.sourceManager,
                        searchStateManager = searchStateManager,
                        onOpenPlayer = { showFullPlayer = true },
                        onNavigateToSourceManager = {
                            navController.navigate(Screen.SourceManager.route)
                        },
                        onNavigateToDownload = { navController.navigate(Screen.Download.route) }
                    )
                }

                composable(Screen.Download.route) {
                    DownloadScreen(
                        downloadCoordinator = app.downloadCoordinator,
                        onBack = { navController.popBackStack() },
                        onOpenSource = { navController.navigate(Screen.SourceManager.route) }
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
                        downloadCoordinator = app.downloadCoordinator,
                        onBack = { navController.popBackStack() },
                        onOpenSource = { navController.navigate(Screen.SourceManager.route) }
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
                    , onOpenSource = {
                        showFullPlayer = false
                        retryAfterSource = true
                        navController.navigate(Screen.SourceManager.route)
                    },
                    onClearQueue = {
                        coordinator.clearQueue()
                        showFullPlayer = false
                        scope.launch {
                            if (mainSnackbar.showSnackbar("队列已清空", "撤销") == SnackbarResult.ActionPerformed) coordinator.undoClearQueue()
                        }
                    }
                )
            }
        }
    }
    }
}
