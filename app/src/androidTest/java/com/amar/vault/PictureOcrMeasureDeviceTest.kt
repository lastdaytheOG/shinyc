package com.amar.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.OcrStrategyRecorder
import com.amar.vault.indexing.PlacedText
import com.amar.vault.indexing.ReadingMerge
import com.amar.vault.indexing.ReadingOrder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reads the text off real pictures with the app's own picture path and writes down what came
 * out — the stored text, and what each pass of the reader contributed and how long it took —
 * so that it can be scored against what the pictures really say.
 *
 * It asserts nothing about the text. It is run on request only:
 *
 *     adb shell am instrument -w -e class com.amar.vault.PictureOcrMeasureDeviceTest \
 *         -e ocr measure com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The pictures are read from the app's files, `ocr-measure/` (put there with `run-as`), or from
 * the same folder in its external files (where adb can reach them on a build that cannot be
 * `run-as`), and the result is written beside them as `result.jsonl`, one line per picture. Score
 * it with `tools/vault-check/ocr_score.py`.
 *
 * `-e ocr reads [-e only EN_RAW,HI_RAW]` reads each picture one read at a time, nothing else running,
 * and writes each read's text and time to `reads.jsonl`: what each of them costs on its own and what
 * each of them finds.
 *
 * `-e ocr draw [-e screens 40]` first draws screens made to be hard to read into that folder, each
 * with the text it says beside it (`N.png`, `N.txt`). Android draws them, as it draws what a real
 * screenshot shows, so that Hindi comes out as it does on a phone. All their words are invented.
 */
@RunWith(AndroidJUnit4::class)
class PictureOcrMeasureDeviceTest {

    private class Style(val background: Int?, val ink: Int, val size: Float, val face: android.graphics.Typeface = android.graphics.Typeface.DEFAULT)

    /** Plain lines, and the kinds a reader has trouble with: faint, coloured, small, over a busy picture. */
    private val styles = listOf(
        Style(0xFFFFFFFF.toInt(), 0xFF141414.toInt(), 44f),
        Style(0xFFFFFFFF.toInt(), 0xFF828282.toInt(), 44f),
        Style(0xFFFFFFFF.toInt(), 0xFFB9B9B9.toInt(), 44f),
        Style(0xFFFFFFFF.toInt(), 0xFFD7D7D7.toInt(), 44f),
        Style(0xFF121214.toInt(), 0xFFEBEBF0.toInt(), 44f),
        Style(0xFF121214.toInt(), 0xFF78787D.toInt(), 44f),
        Style(0xFF1E6E64.toInt(), 0xFFFFFFFF.toInt(), 44f),
        Style(0xFFC82828.toInt(), 0xFFFFFFFF.toInt(), 44f),
        Style(0xFFFFFFFF.toInt(), 0xFFE6C828.toInt(), 44f),
        Style(0xFF2846A0.toInt(), 0xFF7896F0.toInt(), 44f),
        Style(0xFF289646.toInt(), 0xFFC83C3C.toInt(), 44f),
        Style(0xFFFFFFFF.toInt(), 0xFF3C3C3C.toInt(), 22f),
        Style(0xFFFFFFFF.toInt(), 0xFF828282.toInt(), 24f),
        Style(0xFF18181C.toInt(), 0xFFC8C8CD.toInt(), 24f),
        Style(0xFFFAFAFA.toInt(), 0xFF0A0A0A.toInt(), 72f, android.graphics.Typeface.DEFAULT_BOLD),
        Style(0xFFFFFFFF.toInt(), 0xFF202020.toInt(), 40f, android.graphics.Typeface.SERIF),
        Style(0xFFF0F0F0.toInt(), 0xFF202020.toInt(), 36f, android.graphics.Typeface.MONOSPACE),
        Style(null, 0xFFFFFFFF.toInt(), 52f),
        Style(null, 0xFF0F0F0F.toInt(), 52f),
    )

    private val english = (
        "invoice payment received order total amount account balance meeting tomorrow send report schedule project " +
            "review update status office travel ticket booking train hotel address phone email reply thanks message group " +
            "photo shared link document policy date time customer service refund delivery tracking package courier " +
            "electricity water bill rent salary bank statement transfer credit debit card expiry insurance renewal premium " +
            "doctor appointment prescription medicine result sample test laboratory school college exam marks admission fee " +
            "library notice holiday festival family friend birthday wedding market garden window river mountain village"
        ).split(' ')

    private val hindi = (
        "नमस्ते धन्यवाद बैठक कल भुगतान खाता राशि पता फोन संदेश तस्वीर दस्तावेज़ बिल बैंक तारीख समय सेवा " +
            "परीक्षा विद्यालय पुस्तक शहर गाँव नदी पहाड़ बाज़ार परिवार मित्र त्योहार रसीद टिकट यात्रा"
        ).split(' ')

