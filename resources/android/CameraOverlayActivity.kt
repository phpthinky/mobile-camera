package com.nativephp.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera activity that shows a live preview with a configurable
 * circle or box overlay in the centre to guide colour extraction.
 *
 * Launched by [CameraCoordinator] when `regionIndicator = true`.
 * Returns the captured JPEG path via [RESULT_PHOTO_PATH].
 */
class CameraOverlayActivity : AppCompatActivity() {

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

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()

        val shape = intent.getStringExtra(EXTRA_REGION_SHAPE) ?: "circle"
        val size  = intent.getIntExtra(EXTRA_REGION_SIZE, 25)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ── Root container ────────────────────────────────────────────────────
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // ── Camera preview ────────────────────────────────────────────────────
        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView)

        // ── Region indicator overlay ──────────────────────────────────────────
        root.addView(RegionOverlayView(this, shape, size).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        // ── Shutter button ────────────────────────────────────────────────────
        val btnSizePx = dpToPx(72)
        root.addView(CaptureButtonView(this).apply {
            layoutParams = FrameLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(56)
            }
            setOnClickListener { takePhoto() }
        })

        // ── Cancel button (✕ top-left) ────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity   = Gravity.TOP or Gravity.START
                topMargin  = dpToPx(52)
                leftMargin = dpToPx(24)
            }
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        })

        setContentView(root)
        startCamera(previewView)
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                Log.d(TAG, "✅ Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera binding failed: ${e.message}", e)
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val outFile = File(filesDir, "overlay_capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(options, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "✅ Photo saved: ${outFile.absolutePath}")
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(RESULT_PHOTO_PATH, outFile.absolutePath)
                    )
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "❌ Photo capture failed: ${e.message}", e)
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
// Region indicator overlay
// =============================================================================

/**
 * Transparent view drawn on top of the camera preview.
 * Dims the area outside the indicator region and draws a dashed circle/box
 * border in the centre to show the user where colour will be sampled from.
 */
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
        pathEffect = DashPathEffect(floatArrayOf(20f, 8f), 0f)
        alpha = 230
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        alpha = 200
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height) * sizePercent / 100f
        val cx   = width  / 2f
        // Position slightly above vertical centre so the shutter button below
        // doesn't overlap the region indicator.
        val cy   = height * 0.43f
        val rect = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)

        // Dim the whole view
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Draw dashed border around the region
        if (shape.lowercase() == "circle") {
            canvas.drawOval(rect, borderPaint)
        } else {
            canvas.drawRect(rect, borderPaint)
        }

        // Label below the region
        canvas.drawText("Color Sample Area", cx, rect.bottom + 48f, labelPaint)
    }
}

// =============================================================================
// Shutter button
// =============================================================================

/**
 * Simple circular shutter button drawn programmatically — outer ring + inner
 * filled circle, matching the feel of native camera apps.
 */
internal class CaptureButtonView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 230
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val cx      = width  / 2f
        val cy      = height / 2f
        val outerR  = minOf(cx, cy) - 4
        canvas.drawCircle(cx, cy, outerR,      ringPaint)
        canvas.drawCircle(cx, cy, outerR - 12, fillPaint)
    }
}
