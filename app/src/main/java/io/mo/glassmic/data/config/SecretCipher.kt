package io.mo.glassmic.data.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.mo.glassmic.log.GlassLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配置里的凭据（AI TTS / LLM 的 API Key）落盘加密。
 *
 * 密钥是 Android Keystore 里不可导出的 AES-256-GCM 密钥，只在本设备、本应用内可用。
 * 加密结果形如 `enc1:<base64(iv ‖ 密文)>`；没有前缀的值视为旧版本留下的明文，照常读出，
 * 下次保存配置时自动转成密文。
 *
 * 解不开（如 Keystore 被清空）时返回空串：让用户重新填写，而不是把密文当成 Key 发出去。
 */
internal object SecretCipher {

    private const val PREFIX = "enc1:"
    private const val KEY_ALIAS = "glassmic_config_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
        }.getOrElse {
            // Keystore 不可用时宁可保持现状（与旧版本相同），也不能丢掉用户填的 Key。
            GlassLog.b("Config") { "secret encrypt failed: ${it.javaClass.simpleName}" }
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val sealed = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            require(sealed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            GlassLog.b("Config") { "secret decrypt failed: ${it.javaClass.simpleName}" }
            ""
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
