package com.nativephp.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction

/**
 * Functions related to gallery/media picker operations
 * Namespace: "Camera.*" (gallery operations are part of Camera namespace)
 */
object GalleryFunctions {

    /**
     * Pick media from the device gallery
     * Parameters:
     *   - mediaType: (optional) string - Type of media to pick: "image", "video", or "all" (default: "all")
     *   - multiple: (optional) boolean - Allow multiple selection (default: false)
     *   - maxItems: (optional) int - Maximum number of items when multiple=true (default: 10)
     *   - id: (optional) string - Optional ID to track this operation
     *   - event: (optional) string - Custom event class to fire (defaults to "NativePHP\Camera\Events\MediaSelected")
     * Returns:
     *   - (empty map - results are returned via events)
     * Events:
     *   - Fires "NativePHP\Camera\Events\MediaSelected" (or custom event) when media is selected or cancelled
     */
    class PickMedia(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mediaType = parameters["mediaType"] as? String ?: "all"
            val multiple = parameters["multiple"] as? Boolean ?: false
            val maxItems = (parameters["maxItems"] as? Number)?.toInt() ?: 10
            val id = parameters["id"] as? String
            val event = parameters["event"] as? String
            val includeBase64 = parameters["includeBase64"] as? Boolean ?: false
            val quality = (parameters["quality"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 90
            val maxWidth = (parameters["width"] as? Number)?.toInt()
            val maxHeight = (parameters["height"] as? Number)?.toInt()

            Log.d("GalleryFunctions.PickMedia", "🖼️ Picking media with mediaType=$mediaType, multiple=$multiple, maxItems=$maxItems, id=$id, event=$event, includeBase64=$includeBase64, quality=$quality, maxWidth=$maxWidth, maxHeight=$maxHeight")

            // Launch gallery on UI thread
            Handler(Looper.getMainLooper()).post {
                try {
                    val coord = CameraCoordinator.install(activity)
                    coord.launchGallery(mediaType, multiple, maxItems, id, event, includeBase64, quality, maxWidth, maxHeight)
                } catch (e: Exception) {
                    Log.e("GalleryFunctions.PickMedia", "❌ Error launching gallery: ${e.message}", e)
                }
            }

            return emptyMap()
        }
    }
}
