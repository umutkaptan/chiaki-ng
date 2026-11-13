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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads English text from the capture area of decoded stream frames with ML Kit and
 * translates it to Turkish with the engine chosen in [TranslateSettings].
 *
 * A line is translated only after two consecutive reads agree, so half-read or garbled
 * OCR output never reaches the translator. If a cloud engine still fails after a retry,
 * the on-device translation is shown with [FALLBACK_MARK] in front and is not cached,
 * so the line gets the cloud translation the next time it appears.
 */
class SubtitleReader(settings: TranslateSettings, private val onSubtitle: (String) -> Unit)
{
	companion object
	{
		private const val TAG = "SubtitleReader"
		private const val SAMPLE_INTERVAL_MS = 350L
		private const val CHANGE_THRESHOLD = 2.0     // mean luma difference since the last read that is worth a new read
		private const val DIFF_STEP = 4              // pixel step for the cheap change check
		private const val SAME_TEXT_SIMILARITY = 0.8 // OCR jitter on the same line stays above this
		private const val EMPTY_READS_TO_CLEAR = 2   // a single failed read over a busy background shouldn't blank the line
		private const val CACHE_SIZE = 512
		private const val CLOUD_TIMEOUT_MS = 5000
		private const val CLOUD_RETRY_DELAY_MS = 800L
		const val FALLBACK_MARK = "≈ "
	}

	private class CloudError(val retryable: Boolean, message: String): Exception(message)

