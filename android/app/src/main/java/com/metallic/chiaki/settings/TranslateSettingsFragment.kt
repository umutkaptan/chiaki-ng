package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.translate.TranslateSettings

class TranslateSettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return
		TranslateSettings.preferences(context) // seeds first-run defaults before the screen reads them
		preferenceManager.sharedPreferencesName = TranslateSettings.PREFERENCES_NAME
		setPreferencesFromResource(R.xml.translate_preferences, rootKey)

		// Keys stay hidden on screen; only the last characters are shown.
		val keySummary = Preference.SummaryProvider<EditTextPreference> {
			val key = it.text.orEmpty().trim()
			if(key.isEmpty()) getString(R.string.translate_pref_key_not_set) else getString(R.string.translate_pref_key_set, key.takeLast(4))
		}
		for(name in listOf(TranslateSettings.KEY_DEEPL_KEY, TranslateSettings.KEY_AZURE_KEY))
		{
			findPreference<EditTextPreference>(name)?.let {
				it.summaryProvider = keySummary
				it.setOnBindEditTextListener { editText ->
					editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
					editText.setSelection(editText.text.length)
				}
			}
		}
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.translate_pref_title)
}
