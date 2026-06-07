package io.mo.glassmic.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.PermissionChecker
import io.mo.glassmic.data.runtime.PermissionState
import io.mo.glassmic.data.runtime.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    Disclaimer,
    Root,
    Notification,
    Overlay,
    FileAccess,
    ForegroundService,
    SafeMode,
    Done
}

data class OnboardingUi(
    val step: OnboardingStep = OnboardingStep.Disclaimer,
    val disclaimerAgreed: Boolean = false,
    val permissions: PermissionState = PermissionState(),
    val checking: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val checker: PermissionChecker,
    private val configStore: ConfigStore
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()

    fun toggleAgree(agreed: Boolean) {
        _ui.update { it.copy(disclaimerAgreed = agreed) }
    }

    fun next() {
        val current = _ui.value
        _ui.update {
            it.copy(step = when (current.step) {
                OnboardingStep.Disclaimer -> OnboardingStep.Root
                OnboardingStep.Root -> OnboardingStep.Notification
                OnboardingStep.Notification -> OnboardingStep.Overlay
                OnboardingStep.Overlay -> OnboardingStep.FileAccess
                OnboardingStep.FileAccess -> OnboardingStep.ForegroundService
                OnboardingStep.ForegroundService -> OnboardingStep.SafeMode
                OnboardingStep.SafeMode -> OnboardingStep.Done
                OnboardingStep.Done -> OnboardingStep.Done
            })
        }
    }

    fun recheck() {
        _ui.update { it.copy(checking = true) }
        viewModelScope.launch {
            val state = checker.checkAll()
            _ui.update { it.copy(checking = false, permissions = state) }
        }
    }

    fun currentStepStatus(): PermissionStatus {
        val s = _ui.value
        return when (s.step) {
            OnboardingStep.Root -> s.permissions.root
            OnboardingStep.Notification -> s.permissions.notification
            OnboardingStep.Overlay -> s.permissions.overlay
            OnboardingStep.FileAccess -> s.permissions.fileAccess
            OnboardingStep.ForegroundService -> s.permissions.foregroundService
            OnboardingStep.SafeMode -> if (s.permissions.safeModeOk) PermissionStatus.GRANTED else PermissionStatus.DENIED
            else -> PermissionStatus.GRANTED
        }
    }

    fun finish(onComplete: () -> Unit) {
        viewModelScope.launch {
            configStore.update { b -> b.setOnboardingCompleted(true) }
            onComplete()
        }
    }
}