    /**
     * `-e ocr pages [-e out name]`: each picture read the way a scanned PDF's page is, by the real
     * path, into `<name>.jsonl`, with the two reads its first step is made of, for a merge to be
     * tried on again without a device. `escalated` says the page went on to the full set of reads.
     */
    @Test
    fun pages() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e ocr pages", args.getString("ocr") == "pages")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").takeIf { it.isDirectory }
            ?: File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())
        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val reader = ImageContentExtractor(app)
        fun escalations() = com.amar.vault.IndexMetrics.snapshot()[com.amar.vault.IndexMetrics.Event.PDF_OCR_ESCALATED] ?: 0L
        File(folder, (args.getString("out") ?: "pages") + ".jsonl").bufferedWriter().use { out ->
            for (picture in pictures) {
                val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                val before = escalations()
                val started = System.nanoTime()
                val text = reader.extractPdfPageText(bitmap, sourceId = picture.name)
                val tookMs = (System.nanoTime() - started) / 1_000_000
                val escalated = escalations() > before
                val image = InputImage.fromBitmap(bitmap, 0)
                val reads = JSONArray()
                for ((name, client) in listOf("EN_RAW" to en, "HI_RAW" to hi)) {
                    val found = client.process(image).await()
                    val blocks = found.textBlocks.map { block ->
                        val box = block.boundingBox!!
                        val lineHeight = block.lines.firstOrNull()?.boundingBox?.height() ?: (box.height() / block.lines.size.coerceAtLeast(1))
                        PlacedText(block.text, box.left, box.top, lineHeight.coerceAtLeast(1))
                    }
                    reads.put(JSONObject().put("read", name).put("text", found.text.trim()).put("ordered", ReadingOrder.text(blocks).trim()))
                }
                val mergeStarted = System.nanoTime()
                ReadingMerge.text(listOf(reads.getJSONObject(0).getString("text"), reads.getJSONObject(1).getString("text")))
                val mergeMs = (System.nanoTime() - mergeStarted) / 1e6
                out.write(JSONObject().put("name", picture.name).put("text", text).put("ms", tookMs).put("mergeMs", mergeMs)
                    .put("escalated", escalated).put("reads", reads).toString())
                out.newLine()
                out.flush()
                bitmap.recycle()
            }
        }
    }

    @Test
    fun reads() = runBlocking {
        assumeTrue("run with -e ocr reads", InstrumentationRegistry.getArguments().getString("ocr") == "reads")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").takeIf { it.isDirectory }
            ?: File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())
        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val reader = ImageContentExtractor(app)
        var lastLines = JSONArray()
        suspend fun read(client: com.google.mlkit.vision.text.TextRecognizer, b: Bitmap): Pair<String, Double> {
            val t0 = System.nanoTime()
            val found = client.process(InputImage.fromBitmap(b, 0)).await()
            val done = (System.nanoTime() - t0) / 1e6
            lastLines = JSONArray()
            for (block in found.textBlocks) for (line in block.lines) {
                lastLines.put(JSONObject().put("text", line.text).put("confidence", line.confidence.toDouble())
                    .put("lang", line.recognizedLanguage))
            }
            val blocks = found.textBlocks.map { block ->
                val box = block.boundingBox ?: return found.text to done
                val lineHeight = block.lines.firstOrNull()?.boundingBox?.height() ?: (box.height() / block.lines.size.coerceAtLeast(1))
                PlacedText(block.text, box.left, box.top, lineHeight.coerceAtLeast(1))
            }
            return ReadingOrder.text(blocks).trim() to done
        }
        // The first read of each model loads it: not what a read costs.
        BitmapFactory.decodeFile(pictures.first().path)?.let { read(en, it); read(hi, it); it.recycle() }
        val only = InstrumentationRegistry.getArguments().getString("only")?.split(',')?.toSet()
        File(folder, "reads.jsonl").bufferedWriter().use { out ->
            for (picture in pictures) {
                val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                val gray = if (only == null) reader.toHighContrastGrayscale(bitmap) else bitmap.copy(bitmap.config!!, false)
                val inverted = if (only == null) reader.invertBitmap(bitmap) else bitmap.copy(bitmap.config!!, false)
                val bigGray = if (only == null) {
                    val big = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
                    reader.toHighContrastGrayscale(big).also { big.recycle() }
                } else bitmap.copy(bitmap.config!!, false)
                val reads = JSONArray()
                for ((name, client, b) in listOf(
                    Triple("EN_RAW", en, bitmap), Triple("HI_RAW", hi, bitmap),
                    Triple("EN_GRAY", en, gray), Triple("HI_GRAY", hi, gray),
                    Triple("EN_INVERT", en, inverted), Triple("EN_UPSCALE", en, bigGray),
                )) {
                    if (only != null && name !in only) continue
                    val (text, ms) = read(client, b)
                    reads.put(JSONObject().put("read", name).put("text", text).put("ms", ms).put("lines", lastLines))
                }
                out.write(JSONObject().put("name", picture.name).put("width", bitmap.width).put("height", bitmap.height).put("reads", reads).toString())
                out.newLine()
                out.flush()
                bitmap.recycle(); gray.recycle(); inverted.recycle(); bigGray.recycle()
            }
        }
    }

    @Test
    fun ab() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e ocr ab", args.getString("ocr") == "ab")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").takeIf { it.isDirectory }
            ?: File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())
        val reader = ImageContentExtractor(app)
        val before = com.amar.vault.indexing.AdaptiveImageOcrControl.tier1Enabled
        BitmapFactory.decodeFile(pictures.first().path)?.let { reader.extract(it); it.recycle() }
        try {
            File(folder, (args.getString("out") ?: "ab") + ".jsonl").bufferedWriter().use { out ->
                for ((n, picture) in pictures.withIndex()) {
                    val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                    val result = JSONObject().put("name", picture.name)
                    val order = if (n % 2 == 0) listOf(true, false) else listOf(false, true)
                    for (gate in order) {
                        com.amar.vault.indexing.AdaptiveImageOcrControl.tier1Enabled = gate
                        val accepted = com.amar.vault.IndexMetrics.snapshot()[com.amar.vault.IndexMetrics.Event.IMAGE_OCR_TIER1_ACCEPTED] ?: 0L
                        val started = System.nanoTime()
                        val text = reader.extract(bitmap).ocrText
                        val ms = (System.nanoTime() - started) / 1e6
                        val key = if (gate) "gate" else "full"
                        result.put("${key}Text", text).put("${key}Ms", ms)
                        if (gate) result.put("accepted", (com.amar.vault.IndexMetrics.snapshot()[com.amar.vault.IndexMetrics.Event.IMAGE_OCR_TIER1_ACCEPTED] ?: 0L) > accepted)
                    }
                    out.write(result.toString())
                    out.newLine()
                    out.flush()
                    bitmap.recycle()
                }
            }
        } finally {
            com.amar.vault.indexing.AdaptiveImageOcrControl.tier1Enabled = before
        }
    }

    @Test
    fun overlap() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e ocr overlap", args.getString("ocr") == "overlap")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").takeIf { it.isDirectory }
            ?: File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty().take(args.getString("count")?.toInt() ?: 60)
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())
        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        BitmapFactory.decodeFile(pictures.first().path)?.let {
            val image = InputImage.fromBitmap(it, 0)
            en.process(image).await(); hi.process(image).await(); it.recycle()
        }
        File(folder, "overlap.jsonl").bufferedWriter().use { out ->
            for ((n, picture) in pictures.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                val image = InputImage.fromBitmap(bitmap, 0)
                suspend fun oneThenOther(): Double {
                    val t0 = System.nanoTime()
                    en.process(image).await()
                    hi.process(image).await()
                    return (System.nanoTime() - t0) / 1e6
                }
                suspend fun both(): Double = kotlinx.coroutines.coroutineScope {
                    val t0 = System.nanoTime()
                    val a = async { en.process(image).await() }
                    val b = async { hi.process(image).await() }
                    a.await(); b.await()
                    (System.nanoTime() - t0) / 1e6
                }
                val (apart, together) = if (n % 2 == 0) {
                    val a = oneThenOther(); a to both()
                } else {
                    val t = both(); oneThenOther() to t
                }
                out.write(JSONObject().put("name", picture.name).put("apartMs", apart).put("togetherMs", together).toString())
                out.newLine()
                out.flush()
                bitmap.recycle()
            }
        }
    }

    private fun numberWords(r: kotlin.random.Random): List<String> {
        fun digits(n: Int) = (1..n).joinToString("") { r.nextInt(10).toString() }
        return when (r.nextInt(8)) {
            0 -> listOf("Rs", "${r.nextInt(1, 99)},${digits(3)}.${digits(2)}")
            1 -> listOf("₹${r.nextInt(1, 9999)}")
            2 -> listOf("${r.nextInt(1, 28)}/${r.nextInt(1, 12)}/20${r.nextInt(10, 30)}")
            3 -> listOf("${r.nextInt(1, 12)}:${r.nextInt(10, 59)}", if (r.nextBoolean()) "AM" else "PM")
            4 -> listOf("+91", digits(5), digits(5))
            5 -> listOf("ref", digits(8))
            6 -> listOf("${r.nextInt(1, 100)}%", "of", digits(3))
            else -> listOf("A/c", "XX${digits(4)}", "Bal", digits(5))
        }
    }

    @Test
    fun draw() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e ocr draw", args.getString("ocr") == "draw")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").apply { mkdirs() }
        val screens = args.getString("screens")?.toInt() ?: 40
        val numbers = args.getString("numbers")?.toFloat() ?: 0f
        // Screens with no Hindi on them, and screens with one short line of it among English.
        val noHindi = args.getString("noHindi")?.toInt() ?: 0
        val oneHindi = args.getString("oneHindi")?.toInt() ?: 0
        for (n in 1..(screens + noHindi + oneHindi)) {
            val kind = when {
                n <= screens -> ""
                n <= screens + noHindi -> "e"
                else -> "h"
            }
            val r = kotlin.random.Random((args.getString("seed")?.toInt() ?: 7000) + n)
            var hindiLeft = if (kind == "h") 1 else 0
            val hindiAtLine = r.nextInt(0, 12)
            val bitmap = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(0xFFF5F5F5.toInt())
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val lines = ArrayList<String>()
            val order = styles.shuffled(r)
            var y = 30f
            var i = 0
            while (y < 2250f) {
                val style = order[i++ % order.size]
                val useHindi = when (kind) {
                    "" -> r.nextFloat() < 0.25f
                    "e" -> false
                    else -> (i - 1 >= hindiAtLine && hindiLeft > 0).also { if (it) hindiLeft-- }
                }
                val words = (if (useHindi) hindi else english).let { from -> MutableList(r.nextInt(3, 7)) { from[r.nextInt(from.size)] } }
                // -e numbers 0.4: that share of lines carries what a real screen is full of, and what
                // a reader of another script most easily gets wrong: amounts, dates, times, ids.
                if (r.nextFloat() < numbers) {
                    if (r.nextBoolean()) words.clear()
                    words += numberWords(r)
                }
                paint.textSize = style.size
                paint.typeface = style.face
                while (words.size > 1 && paint.measureText(words.joinToString(" ")) > 1000f) words.removeAt(words.size - 1)
                val line = words.joinToString(" ")
                val band = style.size * 1.3f + 60f
                if (style.background != null) {
                    paint.color = style.background
                    canvas.drawRect(0f, y, 1080f, y + band, paint)
                } else {
                    // Something busy to read against: patches of colour, as a photo has.
                    canvas.save()
                    canvas.clipRect(0f, y, 1080f, y + band)
                    paint.color = 0xFF000000.toInt() or r.nextInt(0xFFFFFF)
                    canvas.drawRect(0f, y, 1080f, y + band, paint)
                    repeat(40) {
                        paint.color = 0xFF000000.toInt() or r.nextInt(0xFFFFFF)
                        canvas.drawCircle(r.nextFloat() * 1080f, y + r.nextFloat() * band, 10f + r.nextFloat() * 60f, paint)
                    }
                    canvas.restore()
                }
                paint.color = style.ink
                canvas.drawText(line, 40f, y + 30f + style.size, paint)
                lines += line
                y += band + 8f
            }
            File(folder, "$kind$n.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            File(folder, "$kind$n.txt").writeText(lines.joinToString("\n", postfix = "\n"))
            bitmap.recycle()
        }
    }

    @Test
    fun measure() = runBlocking {
        assumeTrue("run with -e ocr measure", InstrumentationRegistry.getArguments().getString("ocr") == "measure")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.getExternalFilesDir(null) ?: app.filesDir, "ocr-measure").takeIf { it.isDirectory }
            ?: File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())

        val reader = ImageContentExtractor(app)
        File(folder, "result.jsonl").bufferedWriter().use { out ->
            for (picture in pictures) {
                val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                val recorder = OcrStrategyRecorder()
                val started = System.nanoTime()
                val (content, _) = reader.extractInstrumented(bitmap, picture.name, recorder)
                val tookMs = (System.nanoTime() - started) / 1_000_000
                out.write(JSONObject().apply {
                    put("name", picture.name)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("text", content.ocrText)
                    put("ms", tookMs)
                    put("passes", JSONArray(recorder.runsSnapshot().map { run ->
                        JSONObject().put("pass", run.strategy.name).put("text", run.text).put("ms", run.elapsedMs)
                    }))
                }.toString())
                out.newLine()
                bitmap.recycle()
            }
        }
    }
}
