/*
 *  F-904 带外加好友 —— 节点名片编解码（纯函数，无 Android/Jami 依赖）
 *
 *  格式（v1）：JAMICARD1:<base64url(canonical_json)>
 *  - canonical_json：字段按 key 字典序、无空格、UTF-8
 *  - fp：sha256(canonical_json_without_fp) 前 4 字节的 8 个 hex 字符
 *  - base64url：无 padding
 *
 *  兼容：旧方案格式 JAMICARD1:<base64url(deflate(canonical_json))>（带 zlib 头）
 *        解码时先直接尝试 JSON，失败再尝试 inflate， thereby 兼容两种格式。
 *
 *  本文件是 F-904 唯一能跨 Jami 版本零改动的核心；不依赖任何内部符号。
 *  设计纪律见 Jami带外加好友正式方案_F-904.md §10.3。
 */
package cx.ring.utils

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.Inflater

object NodeCardCodec {
    const val MAGIC = "JAMICARD1"
    private const val MAX_DECODED_BYTES = 16 * 1024 // deflate 炸弹防护
    private const val MAX_TEXT_LEN = 4096

    /** 名片字段容器（key 均为可选，id 必填） */
    data class Card(
        val id: String,
        val n: String? = null,
        val d: String? = null,
        val v6: String? = null,
        val v4: String? = null,
        val ver: String? = null,
        val ts: Long? = null,
        val fp: String? = null
    )

    sealed class Result {
        data class Ok(val card: Card) : Result()
        data class Error(val reason: Reason) : Result()
    }

    enum class Reason { EMPTY, BAD_PREFIX, TOO_LONG, BAD_BASE64, BAD_JSON, BAD_MAGIC, BAD_FINGERPRINT, NO_ID, BAD_ID }

    private val b64urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDecoder = Base64.getUrlDecoder()

    /** 40 位小写 hex */
    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")

    /**
     * 编码名片为文本串。fp 由调用方数据自动计算并写入（覆盖传入的 fp）。
     * @param compress true=旧格式 deflate；false=新格式裸 JSON（默认，跨端兼容）
     */
    @JvmOverloads
    fun encode(card: Card, compress: Boolean = false): String {
        val map = TreeMap<String, Any>()
        map["m"] = MAGIC
        map["id"] = card.id
        card.n?.let { map["n"] = it }
        card.d?.let { map["d"] = it }
        card.v6?.let { map["v6"] = it }
        card.v4?.let { map["v4"] = it }
        card.ver?.let { map["ver"] = it }
        card.ts?.let { map["ts"] = it }
        // fp 计算输入 = 不含 fp 的 canonical json
        val withoutFp = canonical(map)
        val fp = fingerprint(withoutFp)
        map["fp"] = fp
        val json = canonical(map)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val payload = if (compress) deflate(bytes) else bytes
        return "$MAGIC:${b64urlEncoder.encodeToString(payload)}"
    }

    /** 解码并校验。任何失败返回 Error（不抛异常），UI 据此提示。 */
    fun decode(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Error(Reason.EMPTY)
        if (trimmed.length > MAX_TEXT_LEN) return Result.Error(Reason.TOO_LONG)
        val colon = trimmed.indexOf(':')
        if (colon <= 0 || trimmed.substring(0, colon) != MAGIC) return Result.Error(Reason.BAD_PREFIX)
        val token = trimmed.substring(colon + 1).trim()
        if (token.isEmpty()) return Result.Error(Reason.BAD_BASE64)

        // 1) base64url 还原
        val raw = try {
            b64urlDecoder.decode(pad(token))
        } catch (e: IllegalArgumentException) {
            return Result.Error(Reason.BAD_BASE64)
        }

        // 2) 先按裸 JSON 解，失败再按 deflate 解（兼容旧格式）
        var jsonStr: String? = null
        try {
            val candidate = String(raw, StandardCharsets.UTF_8)
            if (candidate.startsWith("{")) jsonStr = candidate
        } catch (e: Exception) { /* fall through to inflate */ }
        if (jsonStr == null) {
            val inflated = try { inflate(raw) } catch (e: Exception) { return Result.Error(Reason.BAD_JSON) }
            jsonStr = String(inflated, StandardCharsets.UTF_8)
        }

        // 3) JSON 解析
        val obj = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return Result.Error(Reason.BAD_JSON)
        }
        if (obj.optString("m", "") != MAGIC) return Result.Error(Reason.BAD_MAGIC)

