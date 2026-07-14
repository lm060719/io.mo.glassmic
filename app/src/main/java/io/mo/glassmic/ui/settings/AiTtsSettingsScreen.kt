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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
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

    val saveMessage by vm.saveMessage.collectAsState()
    LaunchedEffect(saveMessage) {
        saveMessage?.let { snackbar.showSnackbar(it); vm.consumeSaveMessage() }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> if (uri != null) vm.saveGeneratedTo(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_tts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                Section(stringResource(R.string.ai_tts_section_access)) {
                    SwitchRow(label = stringResource(R.string.ai_tts_enable), checked = ai.enabled, onChange = vm::setEnabled)
                    ProviderPicker(ai.provider, vm::setProvider)
                }
            }

            item {
                Section(stringResource(R.string.ai_tts_section_endpoint)) {
                    ConfigTextField(stringResource(R.string.ai_tts_endpoint_hint), ai.endpoint, vm::setEndpoint)
                    ConfigTextField(stringResource(R.string.ai_tts_api_key), ai.apiKey, vm::setApiKey)
                    ConfigTextField(stringResource(R.string.ai_tts_model_hint), ai.model, vm::setModel)
                    ModelPickerRow(vm.models.collectAsState().value, onFetch = vm::fetchModels, onPick = vm::setModel)
                }
            }

            item {
                Section(stringResource(R.string.ai_tts_section_voice)) {
                    ConfigTextField(stringResource(R.string.ai_tts_voice_hint), ai.voice, vm::setVoice)
                    if (ai.provider == TtsProvider.MIMO) {
                        Text(
                            stringResource(R.string.ai_tts_mimo_advanced),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        ConfigTextField(
                            stringResource(R.string.ai_tts_style_prompt_hint),
                            ai.stylePrompt, vm::setStylePrompt
                        )
                        CloneSampleRow(
                            hasSample = ai.cloneSamplePath.isNotBlank(),
                            onPick = { sampleLauncher.launch(arrayOf("audio/*")) },
                            onClear = { vm.setCloneSample(null) }
                        )
                    }
                    ConfigTextField(stringResource(R.string.ai_tts_format_hint), ai.format, vm::setFormat)
                }
            }

            item {
                Section(stringResource(R.string.ai_tts_section_test)) {
                    TestRow(vm.test.collectAsState().value, onTest = vm::testConnection)
                    val defaultPreviewText = stringResource(R.string.ai_tts_preview_default_text)
                    var previewText by remember { mutableStateOf(defaultPreviewText) }
                    OutlinedTextField(
                        value = previewText,
                        onValueChange = { previewText = it },
                        label = { Text(stringResource(R.string.ai_tts_preview_text_label)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    PreviewRow(
                        state = vm.preview.collectAsState().value,
                        onGenerate = { vm.generatePreview(previewText) },
                        onPlay = vm::playPreview,
                        onSave = { saveLauncher.launch(vm.suggestedFileName()) }
                    )
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
            stringResource(R.string.ai_tts_protocol),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        PolicyOption(stringResource(R.string.ai_tts_provider_openai), effective == TtsProvider.OPENAI) { onSelect(TtsProvider.OPENAI) }
        PolicyOption(stringResource(R.string.ai_tts_provider_gemini), effective == TtsProvider.GEMINI) { onSelect(TtsProvider.GEMINI) }
        PolicyOption(stringResource(R.string.ai_tts_provider_mimo), effective == TtsProvider.MIMO) { onSelect(TtsProvider.MIMO) }
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
                Text(if (loading) stringResource(R.string.ai_tts_fetching_models) else stringResource(R.string.ai_tts_fetch_models))
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
                stringResource(R.string.ai_tts_models_count, state.models.size),
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
            Text(stringResource(R.string.ai_tts_clone_sample), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (hasSample) stringResource(R.string.ai_tts_clone_sample_selected) else stringResource(R.string.ai_tts_clone_sample_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (hasSample) TextButton(onClick = onClear) { Text(stringResource(R.string.ai_tts_clear)) }
        TextButton(onClick = onPick) { Text(stringResource(R.string.ai_tts_choose_audio)) }
    }
}

@Composable
private fun PreviewRow(
    state: TtsPreviewState,
    onGenerate: () -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    val generating = state is TtsPreviewState.Generating
    val playing = state is TtsPreviewState.Playing
    val ready = state is TtsPreviewState.Ready || playing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onGenerate, enabled = !generating) {
            Text(if (generating) stringResource(R.string.ai_tts_generating) else stringResource(R.string.ai_tts_generate))
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onPlay, enabled = ready && !playing) {
            Text(if (playing) stringResource(R.string.ai_tts_preview_playing) else stringResource(R.string.ai_tts_preview_play))
        }
        // 生成出试听音频后才显示「保存音频」
        if (ready) {
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onSave) { Text(stringResource(R.string.ai_tts_save_audio)) }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (state is TtsPreviewState.Error) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE5484D),
                modifier = Modifier.weight(1f)
            )
        }
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
            Text(if (testing) stringResource(R.string.ai_tts_testing) else stringResource(R.string.ai_tts_test_connection))
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
