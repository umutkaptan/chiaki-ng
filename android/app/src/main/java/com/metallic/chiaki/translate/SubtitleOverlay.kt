package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max

/**
 * Always-on-top views drawn over whatever the TV shows (the PS5 HDMI input): the translated
 * line, styled and placed by [TranslateSettings], and optionally a red frame marking the
 * capture area. The font shrinks to keep the line on one row; long lines wrap to at most
 * [MAX_LINES] rows instead of being cut.
 */
class SubtitleOverlay(private val context: Context, settings: TranslateSettings)
{
	companion object
	{
		private const val MIN_TEXT_FRACTION = 0.65f // smallest size the font shrinks to, relative to the chosen size
		private const val MAX_LINES = 3
		private const val SIDE_MARGIN_DP = 24
		private const val FRAME_STROKE_DP = 3
	}

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private val mainHandler = Handler(Looper.getMainLooper())
	private val metrics = context.resources.displayMetrics
	private var settings = settings
	private var textView: TextView? = null
	private var textParams: WindowManager.LayoutParams? = null
	private var frameView: View? = null

	fun show()
	{
		mainHandler.post {
			if(textView != null)
				return@post
			val padding = (metrics.density * 6).toInt()
			val view = TextView(context).apply {
				gravity = Gravity.CENTER
				maxLines = MAX_LINES
				ellipsize = TextUtils.TruncateAt.END
				maxWidth = metrics.widthPixels - (metrics.density * SIDE_MARGIN_DP * 2).toInt()
				setPadding(padding * 3, padding, padding * 3, padding)
				visibility = View.GONE
			}
			val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
				gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			}
			windowManager.addView(view, params)
			textView = view
			textParams = params
			applySettings()
		}
	}

	fun update(newSettings: TranslateSettings)
	{
		mainHandler.post {
			settings = newSettings
			applySettings()
		}
	}

	fun setText(text: String)
	{
		mainHandler.post {
			val view = textView ?: return@post
			view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fittingTextSize(view, text))
			view.text = text
			view.visibility = if(text.isEmpty()) View.GONE else View.VISIBLE
		}
	}

	fun hide()
	{
		mainHandler.post {
			textView?.let { windowManager.removeView(it) }
			textView = null
			textParams = null
			frameView?.let { windowManager.removeView(it) }
			frameView = null
		}
	}

	private fun applySettings()
	{
		val view = textView ?: return
		val params = textParams ?: return
		view.setTextColor(settings.textColor)
		view.setBackgroundColor(settings.backgroundColor)
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fittingTextSize(view, view.text.toString()))
		params.y = metrics.heightPixels * settings.bottomOffsetPercent / 100
		windowManager.updateViewLayout(view, params)
		updateFrame()
	}

	private fun updateFrame()
	{
		if(!settings.showCaptureArea)
		{
			frameView?.let { windowManager.removeView(it) }
			frameView = null
			return
		}
		// The stream and the HDMI picture both fill the screen, so frame fractions map directly.
		val width = (metrics.widthPixels * (settings.captureRight - settings.captureLeft)).toInt()
		val height = (metrics.heightPixels * (settings.captureBottom - settings.captureTop)).toInt()
		val params = overlayParams(width, height).apply {
			gravity = Gravity.TOP or Gravity.START
			x = (metrics.widthPixels * settings.captureLeft).toInt()
			y = (metrics.heightPixels * settings.captureTop).toInt()
			flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
		}
		val existing = frameView
		if(existing != null)
		{
			windowManager.updateViewLayout(existing, params)
			return
		}
		val frame = View(context).apply {
			background = GradientDrawable().apply {
				setColor(Color.TRANSPARENT)
				setStroke((metrics.density * FRAME_STROKE_DP).toInt(), Color.RED)
			}
		}
		windowManager.addView(frame, params)
		frameView = frame
	}

	private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
		width,
		height,
		WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
		WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
		PixelFormat.TRANSLUCENT
	)

	/** Largest size between the chosen size and its MIN_TEXT_FRACTION at which the text fits on one row. */
	private fun fittingTextSize(view: TextView, text: String): Float
	{
		val available = view.maxWidth - view.paddingLeft - view.paddingRight
		val paint = TextPaint(view.paint)
		val maxSize = settings.textSizeSp.toFloat()
		val minSize = maxSize * MIN_TEXT_FRACTION
		var size = maxSize
		while(size > minSize)
		{
			paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
			if(paint.measureText(text) <= available)
				break
			size -= 1f
		}
		return max(size, minSize)
	}
}
