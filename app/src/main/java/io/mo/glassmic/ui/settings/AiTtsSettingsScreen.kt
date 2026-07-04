package io.mo.glassmic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.proto.TtsProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTtsSettingsScreen(
    onBack: () -> Unit,
    vm: AiTtsViewModel = hiltViewModel()
) {
    val ai by vm.ai.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val sampleError by vm.sampleError.collectAsState()
    LaunchedEffect(sampleError) {
        sampleError?.let { snackbar.showSnackbar(it); vm.consumeSampleError() }
    }
    val sampleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setCloneSample(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 供应商（TTS）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(snackbarData = it) } }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Section("接入") {
                    Text(
                        "为悬浮窗「文字转语音」配置在线 AI 合成。开启后优先走 AI，否则回退系统 TTS；" +
                            "endpoint / model 留空则按所选协议用官方默认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SwitchRow(label = "启用 AI TTS", checked = ai.enabled, onChange = vm::setEnabled)
                    ProviderPicker(ai.provider, vm::setProvider)
                }
            }

            item {
                Section("接口") {
                    ConfigTextField("自定义地址 endpoint（留空用官方默认）", ai.endpoint, vm::setEndpoint)
                    ConfigTextField("API Key", ai.apiKey, vm::setApiKey)
                    ConfigTextField("自定义模型 model（留空用默认）", ai.model, vm::setModel)
                    ModelPickerRow(vm.models.collectAsState().value, onFetch = vm::fetchModels, onPick = vm::setModel)
                }
            }

            item {
                Section("音色") {
                    ConfigTextField("音色 voice（preset / OpenAI / Gemini）", ai.voice, vm::setVoice)
                    if (ai.provider == TtsProvider.MIMO) {
                        Text(
                            "MiMo 进阶：模型填 -voicedesign 用文本描述定制音色（写在下方描述框）；" +
                                "填 -voiceclone 复刻音色（选一段 ≤30 秒、≤2MB 的参考音频）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        ConfigTextField(
                            "音色描述 / 风格（voicedesign 必填，preset 可选）",
                            ai.stylePrompt, vm::setStylePrompt
                        )
                        CloneSampleRow(
                            hasSample = ai.cloneSamplePath.isNotBlank(),
                            onPick = { sampleLauncher.launch(arrayOf("audio/*")) },
                            onClear = { vm.setCloneSample(null) }
                        )
                    }
                    ConfigTextField("返回格式 format（OpenAI：wav / pcm）", ai.format, vm::setFormat)
                }
            }

            item {
                Section("测试") {
                    TestRow(vm.test.collectAsState().value, onTest = vm::testConnection)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ProviderPicker(current: TtsProvider, onSelect: (TtsProvider) -> Unit) {
    val effective = if (current == TtsProvider.UNRECOGNIZED) TtsProvider.OPENAI else current
    Column {
        Text(
            "接口协议",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        PolicyOption("OpenAI（/audio/speech）", effective == TtsProvider.OPENAI) { onSelect(TtsProvider.OPENAI) }
        PolicyOption("Google Gemini（generateContent）", effective == TtsProvider.GEMINI) { onSelect(TtsProvider.GEMINI) }
        PolicyOption("小米 MiMo（chat/completions）", effective == TtsProvider.MIMO) { onSelect(TtsProvider.MIMO) }
    }
}

@Composable
private fun ModelPickerRow(state: TtsModelsState, onFetch: () -> Unit, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val loading = state is TtsModelsState.Loading
    LaunchedEffect(state) { if (state is TtsModelsState.Loaded && state.models.isNotEmpty()) open = true }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = onFetch, enabled = !loading) {
                Text(if (loading) "获取中…" else "获取模型")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (state as? TtsModelsState.Loaded)?.models?.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { onPick(m); open = false })
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        when (state) {
            is TtsModelsState.Error -> Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE5484D),
                modifier = Modifier.weight(1f)
            )
            is TtsModelsState.Loaded -> Text(
                "共 ${state.models.size} 个，点此重选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f).clickable { open = true }
            )
            else -> {}
        }
    }
}

@Composable
private fun CloneSampleRow(hasSample: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("参考音频（voiceclone）", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (hasSample) "已选择样本（≤30 秒 / ≤2MB）" else "未选择——复刻音色需先选一段音频样本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (hasSample) TextButton(onClick = onClear) { Text("清除") }
        TextButton(onClick = onPick) { Text("选择音频") }
    }
}

@Composable
private fun TestRow(state: TtsTestState, onTest: () -> Unit) {
    val testing = state is TtsTestState.Testing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onTest, enabled = !testing) {
            Text(if (testing) "测试中…" else "测试连接")
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (state is TtsTestState.Result) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.ok) Color(0xFF34C759) else Color(0xFFE5484D),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConfigTextField(label: String, initial: String, onCommit: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onCommit(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}
