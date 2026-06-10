package io.terminus.app.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.terminus.app.di.ActiveSessionHolder
import io.terminus.app.ui.cities.CityManagerScreen
import io.terminus.app.ui.cities.ImportScreen
import io.terminus.app.ui.end.EndScreen
import io.terminus.app.ui.game.GameScreen
import io.terminus.app.ui.history.HistoryScreen
import io.terminus.app.ui.home.HomeScreen
import io.terminus.app.ui.setup.GameSetupScreen
import io.terminus.app.vm.CityViewModel
import io.terminus.app.vm.GameViewModel
import io.terminus.app.vm.HistoryViewModel
import io.terminus.app.vm.SetupViewModel

/** The Navigation-Compose route names (ARCHITECTURE.md §1.2 `nav`). */
object Routes {
    const val HOME = "home"
    const val CITIES = "cities"
    const val IMPORT = "import"
    const val SETUP = "setup"
    const val GAME = "game"
    const val END = "end"
    const val HISTORY = "history"
}

/**
 * The app's navigation graph (ARCHITECTURE.md §1.2 `nav`): a single [NavHost] over
 * the seven routes. [CityViewModel] is activity-scoped (shared by cities, import,
 * and setup); [SetupViewModel], [GameViewModel], and [HistoryViewModel] are scoped
 * to their back-stack entries — [GameViewModel] reads the process-wide
 * [ActiveSessionHolder], so game and end screens each binding a fresh instance is
 * correct.
 */
@Composable
fun TerminusNavHost() {
    val navController = rememberNavController()
    val cityVm: CityViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                hasActiveGame = ActiveSessionHolder.session != null,
                onNewGame = { navController.navigate(Routes.SETUP) },
                onCities = { navController.navigate(Routes.CITIES) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onResume = { navController.navigate(Routes.GAME) },
            )
        }

        composable(Routes.CITIES) {
            CityManagerScreen(
                cityVm = cityVm,
                onImport = { navController.navigate(Routes.IMPORT) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.IMPORT) {
            ImportScreen(
                cityVm = cityVm,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETUP) {
            val setupVm: SetupViewModel = viewModel()
            GameSetupScreen(
                setupVm = setupVm,
                cityVm = cityVm,
                onStart = {
                    navController.navigate(Routes.GAME) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.GAME) {
            val gameVm: GameViewModel = viewModel()
            GameScreen(
                gameVm = gameVm,
                onRoundEnd = { navController.navigate(Routes.END) },
                onAbandon = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }

        composable(Routes.END) {
            val gameVm: GameViewModel = viewModel()
            EndScreen(
                gameVm = gameVm,
                onNextRound = { navController.popBackStack(Routes.GAME, inclusive = false) },
                onEndMatch = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }

        composable(Routes.HISTORY) {
            val historyVm: HistoryViewModel = viewModel()
            HistoryScreen(
                historyVm = historyVm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
