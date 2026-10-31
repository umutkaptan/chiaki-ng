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
 * Always-on-top text drawn over whatever the TV shows (the PS5 HDMI input),
 * placed over the game's own subtitle line.
 */
class SubtitleOverlay(private val context: Context)
{
	companion object
	{
		private const val TEXT_SIZE_SP = 26f
		private const val BOTTOM_MARGIN_FRACTION = 0.07f
	}

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private val mainHandler = Handler(Looper.getMainLooper())
	private var textView: TextView? = null

	fun show()
	{
		mainHandler.post {
			if(textView != null)
				return@post
			val padding = (context.resources.displayMetrics.density * 12).toInt()
			val view = TextView(context).apply {
				setTextColor(Color.WHITE)
				setBackgroundColor(Color.argb(210, 0, 0, 0))
				setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
				gravity = Gravity.CENTER
				setPadding(padding * 2, padding, padding * 2, padding)
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
				y = (context.resources.displayMetrics.heightPixels * BOTTOM_MARGIN_FRACTION).toInt()
			}
			windowManager.addView(view, params)
			textView = view
		}
	}

	fun setText(text: String)
	{
		mainHandler.post {
			val view = textView ?: return@post
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
}
