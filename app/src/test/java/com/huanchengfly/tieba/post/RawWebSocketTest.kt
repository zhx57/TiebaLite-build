package com.huanchengfly.tieba.post

import com.huanchengfly.tieba.post.api.chat.ws.RawWebSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWebSocketTest {
    @Test
    fun serverEofTriggersOnClosed() {
        val connection = TestConnection { socket ->
            socket.shutdownOutput()
            assertEquals(-1, socket.getInputStream().read())
        }
        try {
            connection.connect()
            connection.allowServerAction.countDown()
            connection.awaitRemoteClose()
            assertEquals(1, connection.callbackCount.get())
            assertNull(connection.closeFailure.get())
            assertFalse(connection.webSocket.sendBinary(byteArrayOf(1)))
        } finally {
            connection.close()
        }
    }

    @Test
    fun serverCloseFrameTriggersOnClosedOnlyOnceEvenAfterRepeatedClose() {
        val connection = TestConnection { socket ->
            socket.getOutputStream().apply {
                write(byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xE8.toByte()))
                flush()
            }
            assertEquals(-1, socket.getInputStream().read())
        }
        try {
            connection.connect()
            connection.allowServerAction.countDown()
            connection.awaitRemoteClose()
            connection.webSocket.close()
            connection.webSocket.close()
            assertEquals(1, connection.callbackCount.get())
            assertNull(connection.closeFailure.get())
            assertFalse(connection.webSocket.sendBinary(byteArrayOf(1)))
        } finally {
            connection.close()
        }
    }

    @Test
    fun activeCloseIsIdempotentAndReleasesResourcesWithoutOnClosed() {
        val connection = TestConnection { socket ->
            val input = socket.getInputStream()
            assertEquals(0x88, input.read())
            assertEquals(0x80, input.read())
            repeat(4) {
                assertTrue("close frame mask is incomplete", input.read() >= 0)
            }
            assertEquals(-1, input.read())
        }
        try {
            connection.connect()
            connection.allowServerAction.countDown()
            connection.webSocket.close()
            connection.webSocket.close()
            connection.awaitServerAndReader()
            assertEquals(0, connection.callbackCount.get())
            assertFalse(connection.webSocket.sendBinary(byteArrayOf(1)))
            for (name in listOf("socket", "input", "output")) {
                val field = RawWebSocket::class.java.getDeclaredField(name)
                field.isAccessible = true
                assertNull("$name must be cleared after close", field.get(connection.webSocket))
            }
        } finally {
            connection.close()
        }
    }

    private class TestConnection(serverAction: (Socket) -> Unit) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = TIMEOUT_MS
        }
        private val acceptedSocket = AtomicReference<Socket?>()
        private val serverFailure = AtomicReference<Throwable?>()
        private val serverFinished = CountDownLatch(1)
        private val readerReady = CountDownLatch(1)
        private val reader = AtomicReference<Thread?>()
        private val closed = CountDownLatch(1)
        val allowServerAction = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val closeFailure = AtomicReference<Throwable?>()
        val webSocket = RawWebSocket(
            host = "127.0.0.1",
            port = server.localPort,
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
        ).apply {
            onBinary = {
                reader.set(Thread.currentThread())
                readerReady.countDown()
            }
            onClosed = { failure ->
                closeFailure.set(failure)
                callbackCount.incrementAndGet()
                closed.countDown()
            }
        }
        private val serverThread = Thread({
            try {
                server.accept().use { socket ->
                    acceptedSocket.set(socket)
                    socket.soTimeout = TIMEOUT_MS
                    completeHandshake(socket)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x82.toByte(), 0x00))
                        flush()
                    }
                    assertTrue("server action was not released", await(allowServerAction))
                    serverAction(socket)
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            } finally {
                serverFinished.countDown()
            }
        }, "raw-ws-test-server").apply {
            isDaemon = true
            start()
        }

        fun connect() {
            webSocket.connect()
            assertTrue("reader did not receive the ready frame", await(readerReady))
        }

        fun awaitRemoteClose() {
            assertTrue("onClosed was not called", await(closed))
            awaitServerAndReader()
        }

        fun awaitServerAndReader() {
            assertTrue("server did not finish", await(serverFinished))
            serverFailure.get()?.let { throw AssertionError("server thread failed", it) }
            serverThread.join(TIMEOUT_MS.toLong())
            assertFalse("server thread is still alive", serverThread.isAlive)
            val readerThread = reader.get() ?: throw AssertionError("reader was not captured")
            readerThread.join(TIMEOUT_MS.toLong())
            assertFalse("reader thread is still alive", readerThread.isAlive)
        }

        fun close() {
            try {
                webSocket.close()
            } finally {
                try {
                    server.close()
                } finally {
                    try {
                        acceptedSocket.get()?.close()
                    } finally {
                        allowServerAction.countDown()
                        serverThread.join(TIMEOUT_MS.toLong())
                        reader.get()?.join(TIMEOUT_MS.toLong())
                        assertFalse("server thread leaked", serverThread.isAlive)
                        assertFalse("reader thread leaked", reader.get()?.isAlive == true)
                        serverFailure.get()?.let { throw AssertionError("server thread failed", it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000

        fun await(latch: CountDownLatch): Boolean =
            latch.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)

        fun completeHandshake(socket: Socket) {
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            var lastFour = 0
            while (true) {
                val value = input.read()
                if (value == -1) throw IOException("client closed during handshake")
                header.write(value)
                if (header.size() > 8192) throw IOException("handshake header is too large")
                lastFour = (lastFour shl 8) or value
                if (lastFour == 0x0D0A0D0A) break
            }
            val key = header.toString(Charsets.ISO_8859_1.name()).lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
                )
            )
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                flush()
            }
        }
    }
}
