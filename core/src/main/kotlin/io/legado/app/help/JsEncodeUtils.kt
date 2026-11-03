package io.legado.app.help

import io.legado.app.utils.MD5Utils
import java.util.Base64

interface JsEncodeUtils {

    fun md5Encode(str: String): String = MD5Utils.md5Encode(str)

    fun md5Encode16(str: String): String = MD5Utils.md5Encode16(str)

    fun base64Decode(str: String?): String =
        String(Base64.getDecoder().decode(str ?: ""), Charsets.UTF_8)

    fun base64Decode(str: String?, charset: String): String =
        String(Base64.getDecoder().decode(str ?: ""), charset(charset))

    fun base64DecodeToByteArray(str: String?): ByteArray? =
        runCatching { Base64.getDecoder().decode(str ?: "") }.getOrNull()

    fun base64Encode(str: String): String =
        Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))

    fun base64Encode(str: ByteArray): String = Base64.getEncoder().encodeToString(str)

    fun hexDecodeToByteArray(hex: String): ByteArray? = runCatching {
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()

    fun hexDecodeToString(hex: String): String? =
        hexDecodeToByteArray(hex)?.decodeToString()

    fun hexEncodeToString(utf8: String): String? =
        utf8.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}
