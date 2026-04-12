package com.nativephp.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Full-screen camera activity built on the Camera2 API (no external deps).
 * Shows a live preview with a configurable circle or box overlay in the
 * centre to guide colour extraction.
 *
 * Launched by [CameraCoordinator] when `regionIndicator = true`.
 * Returns the captured JPEG path via [RESULT_PHOTO_PATH].
 */
class CameraOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraOverlayActivity"

        const val EXTRA_REGION_SHAPE = "region_shape"
        const val EXTRA_REGION_SIZE  = "region_size"
        const val RESULT_PHOTO_PATH  = "photo_path"

        fun createIntent(context: Context, shape: String, size: Int): Intent =
            Intent(context, CameraOverlayActivity::class.java).apply {
                putExtra(EXTRA_REGION_SHAPE, shape)
                putExtra(EXTRA_REGION_SIZE, size)
            }
    }

    // ── Camera2 state ─────────────────────────────────────────────────────────
    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String = ""
    private var sensorOrientation: Int = 90

    private val bgThread = HandlerThread("CameraOverlayBg").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)

    // ── Region params ─────────────────────────────────────────────────────────
    private var shape: String = "circle"
    private var sizePercent: Int = 25

    // ── TextureView listener — opens camera once surface is ready ─────────────
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    // ── Camera state callback ─────────────────────────────────────────────────
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "❌ Camera error $error")
            camera.close(); cameraDevice = null
            setResult(Activity.RESULT_CANCELED); finish()
        }
    }

    // ── onCreate ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()

        shape       = intent.getStringExtra(EXTRA_REGION_SHAPE) ?: "circle"
        sizePercent = intent.getIntExtra(EXTRA_REGION_SIZE, 25)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Camera preview
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = this@CameraOverlayActivity.surfaceListener
        }
        root.addView(textureView)

        // Region indicator overlay
        root.addView(RegionOverlayView(this, shape, sizePercent).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        // Shutter button
        val btnPx = dpToPx(72)
        root.addView(CaptureButtonView(this).apply {
            layoutParams = FrameLayout.LayoutParams(btnPx, btnPx).apply {
                gravity      = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(56)
            }
            setOnClickListener { takePhoto() }
        })

        // Cancel button (✕ top-left)
        root.addView(TextView(this).apply {
            text     = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity    = Gravity.TOP or Gravity.START
                topMargin  = dpToPx(52)
                leftMargin = dpToPx(24)
            }
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        })

        setContentView(root)
    }

    // ── Camera2 — open ────────────────────────────────────────────────────────
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Camera permission not granted")
            setResult(Activity.RESULT_CANCELED); finish(); return
        }

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            // Pick the back-facing camera (fall back to first available)
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "❌ No camera found"); setResult(Activity.RESULT_CANCELED); finish(); return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation   = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // Choose the largest JPEG output size the camera supports
            val map    = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpgSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val largest = jpgSizes?.maxByOrNull { it.width.toLong() * it.height }
            val jpgW = largest?.width  ?: 1920
            val jpgH = largest?.height ?: 1080

            imageReader = ImageReader.newInstance(jpgW, jpgH, ImageFormat.JPEG, 1).also {
                it.setOnImageAvailableListener(::onImageAvailable, bgHandler)
            }

            manager.openCamera(cameraId, cameraStateCallback, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "❌ openCamera failed: ${e.message}", e)
            setResult(Activity.RESULT_CANCELED); finish()
        }
    }

    // ── Camera2 — preview session ─────────────────────────────────────────────
    private fun createPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            val preview = Surface(texture)
            val capture = imageReader?.surface ?: return

            val previewReq = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            @Suppress("DEPRECATION")
            cameraDevice!!.createCaptureSession(
                listOf(preview, capture),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewReq.build(), null, bgHandler)
                            Log.d(TAG, "✅ Camera preview started")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Preview start failed: ${e.message}", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "❌ Session configuration failed")
                        setResult(Activity.RESULT_CANCELED); finish()
                    }
                },
                bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ createPreviewSession failed: ${e.message}", e)
        }
    }

    // ── Camera2 — capture ─────────────────────────────────────────────────────
    private fun takePhoto() {
        try {
            val readerSurface = imageReader?.surface ?: return
            val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE,    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION,   sensorOrientation)
                set(CaptureRequest.CONTROL_AE_MODE,    CaptureRequest.CONTROL_AE_MODE_ON)
            }
            captureSession?.capture(req.build(), null, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "❌ takePhoto failed: ${e.message}", e)
            setResult(Activity.RESULT_CANCELED); finish()
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()

            val outFile = File(filesDir, "overlay_capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { it.write(bytes) }
            Log.d(TAG, "✅ Photo saved: ${outFile.absolutePath}")

            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PHOTO_PATH, outFile.absolutePath))
            finish()
        } catch (e: Exception) {
            image.close()
            Log.e(TAG, "❌ Failed to save photo: ${e.message}", e)
            setResult(Activity.RESULT_CANCELED); finish()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        bgThread.quitSafely()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}

// =============================================================================
// Region indicator overlay — drawn on top of the TextureView preview
// =============================================================================

internal class RegionOverlayView(
    context: Context,
    private val shape: String,
    private val sizePercent: Int
) : View(context) {

    private val dimPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect  = DashPathEffect(floatArrayOf(20f, 8f), 0f)
        alpha       = 230
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 36f
        alpha     = 200
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height) * sizePercent / 100f
        val cx   = width  / 2f
        val cy   = height * 0.43f  // slightly above centre — shutter button is below
        val rect = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (shape.lowercase() == "circle") canvas.drawOval(rect, borderPaint)
        else canvas.drawRect(rect, borderPaint)

        canvas.drawText("Color Sample Area", cx, rect.bottom + 48f, labelPaint)
    }
}

// =============================================================================
// Shutter button
// =============================================================================

internal class CaptureButtonView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 230
    }

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) - 4
        canvas.drawCircle(cx, cy, r,      ringPaint)
        canvas.drawCircle(cx, cy, r - 12, fillPaint)
    }
}