        // 4) 指纹校验：重算不含 fp 的 canonical json
        val fpGiven = obj.optString("fp", "")
        if (fpGiven.isEmpty()) return Result.Error(Reason.BAD_FINGERPRINT)
        val withoutFp = TreeMap<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "fp") continue
            withoutFp[k] = obj.get(k)
        }
        // F-904 跨端兼容：Qt 端用 JSON.stringify（'/' 不转义），本端 JSONObject.quote
        // 会把 '/' 转义为 '\/'。若名片值含 '/'，两端 canonical 不一致导致指纹失败。
        // 两种规则都接受：本端 quote（转义 /）+ 标准 quote（不转义 /）。
        val fpComputed = fingerprint(canonical(withoutFp))
        val fpStd = fingerprint(canonicalStd(withoutFp))
        if (!constantTimeEquals(fpGiven, fpComputed) && !constantTimeEquals(fpGiven, fpStd)) {
            return Result.Error(Reason.BAD_FINGERPRINT)
        }

        // 5) 必填 id
        val id = obj.optString("id", "")
        if (id.isEmpty()) return Result.Error(Reason.NO_ID)
        if (!HEX40.matches(id)) return Result.Error(Reason.BAD_ID)

        return Result.Ok(
            Card(
                id = id,
                n = optNullable(obj, "n"),
                d = optNullable(obj, "d"),
                v6 = optNullable(obj, "v6"),
                v4 = optNullable(obj, "v4"),
                ver = optNullable(obj, "ver"),
                ts = if (obj.has("ts")) obj.getLong("ts") else null,
                fp = fpGiven
            )
        )
    }

    /** 从名片文件 JSON（{"magic":..,"cards":[...]}）解析出多张名片文本 */
    fun parseFile(json: String): List<String> {
        val root = JSONObject(json)
        if (root.optString("magic", "") != MAGIC) return emptyList()
        val arr = root.optJSONArray("cards") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 文件里的每张卡是对象，重编码为标准文本串再走统一校验
            val m = TreeMap<String, Any>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k != "fp") m[k] = o.get(k)
            }
            out.add(encode(Card(
                id = o.optString("id", ""),
                n = optNullable(o, "n"),
                d = optNullable(o, "d"),
                v6 = optNullable(o, "v6"),
                v4 = optNullable(o, "v4"),
                ver = optNullable(o, "ver"),
                ts = if (o.has("ts")) o.getLong("ts") else null
            )))
        }
        return out
    }

    /** 生成批量文件 JSON */
    fun toFile(cards: List<Card>, exportedAt: Long): String {
        val root = JSONObject()
        root.put("magic", MAGIC)
        root.put("version", 1)
        root.put("exportedAt", exportedAt)
        val arr = org.json.JSONArray()
        for (c in cards) {
            val o = JSONObject()
            o.put("m", MAGIC)
            o.put("id", c.id)
            c.n?.let { o.put("n", it) }
            c.d?.let { o.put("d", it) }
            c.v6?.let { o.put("v6", it) }
            c.v4?.let { o.put("v4", it) }
            c.ver?.let { o.put("ver", it) }
            c.ts?.let { o.put("ts", it) }
            val withoutFp = TreeMap<String, Any>()
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); withoutFp[k] = o.get(k) }
            o.put("fp", fingerprint(canonical(withoutFp)))
            arr.put(o)
        }
        root.put("cards", arr)
        return root.toString()
    }

    // ---- internals ----

    /** canonical JSON：key 字典序、无空格；值类型保持（字符串/number） */
    private fun canonical(map: TreeMap<String, Any>): String = canonical(map, true)

    /** 标准 JSON 转义变体（'/' 不转义），与 Qt 端 JSON.stringify 一致 */
    private fun canonicalStd(map: TreeMap<String, Any>): String = canonical(map, false)

    private fun canonical(map: TreeMap<String, Any>, androidQuote: Boolean): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k, androidQuote)).append(':')
            if (v is Number) sb.append(v.toString())
            else sb.append(quote(v.toString(), androidQuote))
        }
        sb.append('}')
        return sb.toString()
    }

    /** androidQuote=true 用 JSONObject.quote（'/' -> '\/'）；false 用标准 JSON（'/' 原样） */
    private fun quote(s: String, androidQuote: Boolean): String {
        if (androidQuote) return JSONObject.quote(s)
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code <= 0x1F) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun fingerprint(canonicalJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) sb.append(String.format("%02x", digest[i].toInt() and 0xff))
        return sb.toString()
    }

    private fun pad(token: String): String = token + "=".repeat((4 - token.length % 4) % 4)

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap=false -> zlib 头，与旧方案一致
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val i = Inflater() // nowrap=false -> 期望 zlib 头
        i.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n <= 0) {
                if (i.needsInput() || i.needsDictionary()) break
                if (i.finished()) break
                break
            }
            if (out.size() + n > MAX_DECODED_BYTES) throw IllegalArgumentException("decoded too large")
            out.write(buf, 0, n)
        }
        i.end()
        if (out.size() == 0) throw IllegalArgumentException("empty inflate")
        return out.toByteArray()
    }

    private fun optNullable(obj: JSONObject, key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
