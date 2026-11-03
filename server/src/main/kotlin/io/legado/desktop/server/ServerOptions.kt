package io.legado.desktop.server

import java.nio.file.Path
import java.security.SecureRandom
import kotlin.system.exitProcess

data class ServerOptions(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val staticDir: Path,
    val token: String,
    val devNoAuth: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): ServerOptions {
            var host = "127.0.0.1"
            var port = 1122
            var dataDir = Path.of("data")
            var staticDir = Path.of("frontend", "dist")
            var token = randomToken()
            var devNoAuth = false

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--host" -> host = args.valueAt(++i)
                    "--port" -> port = args.valueAt(++i).toInt()
                    "--data-dir" -> dataDir = Path.of(args.valueAt(++i))
                    "--static-dir" -> staticDir = Path.of(args.valueAt(++i))
                    "--token" -> token = args.valueAt(++i)
                    "--dev-no-auth" -> devNoAuth = true
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> error("未知参数: ${args[i]}")
                }
                i++
            }
            require(port in 0..65535) { "端口超出范围: $port" }
            return ServerOptions(host, port, dataDir, staticDir, token, devNoAuth)
        }

        private fun Array<String>.valueAt(index: Int): String {
            require(index < size) { "参数 ${getOrNull(index - 1)} 缺少值" }
            return this[index]
        }

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private val USAGE = """
            legado-desktop server
              --host <addr>        默认 127.0.0.1
              --port <port>        默认 1122，0 表示随机端口
              --data-dir <path>    默认 ./data
              --static-dir <path>  默认 ./frontend/dist
              --token <token>      默认随机生成
              --dev-no-auth        仅开发用：/api/* 跳过 Cookie 鉴权
        """.trimIndent()
    }
}
