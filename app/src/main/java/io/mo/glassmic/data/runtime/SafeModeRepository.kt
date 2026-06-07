package io.mo.glassmic.data.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SafeModeInfo
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafeModeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val flagFile = File(context.filesDir, Constants.SAFE_MODE_FLAG)
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<SafeModeInfo?> = _state.asStateFlow()

    fun isActive(): Boolean = _state.value != null

    fun activate(reason: SafeModeReason, occurredAt: Long) {
        val info = SafeModeInfo(reason, occurredAt)
        runCatching {
            flagFile.writeText(
                JSONObject().apply {
                    put("reason", reason.name)
                    put("occurredAt", occurredAt)
                }.toString()
            )
        }
        _state.value = info
        GlassLog.b("SafeMode") { "进入安全模式：reason=$reason" }
    }

    /** 必须在 UI 二次确认后调用 */
    fun exit() {
        flagFile.delete()
        _state.value = null
        GlassLog.b("SafeMode") { "用户手动退出安全模式" }
    }

    fun snapshot(): SafeModeInfo? = _state.value

    private fun loadInitial(): SafeModeInfo? {
        if (!flagFile.exists()) return null
        return runCatching {
            val j = JSONObject(flagFile.readText())
            SafeModeInfo(
                SafeModeReason.valueOf(j.getString("reason")),
                j.getLong("occurredAt")
            )
        }.getOrNull()
    }
}
