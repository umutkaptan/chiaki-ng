package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Always-on-top text drawn over whatever the TV shows (the PS5 HDMI input), low on the
 * screen and as wide as needed: the font shrinks until the line fits on one row, and only
 * lines too long even at the smallest size wrap onto a second row.
 */
class SubtitleOverlay(private val context: Context)
{
	companion object
	{
		private const val MAX_TEXT_SP = 22f
		private const val MIN_TEXT_SP = 14f
		private const val MAX_LINES = 2
		private const val BOTTOM_MARGIN_FRACTION = 0.03f
		private const val SIDE_MARGIN_DP = 24
	}

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private val mainHandler = Handler(Looper.getMainLooper())
	private val metrics = context.resources.displayMetrics
	private var textView: TextView? = null

	fun show()
	{
		mainHandler.post {
			if(textView != null)
				return@post
			val padding = (metrics.density * 6).toInt()
			val view = TextView(context).apply {
				setTextColor(Color.WHITE)
				setBackgroundColor(Color.argb(210, 0, 0, 0))
				gravity = Gravity.CENTER
				maxLines = MAX_LINES
				maxWidth = metrics.widthPixels - (metrics.density * SIDE_MARGIN_DP * 2).toInt()
				setPadding(padding * 3, padding, padding * 3, padding)
				visibility = View.GONE
			}
			val params = WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
				PixelFormat.TRANSLUCENT
			).apply {
				gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
				y = (metrics.heightPixels * BOTTOM_MARGIN_FRACTION).toInt()
			}
			windowManager.addView(view, params)
			textView = view
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
		}
	}

	/** Largest size between MIN and MAX at which the text fits on a single row. */
	private fun fittingTextSize(view: TextView, text: String): Float
	{
		val available = view.maxWidth - view.paddingLeft - view.paddingRight
		val paint = android.text.TextPaint(view.paint)
		var size = MAX_TEXT_SP
		while(size > MIN_TEXT_SP)
		{
			paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
			if(paint.measureText(text) <= available)
				break
			size -= 1f
		}
		return size
	}
}
