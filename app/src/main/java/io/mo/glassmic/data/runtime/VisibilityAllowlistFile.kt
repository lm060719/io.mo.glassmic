package io.mo.glassmic.data.runtime

import io.mo.glassmic.core.Constants
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 把包可见性白名单用 root 写到 [Constants.VISIBILITY_ALLOWLIST_FILE]，供 system_server 的
 * SystemVisibilityHook 读取。
 *
 * 为什么不只靠 LSPosed remote preferences：system_server 只在开机时加载一次 remote preferences，
 * 之后 App 改了生效范围它也收不到（实测：新加入的微信直到重启都看不到 GlassMic 的 Provider，
 * 录音时只能退回真实麦克风）。文件由 hook 按修改时间重读，改完范围立即生效。
 *
 * 写入在 IO 线程串行执行，连续多次修改只落最后一次；名单没变不重复调用 su；
 * root 不可用时本进程内不再重试，避免反复拉起 su。
 */
object VisibilityAllowlistFile {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

    @Volatile private var pending: Set<String>? = null
    @Volatile private var written: Set<String>? = null
    @Volatile private var rootUnavailable = false

    fun sync(allowlist: Set<String>) {
        // 只接受合法包名：这些字符串会进 shell 命令
        val safe = allowlist.filterTo(sortedSetOf()) { PACKAGE_NAME.matches(it) }
        if (rootUnavailable || safe == written) return
        pending = safe
        scope.launch {
            lock.withLock {
                val target = pending ?: return@withLock
                if (rootUnavailable || target == written) return@withLock
                if (write(target)) {
                    written = target
                    GlassLog.b("Visibility") { "可见性白名单已写入 system（${target.size} 个应用）" }
                } else {
                    rootUnavailable = true
                    GlassLog.b("Visibility") { "可见性白名单写入失败（root 不可用？），改范围后需重启才生效" }
                }
            }
        }
    }

    private fun write(allowlist: Set<String>): Boolean {
        val file = Constants.VISIBILITY_ALLOWLIST_FILE
        val tmp = "$file.tmp"
        val content = if (allowlist.isEmpty()) "printf ''" else "printf '%s\\n' ${allowlist.joinToString(" ")}"
        // 先写临时文件再 mv：hook 永远读不到写了一半的文件；chcon 失败（个别 ROM）不影响结果
        val command = "$content > $tmp && chown system:system $tmp && chmod 600 $tmp && " +
            "{ chcon u:object_r:system_data_file:s0 $tmp 2>/dev/null; true; } && mv -f $tmp $file"
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
