package eu.domob.panoramicon

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.panoramagl.*
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var plManager: PLManager
    private lateinit var panoramaContainer: RelativeLayout
    private lateinit var loadingContainer: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private var isSystemUIVisible = false
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        panoramaContainer = findViewById(R.id.panorama_container)
        loadingContainer = findViewById(R.id.loading_container)
        loadingProgress = findViewById(R.id.loading_progress)
        loadingText = findViewById(R.id.loading_text)
        errorText = findViewById(R.id.error_text)

        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        // Hide system UI for fullscreen immersive experience
        hideSystemUI()

        // Initialize PanoramaGL manager
        plManager = PLManager(this).apply {
            setContentView(panoramaContainer)
            onCreate()
            
            // Configure panorama settings
            isAccelerometerEnabled = true
            isInertiaEnabled = true
            isZoomEnabled = true
            isScrollingEnabled = true
            isAcceleratedTouchScrollingEnabled = true
        }

        // Set up gesture detector for single tap detection
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleSystemUI()
                return true
            }
        })

        // Handle intent (image sharing/opening)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                uri?.let { loadPanoramaFromUri(it) }
            }
            else -> {
                // No image provided, show error
                showError("No panoramic image provided.\nPlease share or open an image with this app.")
            }
        }
    }

    private fun loadPanoramaFromUri(uri: Uri) {
        showLoading()
        
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                // Create spherical panorama
                val panorama = PLSphericalPanorama()
                panorama.setImage(PLImage(bitmap, false))

                // Configure camera
                panorama.camera.apply {
                    lookAtAndZoomFactor(0f, 0f, 0.7f, false)
                    rotationSensitivity = 270f // Increase sensitivity for better touch response
                }

                // Set panorama
                plManager.panorama = panorama
                plManager.startSensorialRotation()

                hideLoading()
                hideError()
            } else {
                showError("Failed to decode image.\nPlease try with a different image format.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showError("Error loading image: ${e.message}")
        }
    }

    private fun showLoading() {
        loadingContainer.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
    }

    private fun showError(message: String) {
        hideLoading()
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun enableEdgeToEdge() {
        // Simple edge-to-edge setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, panoramaContainer).let { controller ->
            // Hide both status bar and navigation bar
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        isSystemUIVisible = false
    }

    private fun showSystemUI() {
        WindowInsetsControllerCompat(window, panoramaContainer).let { controller ->
            // Show both status bar and navigation bar
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        isSystemUIVisible = true
    }

    private fun toggleSystemUI() {
        if (isSystemUIVisible) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
    }

    override fun onResume() {
        super.onResume()
        plManager.onResume()
        enableEdgeToEdge()
        hideSystemUI()
    }

    override fun onPause() {
        plManager.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        plManager.onDestroy()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // First, let the gesture detector check for single taps
        val gestureHandled = gestureDetector.onTouchEvent(event)
        
        // If it wasn't a single tap, pass the event to the panorama manager
        val panoramaHandled = if (!gestureHandled) {
            plManager.onTouchEvent(event)
        } else {
            false
        }
        
        return gestureHandled || panoramaHandled || super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableEdgeToEdge()
            hideSystemUI()
        }
    }
}