package com.cayatur.winbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Talks to the real C# host over a socket.
 *
 * Skips itself when nothing is listening, so a normal `gradlew test` stays
 * hermetic. To actually exercise it:
 *
 *   dotnet run --project windows/tests/WinBridge.Core.Tests -- --serve 8737
 *   ./gradlew :core:protocol:test --tests '*LiveHandshakeTest*'
 *
 * From a phone, `adb reverse tcp:8737 tcp:8737` makes 127.0.0.1 on the device
 * reach the same server, which takes the network out of the variable set.
 */
class LiveHandshakeTest {

    private val port = System.getenv("WINBRIDGE_LIVE_PORT")?.toIntOrNull() ?: 8737
    private val psk: ByteArray = Base64.getDecoder()
        .decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")

    @Test
    fun `handshake and first state against the live host`() {
        val socket = try {
            // Connect first, then set options: setting them on an unconnected
            // socket makes the JDK materialise the impl early and, on Windows,
            // fail the connect with "Cannot assign requested address".
            Socket("127.0.0.1", port)
        } catch (e: ConnectException) {
            println("SKIP: nothing listening on 127.0.0.1:$port — start the --serve host to run this")
            return
        }

        socket.use {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000

            val session = ProtocolSession.connect(
                socket.getInputStream(),
                socket.getOutputStream(),
                LocalIdentity("11111111-1111-4111-8111-111111111111", "JVM test client"),
                psk,
            )

            println("handshake ok; peer=${session.peerName} (${session.peerPlatform})")
            assertEquals("windows", session.peerPlatform)

            session.sendJson(SubscribeMessage(rates = mapOf("system" to 1000, "volume" to 0)))

            var sawHost = false
            var sawVolume = false
            var sawSystem = false

            // The host pushes host state immediately and then ticks; a handful
            // of frames is enough to prove both directions really work.
            repeat(8) {
                val message = session.receive()
                when (message.jsonType) {
                    MessageTypes.STATE_HOST -> {
                        val host: HostState = message.decode()
                        println("host: ${host.name} caps.sleep=${host.caps.sleep}")
                        sawHost = true
                    }
                    MessageTypes.STATE_VOLUME -> {
                        val volume: VolumeState = message.decode()
                        println("volume: ${volume.level}%")
                        sawVolume = true
                    }
                    MessageTypes.STATE_SYSTEM -> {
                        val system: SystemState = message.decode()
                        println("system: cpu=${system.cpu}% ram=${system.ram.usedMb}/${system.ram.totalMb}MB")
                        sawSystem = true
                    }
                }
            }

            session.sendJson(PingMessage(echo = 4242))

            assertTrue("never received host state", sawHost)
            assertTrue("never received volume state", sawVolume)
            assertTrue("never received system state", sawSystem)

            session.sayGoodbye()
        }
    }
}
