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
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
 * Full-screen camera activity using CameraX with a layered FrameLayout.
 *
 * Layout stack (bottom → top):
 *   1. PreviewView  — camera frames, managed entirely by CameraX
 *   2. RegionOverlayView — plain UI View drawn by the main thread;
 *      completely independent of the camera pipeline (no Surface conflicts)
 *   3. Shutter button + Cancel button
 *
 * The overlay is kept INVISIBLE until ProcessCameraProvider confirms the
 * camera is bound, avoiding any draw/calculate work before the preview is live.
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

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlayView: RegionOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shape = intent.getStringExtra(EXTRA_REGION_SHAPE) ?: "circle"
        val size  = intent.getIntExtra(EXTRA_REGION_SIZE, 25)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ── Root container ────────────────────────────────────────────────────
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ── Layer 1 (bottom): camera preview ──────────────────────────────────
        // PreviewView handles its own Surface lifecycle; we never touch it.
        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView)

        // ── Layer 2 (middle): region indicator ───────────────────────────────
        // A standard View drawn by the UI thread — no camera surface interaction.
        // Kept invisible until the camera is live so nothing is calculated early.
        overlayView = RegionOverlayView(this, shape, size).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
        }
        root.addView(overlayView)

        // ── Layer 3 (top): shutter button ─────────────────────────────────────
        val btnPx = dpToPx(72)
        root.addView(CaptureButtonView(this).apply {
            layoutParams = FrameLayout.LayoutParams(btnPx, btnPx).apply {
                gravity      = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(56)
            }
            setOnClickListener { takePhoto() }
        })

        // ── Layer 4 (top): cancel button ──────────────────────────────────────
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

        // goFullscreen() must be called AFTER setContentView so the DecorView exists.
        // Calling window.insetsController before DecorView is created NPEs inside PhoneWindow.
        goFullscreen()

        // Wait until PreviewView is fully attached and measured before binding CameraX.
        // Calling ProcessCameraProvider before the view is laid out can cause a crash
        // because the Surface provider has no dimensions yet.
        previewView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    previewView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    startCamera(previewView)
                }
            }
        )
    }

    // ── CameraX ───────────────────────────────────────────────────────────────

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,   // ComponentActivity implements LifecycleOwner
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )

                // Camera is live and preview is rendering — safe to show overlay now
                runOnUiThread {
                    overlayView.visibility = View.VISIBLE
                    Log.d(TAG, "✅ Camera bound, overlay visible")
                }
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
                    Log.e(TAG, "❌ Capture failed: ${e.message}", e)
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
// Drawn entirely by the UI thread — independent of the camera pipeline.
// Uses four dim strips around the region so the centre is naturally transparent
// (no blend modes, no compositing tricks needed).
// =============================================================================

internal class RegionOverlayView(
    context: Context,
    private val shape: String,
    private val sizePercent: Int
) : View(context) {

    private val dimPaint = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
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
        // Slightly above vertical centre so the shutter button doesn't overlap
        val cy   = height * 0.43f
        val rect = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)

        // Four dim strips surrounding the region — centre stays transparent,
        // camera preview shows through naturally with no blend-mode tricks.
        canvas.drawRect(0f,          0f,          width.toFloat(), rect.top,          dimPaint)
        canvas.drawRect(0f,          rect.bottom, width.toFloat(), height.toFloat(),  dimPaint)
        canvas.drawRect(0f,          rect.top,    rect.left,       rect.bottom,       dimPaint)
        canvas.drawRect(rect.right,  rect.top,    width.toFloat(), rect.bottom,       dimPaint)

        // Dashed circle or box border
        if (shape.lowercase() == "circle") canvas.drawOval(rect, borderPaint)
        else canvas.drawRect(rect, borderPaint)

        // Label below the region
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
