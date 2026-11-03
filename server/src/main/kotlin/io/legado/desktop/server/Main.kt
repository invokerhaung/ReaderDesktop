package io.legado.desktop.server

import io.legado.desktop.core.Engine
import io.legado.desktop.core.spi.CookieBridgeHolder
import io.legado.desktop.server.api.ApiRouter
import io.legado.desktop.server.http.DesktopHttpServer
import io.legado.desktop.server.http.WebCookieBridgeClient

fun main(args: Array<String>) {
    val options = ServerOptions.parse(args)
    val env = DesktopAppEnv(options.dataDir).apply { ensureDirs() }
    val engine = Engine(env)
    val context = ServerContext(env.dataDir)
    CookieBridgeHolder.bridge = WebCookieBridgeClient()
    val server = DesktopHttpServer(
        options,
        engine,
        ApiRouter(context),
        context.coverProxy,
        context.mediaProxy,
    )

    server.start(5_000, false)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    val base = "http://${options.host}:${server.listeningPort}"
    println("Legado Desktop server")
    println("  data:   ${env.dataDir.toAbsolutePath()}")
    println("  static: ${options.staticDir.toAbsolutePath()}")
    println("  health: $base/healthz")
    println("  open:   $base/bootstrap?token=${options.token}")

    Thread.currentThread().join()
}
