package io.mo.glassmic

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import io.mo.glassmic.ui.theme.LocalGlassTokens
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import io.mo.glassmic.ui.common.glass
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import io.mo.glassmic.ui.common.GlassBackground
import io.mo.glassmic.ui.common.liquidClickable
import io.mo.glassmic.ui.common.rememberLiquidEdges
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import io.mo.glassmic.ui.common.SplashOverlay
import io.mo.glassmic.ui.theme.LocalReduceMotion
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.mo.glassmic.ui.diag.DiagnosticScreen
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
                // 状态栏 / 导航栏图标跟随 App 自己的深浅色，而不是系统设置
                val dark = LocalGlassTokens.current.isDark
                val windowBg = LocalGlassTokens.current.bgBase
                SideEffect {
                    // 窗口底色设为页面底色，避免任何透明瞬间露出系统默认的白底
                    window.setBackgroundDrawable(ColorDrawable(windowBg.toArgb()))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val vm: GateViewModel = hiltViewModel()
                val gate by vm.gate.collectAsState()
                val nav = rememberNavController()
                // 开屏动画只在冷启动播放；系统“降低动画”时跳过
                val reduceMotion = LocalReduceMotion.current
                var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                // 导航外层再垫一层同样的玻璃背景：页面淡入淡出时露出的是同一张背景而不是白底
                GlassBackground {
                    AppNavHost(nav, gate)
                    if (showSplash && !reduceMotion) SplashOverlay(onFinished = { showSplash = false })
                }
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
    const val DIAGNOSTICS = "diagnostics"
}

private data class TabSpec(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(Routes.HOME, R.string.tab_mic, Icons.Rounded.Mic),
    TabSpec(Routes.LIBRARY, R.string.tab_library, Icons.Rounded.MusicNote),
    TabSpec(Routes.SETTINGS, R.string.tab_settings, Icons.Rounded.Tune)
)

@Composable
private fun AppNavHost(nav: NavHostController, gate: GateDecision) {
    if (gate.loading) return

    // 首屏决策——严格遵循需求 §11 优先级
    val start = when {
        gate.safeMode -> Routes.SAFE_MODE
        !gate.onboardingCompleted -> Routes.ONBOARDING
        else -> Routes.HOME
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val switchTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            // 首页始终在栈底（引导 / 安全模式进入首页时都会弹掉自己），以它为锚避免切 tab 时堆栈累积
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        val reduceMotion = LocalReduceMotion.current
        val isTab: (NavBackStackEntry) -> Boolean = { e -> TABS.any { it.route == e.destination.route } }
        NavHost(
            nav,
            startDestination = start,
            // 默认的 700ms 交叉淡化会让两页玻璃背景半透明叠在一起，显得发灰发糊：
            // 标签之间用很短的淡入（旧页几乎立刻退场），进入二级页用 iOS 式右侧推入 + 底页视差。
            enterTransition = {
                when {
                    reduceMotion -> EnterTransition.None
                    isTab(initialState) && isTab(targetState) -> fadeIn(tween(160))
                    else -> slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
                }
            },
            exitTransition = {
                when {
                    reduceMotion -> ExitTransition.None
                    isTab(initialState) && isTab(targetState) -> fadeOut(tween(120))
                    else -> slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 } +
                        fadeOut(tween(320), targetAlpha = 0.6f)
                }
            },
            popEnterTransition = {
                if (reduceMotion) EnterTransition.None
                else slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 4 }
            },
            popExitTransition = {
                if (reduceMotion) ExitTransition.None
                else slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
            }
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingFlow(onCompleted = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenLibrary = { switchTab(Routes.LIBRARY) },
                    onOpenScope = { nav.navigate(Routes.SCOPE) },
                    onOpenDiagnostic = { nav.navigate(Routes.DIAGNOSTICS) }
                )
            }
            composable(Routes.LIBRARY) { LibraryScreen() }
            composable(Routes.SCOPE) { ScopeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenScope = { nav.navigate(Routes.SCOPE) },
                    onOpenAiTts = { nav.navigate(Routes.AI_TTS) },
                    onOpenDiagnostic = { nav.navigate(Routes.DIAGNOSTICS) }
                )
            }
            composable(Routes.AI_TTS) { AiTtsSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SAFE_MODE) {
                SafeModeScreen(onExitComplete = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.SAFE_MODE) { inclusive = true }
                    }
                })
            }
        }

        if (TABS.any { it.route == currentRoute }) {
            GlassTabBar(
                current = currentRoute,
                onSelect = switchTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            )
        }
    }
}

/** 浮动玻璃底栏：三枚 92×52 胶囊，选中块像水滴一样在标签间流动。 */
@Composable
private fun GlassTabBar(current: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = glass
    val shape = RoundedCornerShape(32.dp)
    val index = TABS.indexOfFirst { it.route == current }.coerceAtLeast(0)
    val edges = rememberLiquidEdges(index)
    val slot = 92.dp
    val gap = 4.dp
    Box(
        modifier = modifier
            .shadow(if (t.glass) 18.dp else 8.dp, shape, ambientColor = t.shadowColor, spotColor = t.shadowColor)
            .clip(shape)
            .background(t.bar)
            .border(BorderStroke(1.dp, t.rim), shape)
            .padding(6.dp)
    ) {
        // 水滴选中块：左右边缘分别跟随，移动中被拉长、纵向略收
        Box(
            Modifier
                .offset(x = (slot + gap) * edges.start)
                .width((slot + gap) * (edges.end - edges.start) - gap)
                .height(52.dp)
                .graphicsLayer { scaleY = 1f - 0.12f * edges.stretch }
                .clip(RoundedCornerShape(26.dp))
                .background(t.primarySoft)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            TABS.forEachIndexed { i, tab ->
                val on = i == index
                val color = if (on) t.primaryInk else t.ink2
                Column(
                    modifier = Modifier
                        .liquidClickable(pressed = 0.9f) { if (!on) onSelect(tab.route) }
                        .size(slot, 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(tab.icon, null, tint = color, modifier = Modifier.size(22.dp))
                    Text(stringResource(tab.label), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
