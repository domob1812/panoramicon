/*
 * Panoramicon - Spherical panorama viewer
 * Copyright (C) 2025 Daniel Kraft <d@domob.eu>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
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
    private lateinit var aboutContainer: ScrollView
    private lateinit var aboutVersion: TextView
    private lateinit var aboutBasedOn: TextView
    private lateinit var aboutProjectUrl: TextView
    private lateinit var buttonOpenImage: Button
    private var isSystemUIVisible = false
    private lateinit var gestureDetector: GestureDetector
    private var wasInertiaActiveOnTouch = false

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var yawOffset: Float = 0f
    private var isYawOffsetInitialized = false
    private var currentRotationMatrix: FloatArray? = null

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var swipeEndX = 0f
    private var swipeEndY = 0f
    private var isScrolling = false
    private var lastHorizonDirection: FloatArray? = null
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
        aboutContainer = findViewById(R.id.about_container)
        aboutVersion = findViewById(R.id.about_version)
        aboutBasedOn = findViewById(R.id.about_based_on)
        aboutProjectUrl = findViewById(R.id.about_project_url)
        buttonOpenImage = findViewById(R.id.button_open_image)

        // Set version text
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        aboutVersion.text = "Version $versionName"

        // Set up "Based on PanoramaGL" with clickable link
        val basedOnText = getString(R.string.about_based_on)
        val spannableBasedOn = SpannableString(basedOnText)
        val startIndex = basedOnText.indexOf("PanoramaGL")
        if (startIndex != -1) {
            val endIndex = startIndex + "PanoramaGL".length
            spannableBasedOn.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hannesa2/panoramaGL"))
                    startActivity(intent)
                }
            }, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        aboutBasedOn.text = spannableBasedOn
        aboutBasedOn.movementMethod = LinkMovementMethod.getInstance()

        // Set up project URL with clickable link
        val projectUrl = getString(R.string.about_project_url)
        val spannableProjectUrl = SpannableString(projectUrl)
        spannableProjectUrl.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(projectUrl))
                startActivity(intent)
            }
        }, 0, projectUrl.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        aboutProjectUrl.text = spannableProjectUrl
        aboutProjectUrl.movementMethod = LinkMovementMethod.getInstance()

        // Set up button click listener
        buttonOpenImage.setOnClickListener {
            openImagePicker()
        }

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
                swipeStartY = e.y
                swipeStartTime = System.currentTimeMillis()
                swipeEndX = e.x
                swipeEndY = e.y
                isScrolling = false
                lastHorizonDirection = null
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // Don't scroll if multi-touch occurred at any point in this gesture
                if (wasMultiTouch) {
                    return false
                }
                
                // Compute horizon direction from current device orientation
                val horizonDir = getHorizonDirection(currentRotationMatrix)
                if (horizonDir == null) {
                    // Degenerate case: device pointing straight up/down, disable scrolling
                    return true
                }
                
                isScrolling = true
                swipeEndX = e2.x
                swipeEndY = e2.y
                lastHorizonDirection = horizonDir
                
                // Project swipe onto horizon direction
                // Note: distanceX/Y is positive when swiping left/up, negative when swiping right/down
                val horizontalSwipe = distanceX * horizonDir[0] + distanceY * horizonDir[1]
                
                // Scale by current FoV - smaller FoV (zoomed in) means more sensitive rotation
                val camera = plManager.camera as? PLCamera
                val fovFactor = camera?.fov ?: 70f
                val sensitivity = fovFactor / 70f // Normalize to default FoV
                
                // Apply rotation: roughly 0.1 degree per pixel at default FoV
                val yawChange = horizontalSwipe * 0.1f * sensitivity
                yawOffset += yawChange
                
                return true
            }
        })

        // Track inertia state for touch handling
        gestureDetector.setIsLongpressEnabled(false)

        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (aboutContainer.visibility != View.VISIBLE) {
                    // Currently viewing panorama, go back to about screen
                    showAbout()
                } else {
                    // On about screen, exit app
                    finish()
                }
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
                // No image provided, show about screen
                showAbout()
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
                hideAbout()
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
        hideAbout()
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun showAbout() {
        hideLoading()
        hideError()
        panoramaContainer.visibility = View.GONE
        aboutContainer.visibility = View.VISIBLE
        showSystemUI()
    }

    private fun hideAbout() {
        aboutContainer.visibility = View.GONE
        panoramaContainer.visibility = View.VISIBLE
        hideSystemUI()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_IMAGE_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                loadPanoramaFromUri(uri)
            }
        }
    }

    companion object {
        private const val REQUEST_IMAGE_PICK = 1
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
        if (aboutContainer.visibility != View.VISIBLE) {
            hideSystemUI()
        }
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

        // Pass only 2-finger events to PLManager for pinch-to-zoom
        if (event.pointerCount == 2) {
            plManager.onTouchEvent(event)
            return true
        }
        
        // Handle single-finger touches with gesture detector
        val gestureHandled = gestureDetector.onTouchEvent(event)
        
        // Start inertia when touch ends after scrolling (only for quick gestures)
        if (event.actionMasked == MotionEvent.ACTION_UP && isScrolling) {
            val gestureDuration = System.currentTimeMillis() - swipeStartTime
            val horizonDir = lastHorizonDirection
            if (horizonDir != null && gestureDuration < 300) {
                val deltaX = swipeEndX - swipeStartX
                val deltaY = swipeEndY - swipeStartY
                val horizontalDelta = -(deltaX * horizonDir[0] + deltaY * horizonDir[1])
                if (kotlin.math.abs(horizontalDelta) > 10f) {
                    startInertia(horizontalDelta)
                }
            }
        }
        
        return gestureHandled || super.onTouchEvent(event)
    }

    private fun startInertia(horizontalDisplacement: Float) {
        stopInertia()
        
        // Scale velocity based on FoV
        val camera = plManager.camera as? PLCamera
        val fovFactor = (camera?.fov ?: 70f) / 70f
        inertiaVelocity = horizontalDisplacement * 0.3f * fovFactor
        
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
            if (aboutContainer.visibility != View.VISIBLE) {
                hideSystemUI()
            }
        }
    }

    private fun getHorizonDirection(rotationMatrix: FloatArray?): FloatArray? {
        if (rotationMatrix == null) return null
        
        // 4x4 matrix in column-major order: column i starts at index i*4
        // World Z-axis (up) in device coordinates is the third column of R^T,
        // which equals the third row of R. In column-major, row 2 elements are at indices 2, 6, 10.
        val upX = rotationMatrix[8]
        val upY = rotationMatrix[9]
        
        // Horizon is perpendicular to the up projection (rotate 90°)
        // Note: screen Y points down (left-handed coords)
        val horizonX = upY
        val horizonY = upX
        
        val length = kotlin.math.sqrt(horizonX * horizonX + horizonY * horizonY)
        
        if (length < 0.001f) {
            return null
        }
        
        return floatArrayOf(horizonX / length, horizonY / length)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val tempMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(tempMatrix, event.values)
            currentRotationMatrix = tempMatrix
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
