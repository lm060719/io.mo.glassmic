#pragma once

namespace glass {

/**
 * 安装 OpenSL ES 录音路径的 hook。
 *
 * 抖音等通过 OpenSL ES 的 buffer-queue 回调采集麦克风（TEOpenSLAudioRecord）。
 * 我们 hook 导出符号 slCreateEngine，再沿 OpenSL ES 稳定 ABI 逐层包装对象 vtable：
 *   slCreateEngine → Object::GetInterface → Engine::CreateAudioRecorder
 *     → Recorder::GetInterface(BufferQueue) → BufferQueue::{RegisterCallback,Enqueue}
 *   → 在录音 buffer-queue 回调里把缓冲覆盖成虚拟音源。
 *
 * 线程安全，重复调用安全。失败不致命（AAudio 路径仍可用）。
 */
bool install_opensl_hook();

} // namespace glass
