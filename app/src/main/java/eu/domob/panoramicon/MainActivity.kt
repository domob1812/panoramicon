package eu.domob.panoramicon

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var wasInertiaActiveOnTouch = false

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
            
            // Configure panorama settings - all rotation disabled
            isAccelerometerEnabled = false
            isInertiaEnabled = false
            isZoomEnabled = false
            isScrollingEnabled = false
            isVerticalScrollingEnabled = false
            isAcceleratedTouchScrollingEnabled = false
            
            // Stop any sensorial rotation that might be active
            stopSensorialRotation()
        }

        // Set up gesture detector for single tap detection
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Only toggle system UI if inertia was not active when touch began
                // This prevents UI toggle when tapping to stop inertia
                if (!wasInertiaActiveOnTouch) {
                    toggleSystemUI()
                }
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

                // Set panorama first (this may reset/create camera)
                plManager.panorama = panorama
                
                // Configure camera rotation matrix AFTER panorama is set
                // Custom Euler angles (in degrees) - modify these to experiment
                val yaw = 180f    // Rotation around "up" axis
                val pitch = -45f  // Rotation around "left/right" axis
                val roll = 10f   // Rotation around "forward" axis
                
                // Build and set rotation matrix from Euler angles
                val rotationMatrix = buildRotationMatrix(yaw, pitch, roll)
                val camera = plManager.camera as PLCamera
                camera.setRotationMatrix(rotationMatrix)
                camera.zoomFactor = 0.7f
                
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
        // Only handle gesture detection for UI toggle
        val gestureHandled = gestureDetector.onTouchEvent(event)
        return gestureHandled || super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableEdgeToEdge()
            hideSystemUI()
        }
    }

    private fun buildRotationMatrix(yaw: Float, pitch: Float, roll: Float): FloatArray {
        // Convert degrees to radians
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        val rollRad = Math.toRadians(roll.toDouble()).toFloat()

        // Build individual rotation matrices
        val yawMatrix = createYRotationMatrix(yawRad)
        val pitchMatrix = createXRotationMatrix(pitchRad)
        val rollMatrix = createZRotationMatrix(rollRad)

        // Combine: R = R_yaw * R_pitch * R_roll
        val temp = multiplyMatrices(pitchMatrix, rollMatrix)
        return multiplyMatrices(yawMatrix, temp)
    }

    private fun createXRotationMatrix(angle: Float): FloatArray {
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        return floatArrayOf(
            1f, 0f,  0f,   0f,
            0f, cos, -sin, 0f,
            0f, sin, cos,  0f,
            0f, 0f,  0f,   1f
        )
    }

    private fun createYRotationMatrix(angle: Float): FloatArray {
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        return floatArrayOf(
            cos,  0f, sin, 0f,
            0f,   1f, 0f,  0f,
            -sin, 0f, cos, 0f,
            0f,   0f, 0f,  1f
        )
    }

    private fun createZRotationMatrix(angle: Float): FloatArray {
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        return floatArrayOf(
            cos, -sin, 0f, 0f,
            sin, cos,  0f, 0f,
            0f,  0f,   1f, 0f,
            0f,  0f,   0f, 1f
        )
    }

    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                result[i * 4 + j] = 0f
                for (k in 0..3) {
                    result[i * 4 + j] += a[i * 4 + k] * b[k * 4 + j]
                }
            }
        }
        return result
    }
}
