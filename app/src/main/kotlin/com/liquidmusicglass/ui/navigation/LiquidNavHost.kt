package com.liquidmusicglass.ui.navigation

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.ui.screens.SettingsScreen
import com.liquidmusicglass.ui.screens.AlbumDetailScreen
import com.liquidmusicglass.ui.screens.ArtistDetailScreen
import com.liquidmusicglass.ui.screens.LibraryScreen
import com.liquidmusicglass.ui.screens.LocalAlbumDetailScreen
import com.liquidmusicglass.ui.screens.LocalArtistDetailScreen
import com.liquidmusicglass.ui.screens.LocalLibraryScreen
import com.liquidmusicglass.ui.screens.NewScreen
import com.liquidmusicglass.ui.screens.PlaylistDetailScreen
import com.liquidmusicglass.ui.screens.SearchScreen
import com.liquidmusicglass.ui.screens.WaveHomeScreen
import com.liquidmusicglass.ui.screens.YandexMusicScreen
import com.liquidmusicglass.ui.theme.ForceDarkContent

/**
 * Единый NavHost приложения (батч 15). Четыре вложенных графа — по одному на
 * нижнюю вкладку (Волна/Библиотека/New/Настройки). За счёт вложенности каждая
 * вкладка держит СВОЙ бэкстек; переключение вкладок в [BottomBar] делает
 * navigate с saveState/restoreState, поэтому позиция и стек вкладки сохраняются.
 *
 * Экраны-детали (альбом/артист/плейлист) регистрируются внутри графа каждой
 * вкладки с префиксом её тега — так они попадают в бэкстек ИМЕННО этой вкладки.
 *
 * Оверлеи (плеер, профиль, авторизация, эквалайзер, редакторы, диалоги) живут
 * НАД NavHost в [AppRoot] и дёргаются через колбэки-параметры.
 */
