package io.mo.glassmic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.proto.TtsProvider
import io.mo.glassmic.ui.common.BackHeader
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GlassTextField
import io.mo.glassmic.ui.common.GlassToggle
import io.mo.glassmic.ui.common.GroupCard
import io.mo.glassmic.ui.common.LabeledField
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.RadioDot
import io.mo.glassmic.ui.common.SectionLabel
import io.mo.glassmic.ui.common.SettingRow
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.glass

@Composable
fun AiTtsSettingsScreen(
    onBack: () -> Unit,
    vm: AiTtsViewModel = hiltViewModel()
) {
    val ai by vm.ai.collectAsState()
    val toast = remember { SnackbarHostState() }
    val t = glass

    val sampleError by vm.sampleError.collectAsState()
    LaunchedEffect(sampleError) {
        sampleError?.let { toast.showSnackbar(it); vm.consumeSampleError() }
    }
    val sampleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setCloneSample(uri) }

    val saveMessage by vm.saveMessage.collectAsState()
    LaunchedEffect(saveMessage) {
        saveMessage?.let { toast.showSnackbar(it); vm.consumeSaveMessage() }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> if (uri != null) vm.saveGeneratedTo(uri) }

    val models by vm.models.collectAsState()
    LaunchedEffect(models) {
        (models as? TtsModelsState.Error)?.let { toast.showSnackbar(it.message) }
    }

    val provider = if (ai.provider == TtsProvider.UNRECOGNIZED) TtsProvider.OPENAI else ai.provider

    GlassPage(toast) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { BackHeader(stringResource(R.string.ai_tts_title), onBack) }

            item {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.ai_tts_enable),
                        subtitle = if (ai.enabled) stringResource(R.string.ai_tts_nav_enabled, providerLabel(provider))
                        else stringResource(R.string.ai_tts_nav_disabled),
                        onClick = { vm.setEnabled(!ai.enabled) },
                        verticalPadding = 8.dp
                    ) {
                        GlassToggle(ai.enabled, vm::setEnabled)
                    }
                }
            }

            if (ai.enabled) {
                item { SectionLabel(stringResource(R.string.ai_tts_protocol)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(TtsProvider.OPENAI, "OpenAI", "/audio/speech"),
                            Triple(TtsProvider.GEMINI, "Google Gemini", "generateContent"),
                            Triple(TtsProvider.MIMO, stringResource(R.string.ai_tts_provider_mimo_name), "chat/completions"),
                            Triple(TtsProvider.FISH, "Fish Audio", "/v1/tts")
                        ).forEach { (p, name, api) ->
                            ProtocolCard(name, api, selected = provider == p) { vm.setProvider(p) }
                        }
                    }
                }

                item { SectionLabel(stringResource(R.string.ai_tts_section_endpoint)) }
                item {
                    GroupCard {
                        TextFieldRow("Endpoint", ai.endpoint, stringResource(R.string.ai_tts_endpoint_placeholder), vm::setEndpoint)
                        TextFieldRow(stringResource(R.string.ai_tts_api_key), ai.apiKey, "sk-…", vm::setApiKey)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextFieldRow(stringResource(R.string.ai_tts_model), ai.model, stringResource(R.string.ai_tts_default_placeholder), vm::setModel)
                            }
                            Spacer(Modifier.width(10.dp))
                            ModelPicker(models, onFetch = vm::fetchModels, onPick = vm::setModel)
                        }
                        TextFieldRow(stringResource(R.string.ai_tts_voice), ai.voice, stringResource(R.string.ai_tts_default_placeholder), vm::setVoice)
                        if (provider != TtsProvider.MIMO || !ai.mimoStream) {
                            TextFieldRow(stringResource(R.string.ai_tts_format), ai.format, stringResource(R.string.ai_tts_format_placeholder), vm::setFormat)
                        }
                    }
                }

                if (provider == TtsProvider.MIMO) {
                    item {
                        Section(stringResource(R.string.ai_tts_section_mimo)) {
                            Text(
                                stringResource(R.string.ai_tts_mimo_advanced),
                                fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                            TextFieldRow(stringResource(R.string.ai_tts_style_prompt_hint), ai.stylePrompt, null, vm::setStylePrompt, mono = false)
                            SwitchRow(
                                label = stringResource(R.string.ai_tts_mimo_stream),
                                hint = stringResource(R.string.ai_tts_mimo_stream_hint),
                                checked = ai.mimoStream,
                                onChange = vm::setMimoStream
                            )
                            val optimizePreview = if (ai.hasMimoOptimizeTextPreview()) ai.mimoOptimizeTextPreview else true
                            SwitchRow(
                                label = stringResource(R.string.ai_tts_mimo_optimize_text_preview),
                                hint = stringResource(R.string.ai_tts_mimo_optimize_text_preview_hint),
                                checked = optimizePreview,
                                onChange = vm::setMimoOptimizeTextPreview
                            )
                            val hasSample = ai.cloneSamplePath.isNotBlank()
                            SettingRow(
                                title = stringResource(R.string.ai_tts_clone_sample),
                                subtitle = stringResource(if (hasSample) R.string.ai_tts_clone_sample_selected else R.string.ai_tts_clone_sample_none)
                            ) {
                                if (hasSample) {
                                    SoftButton(stringResource(R.string.ai_tts_clear), { vm.setCloneSample(null) }, height = 32.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                SoftButton(stringResource(R.string.ai_tts_choose_audio), { sampleLauncher.launch(arrayOf("audio/*")) }, height = 32.dp)
                            }
                        }
                    }
                }

                if (provider == TtsProvider.FISH) {
                    item {
                        Section(stringResource(R.string.ai_tts_section_fish)) {
                            Text(
                                stringResource(R.string.ai_tts_fish_hint),
                                fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                            TextFieldRow(stringResource(R.string.ai_tts_fish_voices), ai.fishVoices, stringResource(R.string.ai_tts_fish_voices_placeholder), vm::setFishVoices, mono = false)
                            Text(
                                stringResource(R.string.ai_tts_fish_voices_hint),
                                fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            val fishVoiceResults by vm.fishVoiceResults.collectAsState()
                            FishVoicePicker(
                                state = fishVoiceResults,
                                onFetch = vm::fetchFishVoices,
                                onPick = vm::pickFishVoice,
                                onDismiss = vm::dismissFishVoices
                            )
                            SwitchRow(
                                label = stringResource(R.string.ai_tts_fish_auto_tag),
                                hint = stringResource(R.string.ai_tts_fish_auto_tag_hint),
                                checked = ai.fishAutoTag,
                                onChange = vm::setFishAutoTag
                            )
                            if (ai.fishAutoTag) {
                                SwitchRow(
                                    label = stringResource(R.string.ai_tts_fish_humanize),
                                    hint = stringResource(R.string.ai_tts_fish_humanize_hint),
                                    checked = ai.fishHumanize,
                                    onChange = vm::setFishHumanize
                                )
                                TextFieldRow(stringResource(R.string.ai_tts_llm_endpoint), ai.llmEndpoint, "https://api.deepseek.com/v1", vm::setLlmEndpoint)
                                TextFieldRow(stringResource(R.string.ai_tts_llm_api_key), ai.llmApiKey, "sk-…", vm::setLlmApiKey)
                                TextFieldRow(stringResource(R.string.ai_tts_llm_model), ai.llmModel, "deepseek-chat", vm::setLlmModel)
                            }
                        }
                    }
                }

                item { SectionLabel(stringResource(R.string.ai_tts_section_test)) }
                item {
                    val test by vm.test.collectAsState()
                    val preview by vm.preview.collectAsState()
                    val defaultPreviewText = stringResource(R.string.ai_tts_preview_default_text)
                    var previewText by remember { mutableStateOf(defaultPreviewText) }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val testing = test is TtsTestState.Testing
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftButton(
                                    stringResource(if (testing) R.string.ai_tts_testing else R.string.ai_tts_test_connection),
                                    vm::testConnection, enabled = !testing, height = 40.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                (test as? TtsTestState.Result)?.let { r ->
                                    Text(r.message, fontSize = 13.sp, color = if (r.ok) t.okInk else t.err, modifier = Modifier.weight(1f))
                                }
                            }
                            GlassTextField(
                                value = previewText,
                                onValueChange = { previewText = it },
                                singleLine = false,
                                minLines = 3,
                                placeholder = stringResource(R.string.ai_tts_preview_text_label)
                            )
                            val generating = preview is TtsPreviewState.Generating
                            val playing = preview is TtsPreviewState.Playing
                            val ready = preview is TtsPreviewState.Ready || playing
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton(
                                    stringResource(
                                        when {
                                            generating -> R.string.ai_tts_generating
                                            ready -> R.string.ai_tts_regenerate
                                            else -> R.string.ai_tts_generate
                                        }
                                    ),
                                    onClick = { vm.generatePreview(previewText) },
                                    enabled = !generating,
                                    height = 46.dp,
                                    modifier = Modifier.weight(1f)
                                )
                                SoftButton(
                                    stringResource(if (playing) R.string.ai_tts_preview_playing else R.string.ai_tts_preview_play),
                                    vm::playPreview,
                                    enabled = ready && !playing,
                                    height = 46.dp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // 生成出试听音频后才显示「保存音频」
                            if (ready) {
                                SoftButton(
                                    stringResource(R.string.ai_tts_save_audio),
                                    { saveLauncher.launch(vm.suggestedFileName()) },
                                    height = 40.dp,
                                    textColor = t.primaryInk,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            (preview as? TtsPreviewState.Error)?.let {
                                Text(it.message, fontSize = 13.sp, color = t.err)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolCard(name: String, api: String, selected: Boolean, onClick: () -> Unit) {
    val t = glass
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SolidColor(t.primarySoft) else t.card)
            .border(BorderStroke(1.5.dp, if (selected) t.primary else t.border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(api, fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
        }
    }
}

/** 本地缓存输入，避免每个按键都触发 DataStore 回写后光标跳动。 */
@Composable
private fun TextFieldRow(label: String, initial: String, placeholder: String?, onCommit: (String) -> Unit, mono: Boolean = true) {
    var value by remember(initial) { mutableStateOf(initial) }
    LabeledField(label, value, { value = it; onCommit(it) }, placeholder, mono)
}

/** 获取 Fish 声音：搜索框留空 = 我的声音，否则按名称搜索；下拉中选一个即设为当前音色并加入预设。 */
@Composable
private fun FishVoicePicker(
    state: FishVoicesState,
    onFetch: (String) -> Unit,
    onPick: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = glass
    var query by remember { mutableStateOf("") }
    val loading = state is FishVoicesState.Loading
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        GlassTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.ai_tts_fish_voice_search_hint)
        )
        Spacer(Modifier.width(10.dp))
        Box {
            SoftButton(
                when {
                    loading -> stringResource(R.string.ai_tts_fish_fetching)
                    query.isBlank() -> stringResource(R.string.ai_tts_fish_my_voices)
                    else -> stringResource(R.string.ai_tts_fish_search)
                },
                onClick = { onFetch(query) },
                enabled = !loading
            )
            DropdownMenu(
                expanded = state is FishVoicesState.Loaded,
                onDismissRequest = onDismiss,
                shape = RoundedCornerShape(18.dp),
                containerColor = t.sheet
            ) {
                (state as? FishVoicesState.Loaded)?.voices?.forEach { (name, id) ->
                    DropdownMenuItem(text = { Text(name, fontSize = 14.sp) }, onClick = { onPick(name, id) })
                }
            }
        }
    }
    if (state is FishVoicesState.Error) {
        Text(state.message, fontSize = 12.sp, lineHeight = 17.sp, color = t.err, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun ModelPicker(state: TtsModelsState, onFetch: () -> Unit, onPick: (String) -> Unit) {
    val t = glass
    var open by remember { mutableStateOf(false) }
    val loading = state is TtsModelsState.Loading
    LaunchedEffect(state) { if (state is TtsModelsState.Loaded && state.models.isNotEmpty()) open = true }
    Box {
        SoftButton(
            when {
                loading -> stringResource(R.string.ai_tts_fetching_models)
                state is TtsModelsState.Loaded -> stringResource(R.string.ai_tts_models_count, state.models.size)
                else -> stringResource(R.string.ai_tts_fetch_models)
            },
            onClick = { if (state is TtsModelsState.Loaded && state.models.isNotEmpty()) open = true else onFetch() },
            enabled = !loading,
            textColor = if (state is TtsModelsState.Error) t.err else Color.Unspecified
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = t.sheet
        ) {
            (state as? TtsModelsState.Loaded)?.models?.forEach { m ->
                DropdownMenuItem(text = { Text(m, fontSize = 14.sp, fontFamily = MonoFamily) }, onClick = { onPick(m); open = false })
            }
        }
    }
}
