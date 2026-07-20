package moe.afox.dpsandbox.playground

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = PlaygroundConfig.fromEnvironment()
    embeddedServer(CIO, host = config.host, port = config.port) {
        playgroundModule(config)
    }.start(wait = true)
}
