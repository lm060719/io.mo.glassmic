package io.mo.glassmic.audio.tts

import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Base64

/** Read MiMo SSE events in order and emit PCM as each complete event arrives. */
internal fun readMimoPcmStream(input: InputStream, maxBytes: Long, onPcm: (ByteArray) -> Unit) {
    val bounded = object : FilterInputStream(input) {
        private var remaining = maxBytes

        // InputStreamReader reads through this bulk overload. Bound the stream before readLine allocates.
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(bytes, offset, length)
            if (count > 0) {
                remaining -= count
                check(remaining >= 0) { "MiMo stream exceeds the response size limit" }
            }
            return count
        }
    }
    val reader = bounded.bufferedReader(Charsets.UTF_8)
    val payload = StringBuilder()
    var receivedAudio = false
    var finished = false
    while (true) {
        val line = reader.readLine() ?: break
        if (line.startsWith("data:")) {
            if (payload.isNotEmpty()) payload.append('\n')
            payload.append(line.removePrefix("data:").removePrefix(" "))
            continue
        }
        if (line.isNotEmpty() || payload.isEmpty()) continue

        val data = payload.toString().trim()
        payload.setLength(0)
        if (data == "[DONE]") {
            finished = true
            break
        }
        if (data.isEmpty()) continue

        val event = JSONObject(data)
        event.optJSONObject("error")?.let { error("MiMo: ${it.optString("message", "Stream error").take(300)}") }
        val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
        val finishReason = choice.opt("finish_reason") as? String
        check(finishReason != "content_filter") { "MiMo filtered the requested audio" }
        if (finishReason == "stop" || finishReason == "length") finished = true

        val audio = choice.optJSONObject("delta")?.optJSONObject("audio")
        val encoded = audio?.opt("data") as? String
        if (encoded.isNullOrBlank()) continue
        val pcm = Base64.getDecoder().decode(encoded)
        check(pcm.size % 2 == 0) { "MiMo returned an incomplete PCM16 sample" }
        onPcm(pcm)
        receivedAudio = true
    }
    check(finished) { "MiMo stream ended before a completion event" }
    check(receivedAudio) { "MiMo returned no audio" }
}
