package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class ScreenCastService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.SCREEN_CAST_START"
        const val ACTION_STOP = "com.example.action.SCREEN_CAST_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_RECEIVER_NAME = "receiver_name"

        private const val CHANNEL_ID = "aircast_screen_mirror"
        private const val NOTIFICATION_ID = 4101

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            width: Int,
            height: Int,
            fps: Int,
            receiverName: String
        ): Intent {
            return Intent(context, ScreenCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_RECEIVER_NAME, receiverName)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenCastService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetFps = 30
    private var startTimeMs = 0L
    private var lastFrameProcessMs = 0L
    private var windowStartMs = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private val totalBytesSent = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CastEngineHolder.ensureInitialized(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val receiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME) ?: "AirPlay Display"
                startInForeground(receiverName)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                targetWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                targetFps = intent.getIntExtra(EXTRA_FPS, 30)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    stopCaptureAndSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(receiverName: String) {
        val notification = buildNotification(receiverName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        tearDownProjection()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopCaptureAndSelf()
            return
        }
        mediaProjection = projection

        val thread = HandlerThread("AirCastScreenCapture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCaptureAndSelf()
            }
        }, handler)

        val densityDpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        startTimeMs = System.currentTimeMillis()
        windowStartMs = startTimeMs
        windowFrames = 0
        windowBytes = 0L
        totalBytesSent.set(0L)

        CastEngineHolder.updateMirrorStats(
            MirrorLiveStats(
                isActive = true,
                width = targetWidth,
                height = targetHeight,
                currentFps = 0,
                bitrateMbps = 0f,
                totalBytesSent = 0L,
                elapsedSec = 0L
            )
        )

        reader.setOnImageAvailableListener({ imgReader ->
            processCapturedImage(imgReader)
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "AirCastBridgeDisplay",
            targetWidth,
            targetHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun processCapturedImage(imgReader: ImageReader) {
        val image = runCatching { imgReader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = System.currentTimeMillis()
            val minIntervalMs = (1000L / targetFps.coerceAtLeast(15)).coerceAtLeast(16L)
            if (now - lastFrameProcessMs < minIntervalMs) {
                return
            }
            lastFrameProcessMs = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val rawBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = if (bitmapWidth != image.width) {
                Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height).also {
                    rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 72, baos)
            croppedBitmap.recycle()

            val jpegBytes = baos.toByteArray()
            CastEngineHolder.mediaServer.updateMirrorFrame(jpegBytes)
            CastEngineHolder.updatePreviewFrame(jpegBytes)

            val newTotal = totalBytesSent.addAndGet(jpegBytes.size.toLong())
            windowFrames++
            windowBytes += jpegBytes.size.toLong()

            val windowElapsedMs = now - windowStartMs
            if (windowElapsedMs >= 1000L) {
                val fps = ((windowFrames * 1000f) / windowElapsedMs).toInt().coerceAtLeast(1)
                val mbps = (windowBytes * 8f) / (windowElapsedMs * 1000f)
                CastEngineHolder.updateMirrorStats(
                    MirrorLiveStats(
                        isActive = true,
                        width = targetWidth,
                        height = targetHeight,
                        currentFps = fps,
                        bitrateMbps = mbps,
                        totalBytesSent = newTotal,
                        elapsedSec = (now - startTimeMs) / 1000L
                    )
                )
                windowStartMs = now
                windowFrames = 0
                windowBytes = 0L
            }
        } catch (_: Exception) {
            // Skip malformed frame during rotation/resize
        } finally {
            image.close()
        }
    }

    private fun tearDownProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { captureThread?.quitSafely() }
        captureThread = null
        captureHandler = null
        CastEngineHolder.updateMirrorStats(MirrorLiveStats(isActive = false))
        CastEngineHolder.updatePreviewFrame(null)
    }

    private fun stopCaptureAndSelf() {
        tearDownProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tearDownProjection()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_screen),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(receiverName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Mirroring screen to $receiverName")
            .setContentText("Live AirPlay display bridge active")
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop_cast), stopIntent)
            .setOngoing(true)
            .build()
    }
}
