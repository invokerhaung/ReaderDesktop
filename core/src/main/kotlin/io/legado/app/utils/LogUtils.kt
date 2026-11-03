package io.legado.app.utils

import org.slf4j.LoggerFactory

object LogUtils {
    private val logger = LoggerFactory.getLogger("legado")

    fun d(tag: String, message: String) {
        logger.debug("[$tag] $message")
    }

    fun d(tag: String, message: () -> String) {
        if (logger.isDebugEnabled) logger.debug("[${tag}] ${message()}")
    }

    fun i(tag: String, message: String) {
        logger.info("[$tag] $message")
    }

    fun w(tag: String, message: String) {
        logger.warn("[$tag] $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) logger.error("[$tag] $message")
        else logger.error("[$tag] $message", throwable)
    }
}
