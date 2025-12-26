package eu.domob.panoramicon

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.Matrix
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

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var plManager: PLManager
    private lateinit var panoramaContainer: RelativeLayout
    private lateinit var loadingContainer: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private var isSystemUIVisible = false
    private lateinit var gestureDetector: GestureDetector
    private var wasInertiaActiveOnTouch = false

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var initialYaw: Float? = null

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
            
            // Stop any sensorial rotation that might be active
            stopSensorialRotation()
            
            // Enable PLManager's built-in pinch-to-zoom
            setZoomEnabled(true)
            
            // Disable scrolling (we use orientation sensor for camera control)
            setScrollingEnabled(false)
        }

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
                
                val camera = plManager.camera as PLCamera
                camera.zoomFactor = 0.7f
                
                // Reset initial yaw so it gets recaptured on first sensor update
                initialYaw = null
                
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
        rotationSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        plManager.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        plManager.onDestroy()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only pass multi-touch events to PLManager (for pinch-to-zoom)
        // Single-finger touches are handled by gestureDetector for UI toggle
        if (event.pointerCount >= 2) {
            plManager.onTouchEvent(event)
            return true
        }
        
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val tempMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(tempMatrix, event.values)
            /* Mathematically we need to transpose here, but the matrix
               convention (row-major vs column-major) between the orientation
               sensor and OpenGL already accounts for this.  */

            /* Apply correction to map device upright+north to panorama forward.
               180° rotation around Y to fix coordinate system.  */
            val axesCorrection = FloatArray(16)
            Matrix.setRotateM(axesCorrection, 0, 180f, 0f, 1f, 0f)
            
            /* Capture initial yaw on first sensor reading */
            if (initialYaw == null) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(tempMatrix, orientation)
                initialYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
            }
            
            /* 90° pitch around X to tilt from zenith to horizon and yaw rotation
               to align the initial device pointing direction with scene forward.  */
            val pitchRot = FloatArray(16)
            Matrix.setRotateM(pitchRot, 0, 90f, 1f, 0f, 0f)
            val yawRot = FloatArray(16)
            Matrix.setRotateM(yawRot, 0, 180f - initialYaw!!, 0f, 0f, 1f)
            val viewCorrection = FloatArray(16)
            Matrix.multiplyMM(viewCorrection, 0, yawRot, 0, pitchRot, 0)
            
            val intermediate = FloatArray(16)
            Matrix.multiplyMM(intermediate, 0, axesCorrection, 0, tempMatrix, 0)
            val rotationMatrix = FloatArray(16)
            Matrix.multiplyMM(rotationMatrix, 0, intermediate, 0, viewCorrection, 0)

            if (::plManager.isInitialized) {
                (plManager.camera as? PLCamera)?.setRotationMatrix(rotationMatrix)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
