package com.metallic.chiaki.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
 */
class TranslateService: Service()
{
	companion object
	{
		private const val TAG = "TranslateService"
		private const val EXTRA_CONNECT_INFO = "connect_info"
		private const val CHANNEL_ID = "translate"
		private const val NOTIFICATION_ID = 1

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

	private lateinit var overlay: SubtitleOverlay
	private var reader: SubtitleReader? = null
	private var session: Session? = null
	private var imageReader: ImageReader? = null
	private var frameThread: HandlerThread? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		isRunning = true
		overlay = SubtitleOverlay(this)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		startInForeground()
		val connectInfo = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONNECT_INFO, ConnectInfo::class.java) }
		if(connectInfo == null || session != null)
			return START_NOT_STICKY

		overlay.show()
		overlay.setText(getString(R.string.translate_preparing))

		val subtitleReader = SubtitleReader { overlay.setText(it) }
		reader = subtitleReader
		val thread = HandlerThread("TranslateFrames").also { it.start() }
		frameThread = thread
		val frameHandler = Handler(thread.looper)

		// Model download and session start happen off the main thread.
		frameHandler.post {
			try
			{
				subtitleReader.prepare()
			}
			catch(e: Exception)
			{
				Log.e(TAG, "Translation model download failed", e)
				overlay.setText(getString(R.string.translate_model_failed))
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
			overlay.setText(getString(R.string.translate_connection_failed, e.errorCode.toString()))
		}
	}

	private fun onSessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> overlay.setText("")
			is LoginPinRequestEvent -> overlay.setText(getString(R.string.translate_pin_required))
			is QuitEvent -> overlay.setText(getString(R.string.translate_connection_lost, event.reasonString ?: event.reason.toString()))
			else -> {}
		}
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
