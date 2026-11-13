package com.metallic.chiaki.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.metallic.chiaki.BuildConfig

/** Snapshot of the subtitle translation settings, stored in their own SharedPreferences file. */
data class TranslateSettings(
	val engine: Engine,
	val deeplKey: String,
	val azureKey: String,
	val azureRegion: String,
	val textSizeSp: Int,
	val textColor: Int,
	val backgroundColor: Int,
	val bottomOffsetPercent: Int,
	val captureLeft: Float,
	val captureRight: Float,
	val captureTop: Float,
	val captureBottom: Float,
	val showCaptureArea: Boolean
)
{
	enum class Engine(val value: String) { ON_DEVICE("on_device"), DEEPL("deepl"), AZURE("azure") }

	companion object
	{
		const val PREFERENCES_NAME = "translate"

		const val KEY_ENGINE = "translate_engine"
		const val KEY_DEEPL_KEY = "translate_deepl_key"
		const val KEY_AZURE_KEY = "translate_azure_key"
		const val KEY_AZURE_REGION = "translate_azure_region"
		const val KEY_TEXT_SIZE = "translate_text_size"
		const val KEY_TEXT_COLOR = "translate_text_color"
		const val KEY_BACKGROUND_COLOR = "translate_background_color"
		const val KEY_BACKGROUND_OPACITY = "translate_background_opacity"
		const val KEY_BOTTOM_OFFSET = "translate_bottom_offset"
		const val KEY_CAPTURE_LEFT = "translate_capture_left"
		const val KEY_CAPTURE_RIGHT = "translate_capture_right"
		const val KEY_CAPTURE_TOP = "translate_capture_top"
		const val KEY_CAPTURE_BOTTOM = "translate_capture_bottom"
		const val KEY_SHOW_CAPTURE_AREA = "translate_show_capture_area"

		// Percent-based capture edges are kept at least this far apart.
		private const val MIN_CAPTURE_SPAN = 5

		/** Opens the preferences file, seeding the DeepL key and engine from the build on first use. */
		fun preferences(context: Context): SharedPreferences
		{
			val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			if(!preferences.contains(KEY_ENGINE))
			{
				val builtInKey = BuildConfig.DEEPL_API_KEY
				preferences.edit()
					.putString(KEY_DEEPL_KEY, builtInKey)
					.putString(KEY_ENGINE, if(builtInKey.isNotEmpty()) Engine.DEEPL.value else Engine.ON_DEVICE.value)
					.apply()
			}
			return preferences
		}

		fun load(context: Context): TranslateSettings
		{
			val p = preferences(context)
			val opacity = p.getInt(KEY_BACKGROUND_OPACITY, 80).coerceIn(0, 100)
			val background = Color.parseColor(p.getString(KEY_BACKGROUND_COLOR, "#000000"))
			var left = p.getInt(KEY_CAPTURE_LEFT, 15)
			var right = p.getInt(KEY_CAPTURE_RIGHT, 85)
			var top = p.getInt(KEY_CAPTURE_TOP, 68)
			var bottom = p.getInt(KEY_CAPTURE_BOTTOM, 98)
			if(right - left < MIN_CAPTURE_SPAN) { left = 0; right = 100 }
			if(bottom - top < MIN_CAPTURE_SPAN) { top = 68; bottom = 98 }
			return TranslateSettings(
				engine = Engine.values().firstOrNull { it.value == p.getString(KEY_ENGINE, null) } ?: Engine.ON_DEVICE,
				deeplKey = p.getString(KEY_DEEPL_KEY, "").orEmpty().trim(),
				azureKey = p.getString(KEY_AZURE_KEY, "").orEmpty().trim(),
				azureRegion = p.getString(KEY_AZURE_REGION, "").orEmpty().trim(),
				textSizeSp = p.getInt(KEY_TEXT_SIZE, 22),
				textColor = Color.parseColor(p.getString(KEY_TEXT_COLOR, "#FFFFFF")),
				backgroundColor = Color.argb(opacity * 255 / 100, Color.red(background), Color.green(background), Color.blue(background)),
				bottomOffsetPercent = p.getInt(KEY_BOTTOM_OFFSET, 3),
				captureLeft = left / 100f,
				captureRight = right / 100f,
				captureTop = top / 100f,
				captureBottom = bottom / 100f,
				showCaptureArea = p.getBoolean(KEY_SHOW_CAPTURE_AREA, false)
			)
		}
	}
}
