package io.legado.app.utils

import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object EncoderUtils {

    fun escape(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch in "-_.!~*'()") {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(HEX[c ushr 4])
                sb.append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    fun unescape(value: String, charset: Charset = StandardCharsets.UTF_8): String =
        URLDecoder.decode(value, charset)

    fun encodeURI(value: String, charset: String = "UTF-8"): String =
        java.net.URLEncoder.encode(value, charset).replace("+", "%20")

    private val HEX = "0123456789ABCDEF".toCharArray()
}
