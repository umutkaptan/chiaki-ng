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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Watches the subtitle band of decoded stream frames, reads English text with ML Kit
 * once the band stops changing, translates it to Turkish on-device and reports the result.
 */
class SubtitleReader(private val onSubtitle: (String) -> Unit)
{
	companion object
	{
		private const val TAG = "SubtitleReader"
		private const val BAND_TOP = 0.70f       // subtitle band, as fractions of frame height
		private const val BAND_BOTTOM = 0.97f
		private const val SAMPLE_INTERVAL_MS = 150L
		private const val CHANGE_THRESHOLD = 4.0 // mean luma difference that counts as a change
		private const val STABLE_SAMPLES = 2     // samples the band must stay still before OCR
		private const val DIFF_STEP = 4          // pixel step for the cheap change check
		private const val CACHE_SIZE = 512
	}

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
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

	// Touched only from the frame thread.
	private var lastSampleTime = 0L
	private var lastBand: IntArray? = null
	private var stableSamples = 0
	private var processed = true

	// Touched only from the worker thread.
	private var lastText = ""

	/** Downloads the EN→TR model if needed; blocks, so call it off the main thread. */
	fun prepare()
	{
		Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
	}

	/** Called on the frame thread for every decoded frame; the caller closes the image. */
	fun onFrame(image: Image)
	{
		val now = SystemClock.uptimeMillis()
		if(now - lastSampleTime < SAMPLE_INTERVAL_MS)
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

		val previous = lastBand
		if(previous == null || previous.size != band.size || meanDifference(previous, band) > CHANGE_THRESHOLD)
		{
			stableSamples = 0
			processed = false
		}
		else
			stableSamples++
		lastBand = band

		if(processed || stableSamples < STABLE_SAMPLES || !busy.compareAndSet(false, true))
			return
		processed = true

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
		if(text == lastText)
			return
		lastText = text
		if(text.isEmpty())
		{
			onSubtitle("")
			return
		}
		val translated = cache[text] ?: Tasks.await(translator.translate(text)).also { cache[text] = it }
		onSubtitle(translated)
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