@Composable
fun LiquidNavHost(
    navController: NavHostController,
    backdrop: LayerBackdrop,
    waveAnimationsActive: Boolean,
    onOpenPlayer: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        route = "root",
        startDestination = NavRoutes.GRAPH_WAVE,
        modifier = modifier,
        // Пуш детали/поиска — выезд справа; возврат — уезд вправо. Тот же язык
        // движения, что был на прежних AnimatedVisibility деталей.
        enterTransition = {
            slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it } + fadeIn(tween(200))
        },
        exitTransition = {
            slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 350f)) { -it / 6 } + fadeOut(tween(150))
        },
        popEnterTransition = {
            slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { -it / 6 } + fadeIn(tween(200))
        },
        popExitTransition = {
            slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 350f)) { it } + fadeOut(tween(150))
        }
    ) {
        // ═══════════ Граф ВОЛНЫ ═══════════
        navigation(startDestination = NavRoutes.WAVE_HOME, route = NavRoutes.GRAPH_WAVE) {
            composable(NavRoutes.WAVE_HOME) { entry ->
                // This VM belongs to this destination's NavBackStackEntry. Android now
                // clears it when the entry is removed instead of leaving a remembered VM alive.
                val homeViewModel = ViewModelProvider(entry)[com.liquidmusicglass.ui.viewmodel.HomeViewModel::class.java]
                // Волна (портрет) всегда тёмная — эффекты/дым завязаны на тёмный
                // фон. В широком окне (телефон-альбом/планшет) вместо дыма —
                // карточная LandscapeHome, у неё ауры нет, поэтому НЕ форсим тёмную:
                // она должна следовать теме приложения, как и сайдбар слева.
                val win = com.liquidmusicglass.ui.rememberWindowInfo()
                val waveHome: @Composable () -> Unit = {
                    WaveHomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToSearch = { navController.navigate(NavRoutes.WAVE_SEARCH) },
                        onOpenPlayer = onOpenPlayer,
                        onNavigateToAlbum = { navController.navigate(NavRoutes.album(NavRoutes.TAB_WAVE, it)) },
                        onNavigateToArtist = { navController.navigate(NavRoutes.artist(NavRoutes.TAB_WAVE, it)) },
                        onNavigateToPlaylist = { navController.navigate(NavRoutes.playlist(NavRoutes.TAB_WAVE, it)) },
                        onOpenAuth = onOpenAuth,
                        onOpenProfile = onOpenProfile,
                        animationsActive = waveAnimationsActive
                    )
                }
                if (win.useSideBySide) waveHome() else ForceDarkContent { waveHome() }
            }
            composable(NavRoutes.WAVE_SEARCH) {
                SearchScreen(
                    onNavigateToAlbum = { navController.navigate(NavRoutes.album(NavRoutes.TAB_WAVE, it)) },
                    onNavigateToArtist = { navController.navigate(NavRoutes.artist(NavRoutes.TAB_WAVE, it)) },
                    onOpenPlayer = onOpenPlayer
                )
            }
            musicDetailDestinations(NavRoutes.TAB_WAVE, navController)
        }

        // ═══════════ Граф БИБЛИОТЕКИ ═══════════
        navigation(startDestination = NavRoutes.LIBRARY_HOME, route = NavRoutes.GRAPH_LIBRARY) {
            composable(NavRoutes.LIBRARY_HOME) {
                LibraryScreen(
                    onNavigateToAlbum = { navController.navigate(NavRoutes.album(NavRoutes.TAB_LIBRARY, it)) },
                    onNavigateToArtist = { navController.navigate(NavRoutes.artist(NavRoutes.TAB_LIBRARY, it)) },
                    onOpenPlaylist = { navController.navigate(NavRoutes.playlist(NavRoutes.TAB_LIBRARY, it)) },
                    onOpenLocalLibrary = { navController.navigate(NavRoutes.LOCAL_LIBRARY) },
                    onOpenYandex = { navController.navigate(NavRoutes.YANDEX_MUSIC) },
                    backdrop = backdrop
                )
            }
            musicDetailDestinations(NavRoutes.TAB_LIBRARY, navController)

            composable(NavRoutes.YANDEX_MUSIC) {
                YandexMusicScreen(onBack = { navController.popBackStack() })
            }

            composable(NavRoutes.LOCAL_LIBRARY) {
                LocalLibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { navController.navigate(NavRoutes.localArtist(it)) },
                    onOpenAlbum = { id, name -> navController.navigate(NavRoutes.localAlbum(id, name)) }
                )
            }
            composable(
                NavRoutes.LOCAL_ARTIST_ROUTE,
                arguments = listOf(navArgument(NavRoutes.ARG_NAME) { type = NavType.StringType })
            ) { entry ->
                val name = entry.arguments?.getString(NavRoutes.ARG_NAME).orEmpty()
                LocalArtistDetailScreen(
                    artistName = name,
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { id, n -> navController.navigate(NavRoutes.localAlbum(id, n)) }
                )
            }
            composable(
                NavRoutes.LOCAL_ALBUM_ROUTE,
                arguments = listOf(
                    navArgument(NavRoutes.ARG_ID) { type = NavType.LongType },
                    navArgument(NavRoutes.ARG_NAME) { type = NavType.StringType }
                )
            ) { entry ->
                val id = entry.arguments?.getLong(NavRoutes.ARG_ID) ?: -1L
                val name = entry.arguments?.getString(NavRoutes.ARG_NAME).orEmpty()
                LocalAlbumDetailScreen(
                    albumId = id,
                    albumName = name,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // ═══════════ Граф NEW ═══════════
        navigation(startDestination = NavRoutes.NEW_HOME, route = NavRoutes.GRAPH_NEW) {
            composable(NavRoutes.NEW_HOME) { entry ->
                val homeViewModel = ViewModelProvider(entry)[com.liquidmusicglass.ui.viewmodel.HomeViewModel::class.java]
                NewScreen(
                    viewModel = homeViewModel,
                    onNavigateToAlbum = { navController.navigate(NavRoutes.album(NavRoutes.TAB_NEW, it)) },
                    onNavigateToArtist = { navController.navigate(NavRoutes.artist(NavRoutes.TAB_NEW, it)) }
                )
            }
            musicDetailDestinations(NavRoutes.TAB_NEW, navController)
        }

        // ═══════════ Граф НАСТРОЕК ═══════════
        navigation(startDestination = NavRoutes.SETTINGS_HOME, route = NavRoutes.GRAPH_SETTINGS) {
            composable(NavRoutes.SETTINGS_HOME) {
                SettingsScreen(
                    onBack = {},
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenProfile = onOpenProfile,
                    showBack = false,
                    backdrop = backdrop
                )
            }
        }
    }
}

/**
 * Общие экраны-детали (альбом/артист/плейлист) для вкладки [tab]. Роуты
 * префиксуются тегом вкладки, поэтому одинаковые экраны не сталкиваются между
 * графами и складываются в бэкстек своей вкладки.
 */
private fun NavGraphBuilder.musicDetailDestinations(
    tab: String,
    navController: NavHostController
) {
    composable(
        NavRoutes.albumRoute(tab),
        arguments = listOf(navArgument(NavRoutes.ARG_ID) { type = NavType.StringType })
    ) { entry ->
        val id = entry.arguments?.getString(NavRoutes.ARG_ID).orEmpty()
        AlbumDetailScreen(
            albumId = id,
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        NavRoutes.artistRoute(tab),
        arguments = listOf(navArgument(NavRoutes.ARG_ID) { type = NavType.StringType })
    ) { entry ->
        val id = entry.arguments?.getString(NavRoutes.ARG_ID).orEmpty()
        ArtistDetailScreen(
            artistId = id,
            onBack = { navController.popBackStack() },
            onNavigateToAlbum = { navController.navigate(NavRoutes.album(tab, it)) },
            onNavigateToArtist = { navController.navigate(NavRoutes.artist(tab, it)) }
        )
    }
    composable(
        NavRoutes.playlistRoute(tab),
        arguments = listOf(navArgument(NavRoutes.ARG_ID) { type = NavType.StringType })
    ) { entry ->
        val id = entry.arguments?.getString(NavRoutes.ARG_ID).orEmpty()
        PlaylistDetailScreen(
            playlistId = id,
            onBack = { navController.popBackStack() }
        )
    }
}
