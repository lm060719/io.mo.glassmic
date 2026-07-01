package io.mo.glassmic.service

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 让 Compose 能挂到 WindowManager overlay 的最小生命周期宿主。
 *
 * Service 不是 Activity，ComposeView 运行需要一套 ViewTree 上的
 * Lifecycle / ViewModelStore / SavedStateRegistry Owner。这里自建一套并注入 ComposeView，
 * 是"在悬浮窗里跑 Compose"的标准样板。
 *
 * 用法：
 *   val host = FloatingOverlayHost(this).also { it.onCreate() }
 *   host.setContent { ... }
 *   windowManager.addView(host.view, params)
 *   // 销毁：windowManager.removeView(host.view); host.onDestroy()
 */
class FloatingOverlayHost(context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    val view: ComposeView = ComposeView(context).apply {
        // 随宿主 Lifecycle 销毁而释放 Composition，避免泄漏
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setViewTreeLifecycleOwner(this@FloatingOverlayHost)
        setViewTreeViewModelStoreOwner(this@FloatingOverlayHost)
        setViewTreeSavedStateRegistryOwner(this@FloatingOverlayHost)
    }

    fun setContent(content: @Composable () -> Unit) = view.setContent(content)

    fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
