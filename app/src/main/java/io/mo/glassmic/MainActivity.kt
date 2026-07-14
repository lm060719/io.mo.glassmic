package io.mo.glassmic

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.ui.home.HomeScreen
import io.mo.glassmic.ui.library.LibraryScreen
import io.mo.glassmic.ui.onboarding.OnboardingFlow
import io.mo.glassmic.ui.scope.ScopeScreen
import io.mo.glassmic.ui.settings.AiTtsSettingsScreen
import io.mo.glassmic.ui.settings.SettingsScreen
import io.mo.glassmic.ui.settings.SafeModeScreen
import io.mo.glassmic.ui.theme.GlassMicTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // MainActivity 不是 AppCompatActivity，Android 13 以下需要在这里手动按已保存的语言包一层
    // Context，AppCompatDelegate.setApplicationLocales() 才能在冷启动时真正生效。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassMicTheme {
                val vm: GateViewModel = hiltViewModel()
                val gate by vm.gate.collectAsState()
                val nav = rememberNavController()
                AppNavHost(nav, gate)
            }
        }
    }
}

/** 全局门禁——决定启动后第一屏 */
data class GateDecision(
    val loading: Boolean = true,
    val safeMode: Boolean = false,
    val onboardingCompleted: Boolean = false
)

@HiltViewModel
class GateViewModel @Inject constructor(
    configStore: ConfigStore,
    safeModeRepo: SafeModeRepository
) : ViewModel() {

    private val _gate = MutableStateFlow(GateDecision())
    val gate: StateFlow<GateDecision> = _gate.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = configStore.current()
            _gate.value = GateDecision(
                loading = false,
                safeMode = safeModeRepo.isActive(),
                onboardingCompleted = cfg.onboardingCompleted
            )
        }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SCOPE = "scope"
    const val SETTINGS = "settings"
    const val AI_TTS = "ai_tts"
    const val SAFE_MODE = "safemode"
}

@Composable
private fun AppNavHost(nav: NavHostController, gate: GateDecision) {
    if (gate.loading) return

    // 首屏决策——严格遵循需求 §11 优先级
    val start = when {
        gate.safeMode -> Routes.SAFE_MODE
        !gate.onboardingCompleted -> Routes.ONBOARDING
        else -> Routes.HOME
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingFlow(onCompleted = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenScope = { nav.navigate(Routes.SCOPE) }
            )
        }
        composable(Routes.LIBRARY) { LibraryScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SCOPE) { ScopeScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAiTts = { nav.navigate(Routes.AI_TTS) }
            )
        }
        composable(Routes.AI_TTS) { AiTtsSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SAFE_MODE) {
            SafeModeScreen(onExitComplete = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.SAFE_MODE) { inclusive = true }
                }
            })
        }
    }
}
