package com.metallic.chiaki.translate

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads English text from the subtitle band of decoded stream frames with ML Kit and
 * translates it to Turkish: DeepL when a key was built in, ML Kit on-device otherwise
 * (and as a fallback when DeepL fails).
 */
class SubtitleReader(private val onSubtitle: (String) -> Unit)
{
	companion object
	{
		private const val TAG = "SubtitleReader"
		private const val BAND_TOP = 0.70f       // subtitle band, as fractions of frame height
		private const val BAND_BOTTOM = 0.97f
		private const val SAMPLE_INTERVAL_MS = 400L
		private const val CHANGE_THRESHOLD = 2.0 // mean luma difference since the last OCR that is worth a new read
		private const val DIFF_STEP = 4          // pixel step for the cheap change check
		private const val SAME_TEXT_SIMILARITY = 0.8 // OCR jitter on the same line stays above this
		private const val EMPTY_READS_TO_CLEAR = 2   // a single failed read over a busy background shouldn't blank the line
		private const val CACHE_SIZE = 512
		private const val DEEPL_TIMEOUT_MS = 4000
	}

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build()
	)
	private val deeplKey = BuildConfig.DEEPL_API_KEY
	private val deeplUrl = if(deeplKey.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
	private val worker = Executors.newSingleThreadExecutor()
	private val busy = AtomicBoolean(false)
	private val cache = object: LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
	}

	// Touched only from the frame thread.
	private var lastSampleTime = 0L
	private var bandAtLastRead: IntArray? = null

	// Touched only from the worker thread.
	private var lastText = ""
	private var emptyReads = 0

	/** Downloads the EN→TR fallback model if needed; blocks, so call it off the main thread. */
	fun prepare()
	{
		Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
	}

	/** Called on the frame thread for every decoded frame; the caller closes the image. */
	fun onFrame(image: Image)
	{
		val now = SystemClock.uptimeMillis()
		if(now - lastSampleTime < SAMPLE_INTERVAL_MS || busy.get())
			return
		lastSampleTime = now

		val plane = image.planes[0] // luma
		val buffer = plane.buffer
		val rowStride = plane.rowStride
		val pixelStride = plane.pixelStride
		val width = image.width
		val top = (image.height * BAND_TOP).toInt()
		val bottom = (image.height * BAND_BOTTOM).toInt()

		val band = IntArray(((bottom - top) / DIFF_STEP) * (width / DIFF_STEP))
		var i = 0
		for(y in 0 until (bottom - top) / DIFF_STEP)
			for(x in 0 until width / DIFF_STEP)
				band[i++] = buffer.get((top + y * DIFF_STEP) * rowStride + x * DIFF_STEP * pixelStride).toInt() and 0xFF

		// Read again whenever the band changed since the last read; no waiting for a still
		// picture, since cutscenes keep moving behind the subtitle.
		val previous = bandAtLastRead
		if(previous != null && previous.size == band.size && meanDifference(previous, band) <= CHANGE_THRESHOLD)
			return
		bandAtLastRead = band

		busy.set(true)
		val bitmap = lumaToBitmap(buffer, rowStride, pixelStride, width, top, bottom)
		worker.execute {
			try
			{
				process(bitmap)
			}
			catch(e: Exception)
			{
				Log.e(TAG, "Subtitle processing failed", e)
			}
			finally
			{
				busy.set(false)
			}
		}
	}

	fun close()
	{
		worker.shutdownNow()
		recognizer.close()
		translator.close()
	}

	private fun process(bitmap: Bitmap)
	{
		val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
		val text = joinLines(result.textBlocks.flatMap { it.lines })
		if(text.isEmpty())
		{
			if(lastText.isNotEmpty() && ++emptyReads >= EMPTY_READS_TO_CLEAR)
			{
				lastText = ""
				onSubtitle("")
			}
			return
		}
		emptyReads = 0
		if(similarity(text, lastText) >= SAME_TEXT_SIMILARITY)
			return
		val context = lastText
		lastText = text
		onSubtitle(cache[text] ?: translate(text, context).also { cache[text] = it })
	}

	private fun translate(text: String, context: String): String
	{
		if(deeplKey.isNotEmpty())
		{
			try
			{
				return translateWithDeepL(text, context)
			}
			catch(e: Exception)
			{
				Log.e(TAG, "DeepL failed, using on-device translation", e)
			}
		}
		return Tasks.await(translator.translate(text))
	}

	private fun translateWithDeepL(text: String, context: String): String
	{
		val body = JSONObject()
			.put("text", JSONArray().put(text))
			.put("source_lang", "EN")
			.put("target_lang", "TR")
		if(context.isNotEmpty())
			body.put("context", context) // previous line; helps names and tone, not billed
		val connection = URL(deeplUrl).openConnection() as HttpURLConnection
		try
		{
			connection.requestMethod = "POST"
			connection.connectTimeout = DEEPL_TIMEOUT_MS
			connection.readTimeout = DEEPL_TIMEOUT_MS
			connection.doOutput = true
			connection.setRequestProperty("Authorization", "DeepL-Auth-Key $deeplKey")
			connection.setRequestProperty("Content-Type", "application/json")
			connection.outputStream.use { it.write(body.toString().toByteArray()) }
			if(connection.responseCode != HttpURLConnection.HTTP_OK)
				throw IllegalStateException("DeepL HTTP ${connection.responseCode}")
			val response = connection.inputStream.bufferedReader().use { it.readText() }
			return JSONObject(response).getJSONArray("translations").getJSONObject(0).getString("text")
		}
		finally
		{
			connection.disconnect()
		}
	}

	private fun joinLines(lines: List<com.google.mlkit.vision.text.Text.Line>): String
	{
		// Group boxes into rows by vertical center, then read each row left-to-right.
		val boxes = lines.mapNotNull { line -> line.boundingBox?.let { Triple(it, line.text, it.exactCenterY()) } }
			.sortedBy { it.third }
		val rows = mutableListOf<MutableList<Pair<Int, String>>>()
		var rowCenter = 0f
		for((box, text, center) in boxes)
		{
			if(rows.isNotEmpty() && abs(center - rowCenter) < box.height() / 2f)
				rows.last().add(box.left to text)
			else
			{
				rows.add(mutableListOf(box.left to text))
				rowCenter = center
			}
		}
		val text = rows.joinToString(" ") { row -> row.sortedBy { it.first }.joinToString(" ") { it.second } }
			.replace(Regex("\\s+"), " ").trim()
		return if(text.count { it.isLetter() } >= 2) text else ""
	}

	/** 1 - normalized Levenshtein distance; 1.0 for identical strings. */
	private fun similarity(a: String, b: String): Double
	{
		if(a == b)
			return 1.0
		if(a.isEmpty() || b.isEmpty())
			return 0.0
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for(i in 1..a.length)
		{
			cur[0] = i
			for(j in 1..b.length)
			{
				val cost = if(a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
			}
			val swap = prev
			prev = cur
			cur = swap
		}
		return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
	}

	private fun meanDifference(a: IntArray, b: IntArray): Double
	{
		var sum = 0L
		for(i in a.indices)
			sum += abs(a[i] - b[i])
		return sum.toDouble() / a.size
	}

	private fun lumaToBitmap(buffer: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, top: Int, bottom: Int): Bitmap
	{
		val height = bottom - top
		val pixels = IntArray(width * height)
		for(y in 0 until height)
		{
			val rowStart = (top + y) * rowStride
			for(x in 0 until width)
			{
				val luma = buffer.get(rowStart + x * pixelStride).toInt() and 0xFF
				pixels[y * width + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
			}
		}
		return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
	}
}
