package io.mo.glassmic.ui.library

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 音频批量导入选择器。
 *
 * 在 [ActivityResultContracts.OpenMultipleDocuments]（ACTION_OPEN_DOCUMENT + 多选）基础上，
 * 显式把 Intent 指向系统自带的〖文件〗（DocumentsUI）。
 *
 * 背景：偏原生 / 厂商定制 ROM 上，ACTION_OPEN_DOCUMENT 常被解析到厂商自带文件管理器，
 * 部分厂商选择器不支持多选、或无法浏览完整目录树，批量导入体验差。显式指定 DocumentsUI
 * 包可获得一致的文件夹浏览 + 多选批量导入体验。
 *
 * 兼容性：
 * - GMS 设备：com.google.android.documentsui
 * - AOSP / 无 GMS：com.android.documentsui
 * - 两者都不可用时不设置 package，回退到系统默认选择器，保证任何设备都能导入。
 */
class OpenMultipleAudioViaFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        resolveDocumentsUiPackage(context, intent)?.let { intent.setPackage(it) }
        return intent
    }

    private fun resolveDocumentsUiPackage(context: Context, base: Intent): String? {
        val pm = context.packageManager
        return DOCUMENTS_UI_PACKAGES.firstOrNull { pkg ->
            Intent(base).setPackage(pkg).resolveActivity(pm) != null
        }
    }

    companion object {
        private val DOCUMENTS_UI_PACKAGES = listOf(
            "com.google.android.documentsui",
            "com.android.documentsui"
        )
    }
}
