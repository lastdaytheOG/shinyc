package com.amar.vault.indexing.ppocr

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LineModelDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Host(private val served: Map<String, ByteArray>) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val url = "http://127.0.0.1:${socket.localPort}/pack"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    client.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val name = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }.removePrefix("/pack/")
                        var from = 0
                        while (true) {
                            val header = reader.readLine().orEmpty()
                            if (header.isEmpty()) break
                            if (header.startsWith("Range:", ignoreCase = true)) {
                                from = header.substringAfter("bytes=").substringBefore('-').trim().toInt()
                            }
                        }
                        asked += if (from > 0) "$name from $from" else name
                        val out = it.getOutputStream()
                        val body = served[name]
                        if (body == null) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            val status = if (from > 0) "206 Partial Content" else "200 OK"
                            out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size - from}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body, from, body.size - from)
                        }
                        out.flush()
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private var host: Host? = null

    @After
    fun stop() {
        host?.close()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(suffix: String = "") = """
        {"id": "set$suffix", "version": 1,
         "finder": {"model": "finder$suffix.onnx", "longSide": 1280, "thresh": 0.2, "boxThresh": 0.4, "unclip": 1.4},
         "readers": {"all": {"model": "all.onnx", "letters": "all.txt"},
                     "numbers": {"model": "numbers.onnx", "letters": "numbers.txt"},
                     "hindi": {"model": "hindi.onnx", "letters": "hindi.txt"}}}
    """.trimIndent().toByteArray()

    private fun pack(suffix: String = ""): Map<String, ByteArray> = mapOf(
        "finder$suffix.onnx" to ByteArray(200_000) { (it * 31 + 7).toByte() },
        "all.onnx" to ByteArray(70_000) { (it * 17).toByte() },
        "all.txt" to "a\nb\n".toByteArray(),
        "numbers.onnx" to ByteArray(500) { it.toByte() },
        "numbers.txt" to "0\n1\n".toByteArray(),
        "hindi.onnx" to ByteArray(900) { (it * 3).toByte() },
        "hindi.txt" to "क\nख\n".toByteArray(),
        "manifest.json" to manifest(suffix),
    )

    private fun serve(pack: Map<String, ByteArray>, listed: Map<String, ByteArray> = pack): Host {
        val list = listed.entries.joinToString(",") { (name, bytes) ->
            """{"name": "$name", "size": ${bytes.size}, "sha256": "${sha256(bytes)}"}"""
        }
        return Host(pack + ("files.json" to """{"files": [$list]}""".toByteArray())).also { host = it }
    }

    @Test
    fun everyFileArrivesWholeAndTheManifestLast() = runBlocking {
        val pack = pack()
        val served = serve(pack)
        val files = LineModelFiles(folder.root)
        var last = 0L to 0L

        val done = LineModelDownloader(files, served.url).download { a, b -> last = a to b }

        assertTrue(done)
        assertTrue(files.present())
        for ((name, bytes) in pack) assertArrayEquals(name, bytes, File(folder.root, name).readBytes())
        assertEquals("manifest.json", served.asked.last())
        assertEquals(pack.values.sumOf { it.size }.toLong(), last.first)
        assertEquals(last.first, last.second)
        assertEquals(pack.keys, folder.root.list()!!.toSet())
    }

    @Test
    fun aFileCutShortIsCarriedOnFromWhereItStopped() = runBlocking {
        val pack = pack()
        val served = serve(pack)
        File(folder.root, "finder.onnx.part").writeBytes(pack.getValue("finder.onnx").copyOf(120_000))

        val done = LineModelDownloader(LineModelFiles(folder.root), served.url).download()

        assertTrue(done)
        assertTrue("finder.onnx from 120000" in served.asked)
        assertArrayEquals(pack.getValue("finder.onnx"), File(folder.root, "finder.onnx").readBytes())
    }

    @Test
    fun filesAlreadyHereAndRightAreNotFetchedAgain() = runBlocking {
        val pack = pack()
        val served = serve(pack)
        val files = LineModelFiles(folder.root)
        assertTrue(LineModelDownloader(files, served.url).download())
        served.asked.clear()

        assertTrue(LineModelDownloader(files, served.url).download())

        assertEquals(listOf("files.json", "manifest.json"), served.asked.toList())
    }

    @Test
    fun aFileThatIsNotTheOneNamedStopsTheDownloadAndNoManifestIsWritten() = runBlocking {
        val pack = pack()
        val spoiled = pack + ("all.onnx" to ByteArray(70_000) { 1 })
        val served = serve(spoiled, listed = pack)
        val files = LineModelFiles(folder.root)

        val done = LineModelDownloader(files, served.url).download()

        assertFalse(done)
        assertFalse(files.present())
        assertFalse(File(folder.root, "manifest.json").exists())
        assertFalse(File(folder.root, "all.onnx").exists())
        assertFalse(File(folder.root, "all.onnx.part").exists())
    }

    @Test
    fun aNewerSetReplacesTheOldOneAndItsLeftoverFilesGo() = runBlocking {
        val files = LineModelFiles(folder.root)
        assertTrue(LineModelDownloader(files, serve(pack()).url).download())
        host?.close()
        val newer = pack("-2")

        val done = LineModelDownloader(files, serve(newer).url).download()

        assertTrue(done)
        assertEquals("set-2", files.manifest()!!.id)
        assertEquals(newer.keys, folder.root.list()!!.toSet())
    }

    @Test
    fun aHostThatIsNotThereIsAFailedDownloadNotACrash() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }

        assertFalse(LineModelDownloader(LineModelFiles(folder.root), "http://127.0.0.1:$port/pack").download())
    }
}
