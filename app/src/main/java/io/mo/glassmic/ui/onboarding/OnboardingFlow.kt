package io.mo.glassmic.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.data.runtime.PermissionStatus
import io.mo.glassmic.ui.common.GlassSurface

@Composable
fun OnboardingFlow(
    onCompleted: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsState()

    // 进入页面后自动检测一次
    LaunchedEffect(ui.step) {
        if (ui.step != OnboardingStep.Disclaimer && ui.step != OnboardingStep.Done) {
            vm.recheck()
        }
    }

    // step == Done 时执行完成
    LaunchedEffect(ui.step) {
        if (ui.step == OnboardingStep.Done) {
            vm.finish(onCompleted)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            when (ui.step) {
                OnboardingStep.Disclaimer -> DisclaimerPage(
                    agreed = ui.disclaimerAgreed,
                    onToggle = vm::toggleAgree,
                    onContinue = vm::next
                )
                OnboardingStep.Done -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> PermissionStepPage(
                    step = ui.step,
                    status = vm.currentStepStatus(),
                    checking = ui.checking,
                    onRetry = vm::recheck,
                    onNext = vm::next
                )
            }
        }
    }
}

@Composable
private fun DisclaimerPage(
    agreed: Boolean,
    onToggle: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_disclaimer_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.onboarding_disclaimer_body),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = agreed, onCheckedChange = onToggle)
            Text(
                text = stringResource(R.string.onboarding_disclaimer_agree),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = onContinue,
            enabled = agreed,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.onboarding_continue))
        }
    }
}

@Composable
private fun PermissionStepPage(
    step: OnboardingStep,
    status: PermissionStatus,
    checking: Boolean,
    onRetry: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val (titleRes, hintRes) = stepTexts(step)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        GlassSurface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIcon(status, checking)
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        Box(modifier = Modifier.weight(1f))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.onboarding_retry))
            }
            if (status == PermissionStatus.GRANTED) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            } else {
                Button(
                    onClick = { (context as? Activity)?.let { launchGrantIntent(it, step) } },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.onboarding_grant))
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: PermissionStatus, checking: Boolean) {
    val (icon, color) = when {
        checking -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.secondary
        status == PermissionStatus.GRANTED -> Icons.Default.Check to MaterialTheme.colorScheme.primary
        status == PermissionStatus.DENIED -> Icons.Default.Close to MaterialTheme.colorScheme.error
        else -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.secondary
    }
    Icon(
        icon, contentDescription = null,
        tint = color, modifier = Modifier.size(24.dp)
    )
}

private fun stepTexts(step: OnboardingStep): Pair<Int, Int> = when (step) {
    OnboardingStep.Root -> R.string.perm_root_title to R.string.perm_root_hint
    OnboardingStep.Notification -> R.string.perm_notif_title to R.string.perm_notif_hint
    OnboardingStep.Overlay -> R.string.perm_overlay_title to R.string.perm_overlay_hint
    OnboardingStep.FileAccess -> R.string.perm_file_title to R.string.perm_file_hint
    OnboardingStep.ForegroundService -> R.string.perm_fgs_title to R.string.perm_fgs_hint
    OnboardingStep.SafeMode -> R.string.perm_safemode_title to R.string.perm_safemode_hint
    else -> R.string.app_name to R.string.app_name
}

private fun launchGrantIntent(activity: Activity, step: OnboardingStep) {
    val intent = when (step) {
        OnboardingStep.Root -> Intent(Settings.ACTION_SETTINGS)  // 兜底，提示用户去 Magisk
        OnboardingStep.Notification -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        OnboardingStep.Overlay -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${activity.packageName}")
        )
        OnboardingStep.FileAccess -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${activity.packageName}"))
        OnboardingStep.ForegroundService -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${activity.packageName}"))
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    runCatching { activity.startActivity(intent) }
}
