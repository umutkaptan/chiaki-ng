package com.metallic.chiaki.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*

/**
 * Opens a hidden Remote Play session (video only, no audio, no input), feeds the decoded
 * frames to [SubtitleReader] and draws the translated line over the TV picture.
 * Settings changes apply while running; the service stops itself when the session ends.
 */
class TranslateService: Service()
{
	companion object
	{
		private const val TAG = "TranslateService"
		private const val EXTRA_CONNECT_INFO = "connect_info"
		private const val CHANNEL_ID = "translate"
		private const val NOTIFICATION_ID = 1
		private const val STOP_MESSAGE_DURATION_MS = 4000L

		@Volatile
		var isRunning = false
			private set

		fun start(context: Context, connectInfo: ConnectInfo)
		{
			// 1080p keeps small subtitle fonts readable for OCR; 30 fps is plenty.
			val videoProfile = ConnectVideoProfile.preset(VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
			val info = connectInfo.copy(videoProfile = videoProfile, audioEnabled = false)
			val intent = Intent(context, TranslateService::class.java).putExtra(EXTRA_CONNECT_INFO, info)
			ContextCompat.startForegroundService(context, intent)
		}

		fun stop(context: Context)
		{
			context.stopService(Intent(context, TranslateService::class.java))
		}
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private lateinit var overlay: SubtitleOverlay
	private var reader: SubtitleReader? = null
	private var session: Session? = null
	private var imageReader: ImageReader? = null
	private var frameThread: HandlerThread? = null

	// Held in a field: SharedPreferences keeps listeners only weakly.
	private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
		val settings = TranslateSettings.load(this)
		overlay.update(settings)
		reader?.settings = settings
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		isRunning = true
		overlay = SubtitleOverlay(this, TranslateSettings.load(this))
		TranslateSettings.preferences(this).registerOnSharedPreferenceChangeListener(settingsListener)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		startInForeground()
		val connectInfo = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONNECT_INFO, ConnectInfo::class.java) }
		if(connectInfo == null || session != null)
			return START_NOT_STICKY

		overlay.show()
		overlay.setText(getString(R.string.translate_preparing))

		val settings = TranslateSettings.load(this)
		val subtitleReader = SubtitleReader(settings) { overlay.setText(it) }
		reader = subtitleReader
		val thread = HandlerThread("TranslateFrames").also { it.start() }
		frameThread = thread
		val frameHandler = Handler(thread.looper)

		// Model download and session start happen off the main thread.
		frameHandler.post {
			// The on-device model is the engine itself or the fallback for a cloud engine;
			// only the former can't work without it.
			if(!subtitleReader.prepare() && settings.engine == TranslateSettings.Engine.ON_DEVICE)
			{
				stopWithMessage(getString(R.string.translate_model_failed))
				return@post
			}
			startSession(connectInfo, frameHandler)
		}
		return START_NOT_STICKY
	}

	private fun startSession(connectInfo: ConnectInfo, frameHandler: Handler)
	{
		if(!isRunning) // stopped while the model was downloading
			return
		val profile = connectInfo.videoProfile
		val images = ImageReader.newInstance(profile.width, profile.height, ImageFormat.YUV_420_888, 3)
		images.setOnImageAvailableListener({ source ->
			val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
			try
			{
				reader?.onFrame(image)
			}
			finally
			{
				image.close()
			}
		}, frameHandler)
		imageReader = images

		try
		{
			val preferences = Preferences(this)
			val newSession = Session(connectInfo, LogManager(this).createNewFile().file.absolutePath, preferences.logVerbose)
			newSession.eventCallback = this::onSessionEvent
			newSession.start()
			newSession.setSurface(images.surface)
			session = newSession
			overlay.setText(getString(R.string.translate_connecting))
		}
		catch(e: CreateError)
		{
			Log.e(TAG, "Session creation failed", e)
			stopWithMessage(getString(R.string.translate_connection_failed, e.errorCode.toString()))
		}
	}

	private fun onSessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> overlay.setText("")
			is LoginPinRequestEvent -> stopWithMessage(getString(R.string.translate_pin_required))
			is QuitEvent -> stopWithMessage(getString(R.string.translate_connection_lost, event.reasonString ?: event.reason.toString()))
			else -> {}
		}
	}

	/** Shows why translation ended for a few seconds, then stops the service and removes the overlay. */
	private fun stopWithMessage(message: String)
	{
		overlay.setText(message)
		mainHandler.postDelayed({ stopSelf() }, STOP_MESSAGE_DURATION_MS)
	}

	private fun startInForeground()
	{
		val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.translate_channel), NotificationManager.IMPORTANCE_LOW))
		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(getString(R.string.translate_notification))
			.setOngoing(true)
			.build()
		val type = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
		ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
	}

	override fun onDestroy()
	{
		isRunning = false
		mainHandler.removeCallbacksAndMessages(null)
		TranslateSettings.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
		session?.let {
			it.setSurface(null)
			it.stop()
			it.dispose()
		}
		session = null
		imageReader?.close()
		imageReader = null
		frameThread?.quitSafely()
		frameThread = null
		reader?.close()
		reader = null
		overlay.hide()
		super.onDestroy()
	}
}
