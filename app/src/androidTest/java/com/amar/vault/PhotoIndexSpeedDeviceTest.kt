package com.amar.vault

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.photos.PhotoEncoder
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.photos.PhotoPixels
import com.amar.vault.photos.PhotoThumbnails
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * Times each step of indexing a photo, on the vault's own photos, on this phone: decoding the
 * picture as the bulk scan does, its perceptual hash, preparing the photo model's pixels, the
 * photo model (at several settings, and other model files put beside it), and reading text off it
 * (English and Hindi one after the other and side by side, and the app's own photo reading).
 *
 * Nothing is written to the vault, and no text read off a photo leaves the phone: the result holds
 * timings, sizes, character counts and how alike two vectors are.
 *
 *     adb shell am instrument -w -e class com.amar.vault.PhotoIndexSpeedDeviceTest \
 *         -e speed measure [-e limit 40] [-e variants photo.acc4.onnx,...] \
 *         com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Variants are other photo model files in `files/photo-search/`, each timed and compared with the
 * model in use. The result is `files/photo-speed/result.jsonl`, one line per photo, and a last line
 * with the batch timings.
 */
@RunWith(AndroidJUnit4::class)
class PhotoIndexSpeedDeviceTest {

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s
    }

    private inline fun <T> timed(into: MutableMap<String, Double>, key: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val out = block()
        into[key] = (System.nanoTime() - t0) / 1e6
        return out
    }

    @Test
    fun measure() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed measure", args.getString("speed") == "measure")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = PhotoModelFiles(app)
        assumeTrue("photo search models are not on this device", files.present())
        val size = files.manifest()!!.inputSize
        val limit = args.getString("limit")?.toInt() ?: Int.MAX_VALUE
        val photos = VaultDatabase.get(app).vaultDao().getAll()
            .filter { it.itemType == ItemType.PHOTO && it.parentDocumentId == null }
            .sortedByDescending { it.timestamp }.take(limit)
        assertTrue("no photos in the vault", photos.isNotEmpty())

        val inUse = PhotoEncoder(files.photoModel().path, size, threads = 4)
        val eight = PhotoEncoder(files.photoModel().path, size, threads = 8)
        val variants = args.getString("variants").orEmpty().split(',').filter { it.isNotBlank() }
            .associateWith { PhotoEncoder(File(files.dir, it).path, size, threads = 4) }
        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val reader = ImageContentExtractor(app)
        val blank = FloatArray(3 * size * size)
        repeat(2) { (listOf(inUse, eight) + variants.values).forEach { it.encode(blank) } }

        val out = File(app.filesDir, "photo-speed").apply { mkdirs() }
        val kept = ArrayList<FloatArray>()
        File(out, "result.jsonl").bufferedWriter().use { w ->
            for ((n, p) in photos.withIndex()) {
                val uri = Uri.parse(p.uri)
                val ms = LinkedHashMap<String, Double>()
                val full: Bitmap = timed(ms, "decode_full") {
                    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } ?: continue
                timed(ms, "phash") {
                    val s = Bitmap.createScaledBitmap(full, 8, 8, true)
                    IntArray(64).also { s.getPixels(it, 0, 8, 0, 0, 8, 8) }
                    s.recycle()
                }
                val pixels = timed(ms, "pixels_full") { PhotoPixels.of(full, size) }
                val thumb = timed(ms, "thumbnail") { PhotoThumbnails.load(app, uri) }
                val thumbPixels = thumb?.let { t -> timed(ms, "pixels_thumbnail") { PhotoPixels.of(t, size) } }
                thumb?.recycle()
                val v = timed(ms, "model_t4") { inUse.encode(pixels) }
                timed(ms, "model_t8") { eight.encode(pixels) }
                val cos = LinkedHashMap<String, Double>()
                for ((name, enc) in variants) cos[name] = cosine(timed(ms, "model_$name") { enc.encode(pixels) }, v)
                thumbPixels?.let { cos["thumbnail_vs_full"] = cosine(inUse.encode(it), v) }
                if (kept.size < 4) kept += pixels

                val image = InputImage.fromBitmap(full, 0)
                val enText = timed(ms, "ocr_en") { en.process(image).await().text }
                val hiText = timed(ms, "ocr_hi") { hi.process(image).await().text }
                timed(ms, "ocr_en_hi_together") {
                    coroutineScope {
                        val a = async(Dispatchers.IO) { en.process(image).await() }
                        val b = async(Dispatchers.IO) { hi.process(image).await() }
                        a.await(); b.await()
                    }
                }
                val read = timed(ms, "read_photo_as_indexing") { reader.extract(full, photo = true) }
                w.write(JSONObject().apply {
                    put("n", n)
                    put("width", full.width).put("height", full.height)
                    put("ms", JSONObject(ms as Map<*, *>))
                    put("cosine", JSONObject(cos as Map<*, *>))
                    put("chars_en", enText.length).put("chars_hi", hiText.length)
                    put("chars_stored", read.ocrText.length).put("codes", read.qrPayloads.size)
                }.toString())
                w.newLine()
                full.recycle()
            }

            // Several photos in one run of the model, per photo, against one at a time.
            val batches = JSONObject()
            if (kept.size == 4) {
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(files.photoModel().path, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                })
                for (b in listOf(1, 2, 4)) {
                    val input = FloatArray(b * 3 * size * size).also { all ->
                        for (i in 0 until b) kept[i].copyInto(all, i * 3 * size * size)
                    }
                    val times = (0 until 6).map {
                        val t0 = System.nanoTime()
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(b.toLong(), 3, size.toLong(), size.toLong())).use { t ->
                            session.run(mapOf("pixels" to t)).close()
                        }
                        (System.nanoTime() - t0) / 1e6 / b
                    }.drop(1).sorted()
                    batches.put("batch_$b", times[times.size / 2])
                }
                session.close()
            }
            w.write(JSONObject().put("batches_ms_per_photo", batches).toString())
            w.newLine()
        }
        (listOf(inUse, eight) + variants.values).forEach { it.close() }
    }

    /**
     * The whole loop of a bulk scan over the vault's photos, minus its database writes, in several
     * designs: how long a photo takes from one to the next. Which photos have text is taken from
     * what the vault holds for them, so skipping the reading where there is none is timed as if
     * the guess were always right: the most that skipping can give.
     *
     *     -e speed pipeline -e variants photo.acc4.onnx
     */
    @Test
    fun pipeline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed pipeline", args.getString("speed") == "pipeline")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = PhotoModelFiles(app)
        assumeTrue("photo search models are not on this device", files.present())
        val size = files.manifest()!!.inputSize
        val photos = VaultDatabase.get(app).vaultDao().getAll()
            .filter { it.itemType == ItemType.PHOTO && it.parentDocumentId == null }
            .sortedByDescending { it.timestamp }
        val hasText = photos.associate { it.id to it.ocrText.isNotBlank() }
        val faster = args.getString("variants").orEmpty().split(',').first { it.isNotBlank() }
        val encoders = mapOf(
            "now" to PhotoEncoder(files.photoModel().path, size, threads = 4),
            "faster" to PhotoEncoder(File(files.dir, faster).path, size, threads = 4),
        )
        val reader = ImageContentExtractor(app)
        val blank = FloatArray(3 * size * size)
        encoders.values.forEach { it.encode(blank) }

        fun decode(p: VaultItem): Bitmap? =
            app.contentResolver.openInputStream(Uri.parse(p.uri))?.use { BitmapFactory.decodeStream(it) }
        fun hash(b: Bitmap) = Bitmap.createScaledBitmap(b, 8, 8, true).also { s ->
            IntArray(64).also { s.getPixels(it, 0, 8, 0, 0, 8, 8) }
        }.recycle()

        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val codes = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        // The app's photo reading with English and Hindi read side by side instead of one after the other.
        suspend fun readTogether(b: Bitmap) = coroutineScope {
            val image = InputImage.fromBitmap(b, 0)
            listOf(
                async(Dispatchers.IO) { en.process(image).await() },
                async(Dispatchers.IO) { hi.process(image).await() },
                async(Dispatchers.IO) { codes.process(image).await() },
            ).forEach { it.await() }
        }

        // name → (encoder, decode the next photo ahead, model before reading, read only photos with text, English and Hindi together)
        data class Design(val encoder: String, val ahead: Boolean, val modelFirst: Boolean, val skip: Boolean, val together: Boolean = false)
        val all = linkedMapOf(
            "as_now" to Design("now", ahead = false, modelFirst = false, skip = false),
            "faster_model" to Design("faster", ahead = false, modelFirst = false, skip = false),
            "faster_model_decode_ahead" to Design("faster", ahead = true, modelFirst = false, skip = false),
            "faster_model_skip_reading_best_case" to Design("faster", ahead = false, modelFirst = false, skip = true),
            "faster_model_decode_ahead_skip_reading_best_case" to Design("faster", ahead = true, modelFirst = false, skip = true),
            "faster_model_decode_ahead_model_first_skip_reading" to Design("faster", ahead = true, modelFirst = true, skip = true),
            "faster_model_reading_together" to Design("faster", ahead = false, modelFirst = false, skip = false, together = true),
        )
        val designs = args.getString("designs")?.split(',')?.associateWith { all.getValue(it) } ?: all
        // Seconds of rest before each design, and passes over the photos back to back in each.
        val rest = args.getString("rest")?.toLong() ?: 0L
        val passes = args.getString("passes")?.toInt() ?: 1
        val power = app.getSystemService(android.os.PowerManager::class.java)

        suspend fun run(d: Design): Double = coroutineScope {
            val enc = encoders.getValue(d.encoder)
            val lock = kotlinx.coroutines.sync.Mutex()
            val models = ArrayList<kotlinx.coroutines.Job>()
            val t0 = System.nanoTime()
            val decoded = kotlinx.coroutines.channels.Channel<Pair<VaultItem, Bitmap>>(if (d.ahead) 1 else 0)
            val producer = launch(Dispatchers.IO) {
                for (p in photos) decode(p)?.let { decoded.send(p to it) }
                decoded.close()
            }
            for ((p, full) in decoded) {
                hash(full)
                val pixels = PhotoPixels.of(full, size)
                val read = !d.skip || hasText.getValue(p.id)
                suspend fun readIt() { if (d.together) readTogether(full) else reader.extract(full, photo = true) }
                if (d.modelFirst) {
                    lock.withLockSuspend { enc.encode(pixels) }
                    if (read) readIt()
                } else {
                    models += launch(Dispatchers.Default) { lock.withLockSuspend { enc.encode(pixels) } }
                    if (read) readIt()
                }
                full.recycle()
            }
            producer.join()
            models.forEach { it.join() }
            (System.nanoTime() - t0) / 1e6 / photos.size
        }

        val results = designs.keys.associateWith { org.json.JSONArray() }
        for (order in listOf(designs.keys.toList(), designs.keys.reversed())) {
            for (name in order) {
                if (rest > 0) kotlinx.coroutines.delay(rest * 1000)
                repeat(passes) {
                    val ms = run(designs.getValue(name))
                    results.getValue(name).put(JSONObject().put("ms_per_photo", ms).put("thermal_after", power.currentThermalStatus))
                }
            }
        }
        val out = File(app.filesDir, "photo-speed").apply { mkdirs() }
        File(out, "pipeline.json").writeText(JSONObject().apply {
            put("photos", photos.size).put("with_text", hasText.values.count { it })
            put("rest_s", rest).put("passes", passes)
            for ((name, runs) in results) put(name, runs)
        }.toString(1))
        encoders.values.forEach { it.close() }
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockSuspend(block: () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    /**
     * The photo model in use and each variant on the same prepared pixels, on this phone's own
     * runtime: benchmark photos as 256 × 256 RGB bytes, one after another, in
     * `files/photo-speed/<set>.u8`; each model's vectors written beside them as `<set>.<model>.f32`
     * (little-endian floats, 768 per photo), to be scored on a desktop against the same queries.
     *
     *     -e speed accuracy -e sets xm3600,gallery -e variants photo.acc4.onnx
     */
    @Test
    fun accuracy() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed accuracy", args.getString("speed") == "accuracy")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = PhotoModelFiles(app)
        assumeTrue("photo search models are not on this device", files.present())
        val size = files.manifest()!!.inputSize
        val dir = File(app.filesDir, "photo-speed")
        // -e only_variants true: the model in use was scored on this set before.
        val models = (if (args.getString("only_variants") == "true") emptyList() else listOf(files.photoModel().name)) +
            args.getString("variants").orEmpty().split(',').filter { it.isNotBlank() }
        val frame = size * size * 3
        for (set in args.getString("sets").orEmpty().split(',').filter { it.isNotBlank() }) {
            val source = File(dir, "$set.u8")
            assertTrue("put $set.u8 in ${dir.path}", source.exists() && source.length() % frame == 0L)
            val count = (source.length() / frame).toInt()
            for (model in models) {
                PhotoEncoder(File(files.dir, model).path, size, threads = 4).use { enc ->
                    val bytes = ByteArray(frame)
                    val argb = IntArray(size * size)
                    val out = java.io.DataOutputStream(File(dir, "$set.$model.f32").outputStream().buffered(1 shl 20))
                    val buf = java.nio.ByteBuffer.allocate(4 * 768).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    source.inputStream().buffered(1 shl 20).use { input ->
                        repeat(count) {
                            var read = 0
                            while (read < frame) read += input.read(bytes, read, frame - read).also { check(it > 0) }
                            for (i in argb.indices) {
                                val r = bytes[3 * i].toInt() and 0xFF
                                val g = bytes[3 * i + 1].toInt() and 0xFF
                                val b = bytes[3 * i + 2].toInt() and 0xFF
                                argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            val v = enc.encode(PhotoPixels.fromArgb(argb, size))
                            buf.clear()
                            v.forEach { buf.putFloat(it) }
                            out.write(buf.array(), 0, 4 * v.size)
                        }
                    }
                    out.close()
                }
            }
        }
    }

    /**
     * One photo model at several thread counts, and with its worker threads held to the fast cores
     * (ONNX Runtime numbers processors from 1): the median of 12 runs on a vault photo each.
     *
     *     -e speed threads -e variants photo.acc4.onnx
     */
    @Test
    fun threads() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed threads", args.getString("speed") == "threads")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = PhotoModelFiles(app)
        assumeTrue("photo search models are not on this device", files.present())
        val size = files.manifest()!!.inputSize
        val model = File(files.dir, args.getString("variants")!!.split(',').first()).path
        val photo = VaultDatabase.get(app).vaultDao().getAll()
            .first { it.itemType == ItemType.PHOTO && it.parentDocumentId == null }
        val pixels = PhotoPixels.of(PhotoThumbnails.load(app, Uri.parse(photo.uri), side = 1024)!!, size)
        val env = OrtEnvironment.getEnvironment()
        // name → (threads, affinities of the threads after the caller's)
        val settings = linkedMapOf(
            "t2" to (2 to null), "t3" to (3 to null), "t4" to (4 to null), "t5" to (5 to null), "t6" to (6 to null),
            "t3_fast_cores" to (3 to "7;8"), "t4_fast_cores" to (4 to "6;7;8"), "t5_fast_cores" to (5 to "5;6;7;8"),
        )
        val result = JSONObject()
        for ((name, s) in settings) {
            try {
                val session = env.createSession(model, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(s.first)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    s.second?.let { addConfigEntry("session.intra_op_thread_affinities", it) }
                })
                val times = (0 until 14).map {
                    val t0 = System.nanoTime()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
                        session.run(mapOf("pixels" to t)).close()
                    }
                    (System.nanoTime() - t0) / 1e6
                }.drop(2).sorted()
                result.put(name, times[times.size / 2])
                session.close()
            } catch (e: Exception) {
                result.put(name, "failed: ${e.message}")
            }
        }
        File(File(app.filesDir, "photo-speed").apply { mkdirs() }, "threads.json").writeText(result.toString(1))
    }

    /**
     * Whether the app reads each vault photo's text exactly as the vault holds it: the picture
     * decoded as the reader that stored that text decodes it, read by the app's photo reading as it
     * is now, compared character for character. Only counts leave the phone.
     *
     *     -e speed reading
     */
    @Test
    fun reading() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed reading", args.getString("speed") == "reading")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val photos = VaultDatabase.get(app).vaultDao().getAll()
            .filter { it.itemType == ItemType.PHOTO && it.parentDocumentId == null }
        val reader = ImageContentExtractor(app)
        var same = 0
        var differ = 0
        var unreadable = 0
        val ms = ArrayList<Double>()
        for (p in photos) {
            val bitmap = com.amar.vault.indexing.BitmapDecoding.loadSoftware(app, Uri.parse(p.uri))
            if (bitmap == null) { unreadable++; continue }
            val t0 = System.nanoTime()
            val read = reader.extract(bitmap, photo = true)
            ms += (System.nanoTime() - t0) / 1e6
            val text = listOf(read.ocrText, com.amar.vault.indexing.QrText.readable(read.qrPayloads))
                .filter { it.isNotBlank() }.joinToString("\n")
            if (text == p.ocrText) same++ else differ++
            bitmap.recycle()
        }
        ms.sort()
        File(File(app.filesDir, "photo-speed").apply { mkdirs() }, "reading.json").writeText(JSONObject()
            .put("photos", photos.size).put("same_text", same).put("different_text", differ).put("unreadable", unreadable)
            .put("reading_ms_median", if (ms.isEmpty()) 0.0 else ms[ms.size / 2]).toString(1))
    }

    /** How each photo comes out of the three ways the app decodes pictures, and what its EXIF says. */
    @Test
    fun orientation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e speed orientation", args.getString("speed") == "orientation")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val photos = VaultDatabase.get(app).vaultDao().getAll()
            .filter { it.itemType == ItemType.PHOTO && it.parentDocumentId == null }
            .sortedByDescending { it.timestamp }
        val out = File(app.filesDir, "photo-speed").apply { mkdirs() }
        File(out, "orientation.jsonl").bufferedWriter().use { w ->
            for ((n, p) in photos.withIndex()) {
                val uri = Uri.parse(p.uri)
                val bf = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                val id = com.amar.vault.indexing.BitmapDecoding.loadSoftware(app, uri)
                val th = PhotoThumbnails.load(app, uri)
                val exif = runCatching {
                    app.contentResolver.openInputStream(uri)?.use {
                        android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0)
                    }
                }.getOrNull()
                w.write(JSONObject().put("n", n)
                    .put("bitmapfactory", "${bf?.width}x${bf?.height}")
                    .put("imagedecoder", "${id?.width}x${id?.height}")
                    .put("thumbnail", "${th?.width}x${th?.height}")
                    .put("exif_orientation", exif ?: -1).toString())
                w.newLine()
                bf?.recycle(); id?.recycle(); th?.recycle()
            }
        }
    }
}
