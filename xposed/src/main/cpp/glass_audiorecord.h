#pragma once

namespace glass {

/**
 * 安装 native `android::AudioRecord::read` 的 hook（libaudioclient.so）。
 *
 * 抖音等通过 native 层的 android::AudioRecord 直接采集麦克风（TESystemAudioRecorder），
 * 既不经过 Java AudioRecord，也不经过 AAudio / OpenSL ES——表现为 AudioFlinger 里一条
 * AUDIO_INPUT_FLAG_NONE 的普通 record track。我们 hook 其导出符号
 * _ZN7android11AudioRecord4readEPvmb，在 read 返回后把缓冲覆盖成虚拟音源。
 *
 * 线程安全，重复调用安全。失败不致命。
 */
bool install_audiorecord_hook();

} // namespace glass
