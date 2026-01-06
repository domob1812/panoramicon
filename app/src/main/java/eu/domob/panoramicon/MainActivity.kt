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
    private var yawOffset: Float = 0f
    private var isYawOffsetInitialized = false

    private var swipeStartX = 0f
    private var swipeEndX = 0f
    private var isScrolling = false
    private var wasMultiTouch = false
    private val inertiaHandler = Handler(Looper.getMainLooper())
    private var inertiaRunnable: Runnable? = null
    private var inertiaVelocity = 0f

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

        // Set up gesture detector for tap and scroll detection
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Only toggle system UI if inertia was not active when touch began
                // This prevents UI toggle when tapping to stop inertia
                if (!wasInertiaActiveOnTouch) {
                    toggleSystemUI()
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                stopInertia()
                swipeStartX = e.x
                swipeEndX = e.x
                isScrolling = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // Don't scroll if multi-touch occurred at any point in this gesture
                if (wasMultiTouch) {
                    return false
                }
                
                isScrolling = true
                swipeEndX = e2.x
                
                // Calculate yaw change based on horizontal movement
                // distanceX is positive when swiping left, negative when swiping right
                // Invert: swipe right -> panorama rotates right (view moves left)
                val deltaX = distanceX
                
                // Scale by current FoV - smaller FoV (zoomed in) means more sensitive rotation
                val camera = plManager.camera as? PLCamera
                val fovFactor = camera?.fov ?: 70f
                val sensitivity = fovFactor / 70f // Normalize to default FoV
                
                // Apply rotation: roughly 0.1 degree per pixel at default FoV
                val yawChange = deltaX * 0.1f * sensitivity
                yawOffset += yawChange
                
                return true
            }
        })

        // Track inertia state for touch handling
        gestureDetector.setIsLongpressEnabled(false)

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
                
                // Reset yaw offset so it gets recaptured on first sensor update
                isYawOffsetInitialized = false
                yawOffset = 0f
                
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
        // Track inertia state
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wasInertiaActiveOnTouch = (inertiaRunnable != null)
        }

        // Track multi-touch: once it occurs, disable scrolling for the rest of this gesture
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wasMultiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                wasMultiTouch = true
                stopInertia()
            }
        }

        // Pass multi-touch events to PLManager for pinch-to-zoom
        if (event.pointerCount >= 2) {
            plManager.onTouchEvent(event)
            return true
        }
        
        // Handle single-finger touches with gesture detector
        val gestureHandled = gestureDetector.onTouchEvent(event)
        
        // Start inertia when touch ends after scrolling
        if (event.actionMasked == MotionEvent.ACTION_UP && isScrolling) {
            val deltaX = swipeEndX - swipeStartX
            if (kotlin.math.abs(deltaX) > 10f) {
                startInertia(deltaX)
            }
        }
        
        return gestureHandled || super.onTouchEvent(event)
    }

    private fun startInertia(initialVelocityX: Float) {
        stopInertia()
        
        // Scale velocity based on FoV
        // Note: initialVelocityX is positive when swiping right, negative when swiping left
        val camera = plManager.camera as? PLCamera
        val fovFactor = (camera?.fov ?: 70f) / 70f
        inertiaVelocity = -initialVelocityX * 0.3f * fovFactor
        
        inertiaRunnable = object : Runnable {
            override fun run() {
                // Apply friction
                inertiaVelocity *= 0.92f
                
                // Stop if velocity is too small
                if (kotlin.math.abs(inertiaVelocity) < 0.5f) {
                    stopInertia()
                    return
                }
                
                // Apply inertia rotation
                yawOffset += inertiaVelocity * 0.1f
                
                // Continue inertia
                inertiaHandler.postDelayed(this, 16) // ~60fps
            }
        }
        inertiaHandler.postDelayed(inertiaRunnable!!, 16)
    }

    private fun stopInertia() {
        inertiaRunnable?.let {
            inertiaHandler.removeCallbacks(it)
            inertiaRunnable = null
        }
        inertiaVelocity = 0f
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
            
            /* Initialize yaw offset on first sensor reading */
            if (!isYawOffsetInitialized) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(tempMatrix, orientation)
                val initialYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                yawOffset = -initialYaw  // Set initial offset to neutralize sensor yaw
                isYawOffsetInitialized = true
            }
            
            /* 90° pitch around X to tilt from zenith to horizon and yaw rotation
               that combines the initial device orientation with user swipe offset.  */
            val pitchRot = FloatArray(16)
            Matrix.setRotateM(pitchRot, 0, 90f, 1f, 0f, 0f)
            val yawRot = FloatArray(16)
            Matrix.setRotateM(yawRot, 0, 180f + yawOffset, 0f, 0f, 1f)
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
