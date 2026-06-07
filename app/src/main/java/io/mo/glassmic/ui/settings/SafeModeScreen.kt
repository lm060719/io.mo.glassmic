package io.mo.glassmic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SafeModeInfo
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.diag.DiagnosticBundler
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.ui.common.GlassSurface
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SafeModeViewModel @Inject constructor(
    private val repo: SafeModeRepository,
    private val runtime: RuntimeStateHolder,
    private val bundler: DiagnosticBundler
) : ViewModel() {
    val info: StateFlow<SafeModeInfo?> = repo.state

    fun exit(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.exit()
            runtime.setSafeMode(false)
            onDone()
        }
    }

    fun exportDiagnostic(onReady: (android.net.Uri) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { bundler.export() }
                .onSuccess { onReady(bundler.shareUri(it)) }
                .onFailure(onError)
        }
    }
}

@Composable
fun SafeModeScreen(
    onExitComplete: () -> Unit,
    vm: SafeModeViewModel = hiltViewModel()
) {
    val info by vm.info.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp,
                tintAlpha = 0.25f
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.safe_mode_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = info?.reason?.let(::reasonText) ?: "原因：未知",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.safe_mode_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    vm.exportDiagnostic(
                        onReady = { uri ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "导出诊断包")) }
                        },
                        onError = { /* swallow; 日志已写 */ }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(R.string.safe_mode_export_diag)) }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { confirming = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text(stringResource(R.string.safe_mode_exit)) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.safe_mode_exit)) },
            text = { Text(stringResource(R.string.safe_mode_exit_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.exit(onExitComplete)
                }) { Text("确认退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }
}

private fun reasonText(r: SafeModeReason): String = "原因：" + when (r) {
    SafeModeReason.SYSTEM_UI_REPEATED_CRASH -> "30 秒内检测到系统界面异常 2 次以上"
    SafeModeReason.KEY_SYSTEM_PROC_CRASH -> "30 秒内检测到关键系统进程异常"
    SafeModeReason.MODULE_INIT_FAILURE -> "模块初始化连续失败"
    SafeModeReason.AUDIO_ENGINE_CONTINUOUS_FAILURE -> "音频引擎连续异常"
    SafeModeReason.LAST_BOOT_DID_NOT_EXIT_CLEANLY -> "上次未正常退出"
    SafeModeReason.USER_EMERGENCY_STOP -> "用户手动紧急停用"
}
