package io.legado.app.utils

import java.security.MessageDigest

object MD5Utils {

    fun md5Encode(str: String?): String {
        if (str == null) return ""
        val digest = MessageDigest.getInstance("MD5").digest(str.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun md5Encode16(str: String): String = md5Encode(str).substring(8, 24)
}