	@Volatile
	var settings: TranslateSettings = settings

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val onDeviceTranslator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build()
	)
	private val worker = Executors.newSingleThreadExecutor()
	private val busy = AtomicBoolean(false)
	private val cache = object: LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
	}

	// Set by the worker when a line was read once and needs a confirming read.
	@Volatile
	private var awaitingConfirmation = false

	// Touched only from the frame thread.
	private var lastSampleTime = 0L
	private var bandAtLastRead: IntArray? = null

	// Touched only from the worker thread.
	private var shownText = ""
	private var candidateText = ""
	private var emptyReads = 0

	/** Downloads the EN→TR on-device model if needed; blocks, so call it off the main thread. */
	fun prepare(): Boolean = try
	{
		Tasks.await(onDeviceTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
		true
	}
	catch(e: Exception)
	{
		Log.e(TAG, "On-device translation model download failed", e)
		false
	}

	/** Called on the frame thread for every decoded frame; the caller closes the image. */
	fun onFrame(image: Image)
	{
		val now = SystemClock.uptimeMillis()
		if(now - lastSampleTime < SAMPLE_INTERVAL_MS || busy.get())
			return
		lastSampleTime = now

		val current = settings
		val plane = image.planes[0] // luma
		val buffer = plane.buffer
		val rowStride = plane.rowStride
		val pixelStride = plane.pixelStride
		val left = (image.width * current.captureLeft).toInt()
		val right = (image.width * current.captureRight).toInt()
		val top = (image.height * current.captureTop).toInt()
		val bottom = (image.height * current.captureBottom).toInt()

		val band = IntArray(((bottom - top) / DIFF_STEP) * ((right - left) / DIFF_STEP))
		var i = 0
		for(y in 0 until (bottom - top) / DIFF_STEP)
			for(x in 0 until (right - left) / DIFF_STEP)
				band[i++] = buffer.get((top + y * DIFF_STEP) * rowStride + (left + x * DIFF_STEP) * pixelStride).toInt() and 0xFF

		// Read when the area changed since the last read, or when a line waits for its confirming read.
		val previous = bandAtLastRead
		val changed = previous == null || previous.size != band.size || meanDifference(previous, band) > CHANGE_THRESHOLD
		if(!changed && !awaitingConfirmation)
			return
		bandAtLastRead = band

		busy.set(true)
		val bitmap = lumaToBitmap(buffer, rowStride, pixelStride, left, right, top, bottom)
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
		onDeviceTranslator.close()
	}

	private fun process(bitmap: Bitmap)
	{
		val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
		val text = joinLines(result.textBlocks.flatMap { it.lines })
		awaitingConfirmation = false

		if(text.isEmpty())
		{
			candidateText = ""
			if(shownText.isNotEmpty() && ++emptyReads >= EMPTY_READS_TO_CLEAR)
			{
				shownText = ""
				onSubtitle("")
			}
			return
		}
		emptyReads = 0

		if(similarity(text, shownText) >= SAME_TEXT_SIMILARITY)
		{
			candidateText = ""
			return
		}
		if(candidateText.isEmpty() || similarity(text, candidateText) < SAME_TEXT_SIMILARITY)
		{
			candidateText = text
			awaitingConfirmation = true
			return
		}

		// Two reads agree: translate the latest one.
		val context = shownText
		shownText = text
		candidateText = ""
		translate(text, context)?.let(onSubtitle)
	}

	private fun translate(text: String, context: String): String?
	{
		val current = settings
		val cacheKey = "${current.engine.value}|$text"
		cache[cacheKey]?.let { return it }

		val cloud: (() -> String)? = when(current.engine)
		{
			TranslateSettings.Engine.DEEPL -> ({ translateWithDeepL(current.deeplKey, text, context) })
			TranslateSettings.Engine.AZURE -> ({ translateWithAzure(current.azureKey, current.azureRegion, text) })
			TranslateSettings.Engine.ON_DEVICE -> null
		}

		if(cloud != null)
		{
			try
			{
				return withRetry(cloud).also { cache[cacheKey] = it }
			}
			catch(e: Exception)
			{
				Log.e(TAG, "${current.engine.value} failed, showing on-device translation", e)
			}
		}

		return try
		{
			val translated = Tasks.await(onDeviceTranslator.translate(text))
			if(cloud == null) translated.also { cache[cacheKey] = it } else FALLBACK_MARK + translated
		}
		catch(e: Exception)
		{
			Log.e(TAG, "On-device translation failed", e)
			null
		}
	}

	private fun withRetry(call: () -> String): String = try
	{
		call()
	}
	catch(e: CloudError)
	{
		if(!e.retryable)
			throw e
		Thread.sleep(CLOUD_RETRY_DELAY_MS)
		call()
	}
	catch(e: IOException)
	{
		Thread.sleep(CLOUD_RETRY_DELAY_MS)
		call()
	}

	private fun translateWithDeepL(key: String, text: String, context: String): String
	{
		if(key.isEmpty())
			throw CloudError(false, "DeepL key is not set")
		val url = if(key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		val body = JSONObject()
			.put("text", JSONArray().put(text))
			.put("source_lang", "EN")
			.put("target_lang", "TR")
		if(context.isNotEmpty())
			body.put("context", context) // previous line; helps names and tone, not billed
		val response = post(url, body.toString(), mapOf("Authorization" to "DeepL-Auth-Key $key"))
		return JSONObject(response).getJSONArray("translations").getJSONObject(0).getString("text")
	}

	private fun translateWithAzure(key: String, region: String, text: String): String
	{
		if(key.isEmpty())
			throw CloudError(false, "Azure key is not set")
		val headers = mutableMapOf("Ocp-Apim-Subscription-Key" to key)
		if(region.isNotEmpty())
			headers["Ocp-Apim-Subscription-Region"] = region
		val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=en&to=tr"
		val body = JSONArray().put(JSONObject().put("Text", text)).toString()
		val response = post(url, body, headers)
		return JSONArray(response).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
	}

	private fun post(url: String, body: String, headers: Map<String, String>): String
	{
		val connection = URL(url).openConnection() as HttpURLConnection
		try
		{
			connection.requestMethod = "POST"
			connection.connectTimeout = CLOUD_TIMEOUT_MS
			connection.readTimeout = CLOUD_TIMEOUT_MS
			connection.doOutput = true
			headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
			connection.setRequestProperty("Content-Type", "application/json")
			connection.outputStream.use { it.write(body.toByteArray()) }
			val code = connection.responseCode
			if(code != HttpURLConnection.HTTP_OK)
			{
				// 429 (too many requests) and 5xx are temporary; 401/403/456 mean a bad key or used-up quota.
				throw CloudError(code == 429 || code >= 500, "HTTP $code")
			}
			return connection.inputStream.bufferedReader().use { it.readText() }
		}
		finally
		{
			connection.disconnect()
		}
	}

	private fun joinLines(lines: List<Text.Line>): String
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

	private fun lumaToBitmap(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, right: Int, top: Int, bottom: Int): Bitmap
	{
		val width = right - left
		val height = bottom - top
		val pixels = IntArray(width * height)
		for(y in 0 until height)
		{
			val rowStart = (top + y) * rowStride
			for(x in 0 until width)
			{
				val luma = buffer.get(rowStart + (left + x) * pixelStride).toInt() and 0xFF
				pixels[y * width + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
			}
		}
		return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
	}
}
