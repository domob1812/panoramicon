/*
 * Panoramicon - Spherical panorama viewer
 * Copyright (C) 2025-2026 Daniel Kraft <d@domob.eu>
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>>.
 */

package eu.domob.panoramicon

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.RelativeLayout

import com.panoramagl.*

class PanoramaViewer(
    private val context: Context,
    private val container: RelativeLayout,
    private val onSingleTap: () -> Unit
) : SensorEventListener {

    private val plManager: PLManager
    private val sensorManager: SensorManager
    private val rotationSensor: Sensor?
    private val gestureDetector: GestureDetector

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
    private var wasInertiaActiveOnTouch = false
    private val inertiaHandler = Handler(Looper.getMainLooper())
    private var inertiaRunnable: Runnable? = null
    private var inertiaVelocity = 0f

    init {
        plManager = PLManager(context).apply {
            setContentView(container)
            onCreate()
            stopSensorialRotation()
            setZoomEnabled(true)
            setScrollingEnabled(false)
        }

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!wasInertiaActiveOnTouch) {
                    onSingleTap()
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
                if (wasMultiTouch) {
                    return false
                }

                val horizonDir = getHorizonDirection(currentRotationMatrix) ?: return true

                isScrolling = true
                swipeEndX = e2.x
                swipeEndY = e2.y
                lastHorizonDirection = horizonDir

                val horizontalSwipe = distanceX * horizonDir[0] + distanceY * horizonDir[1]

                val camera = plManager.camera as? PLCamera
                val fovFactor = camera?.fov ?: 70f
                val sensitivity = fovFactor / 70f

                val yawChange = horizontalSwipe * 0.1f * sensitivity
                yawOffset += yawChange

                return true
            }
        })
        gestureDetector.setIsLongpressEnabled(false)
    }

    fun setImage(bitmap: Bitmap) {
        val panorama = PLSphericalPanorama()
        panorama.setImage(PLImage(bitmap, false))
        plManager.panorama = panorama

        val camera = plManager.camera as PLCamera
        camera.zoomFactor = 0.7f

        isYawOffsetInitialized = false
        yawOffset = 0f
    }

    fun onResume() {
        plManager.onResume()
        rotationSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun onPause() {
        sensorManager.unregisterListener(this)
        plManager.onPause()
    }

    fun onDestroy() {
        plManager.onDestroy()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wasInertiaActiveOnTouch = (inertiaRunnable != null)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wasMultiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                wasMultiTouch = true
                stopInertia()
            }
        }

        if (event.pointerCount == 2) {
            plManager.onTouchEvent(event)
            return true
        }

        val gestureHandled = gestureDetector.onTouchEvent(event)

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

        return gestureHandled
    }

    private fun startInertia(horizontalDisplacement: Float) {
        stopInertia()

        val camera = plManager.camera as? PLCamera
        val fovFactor = (camera?.fov ?: 70f) / 70f
        inertiaVelocity = horizontalDisplacement * 0.3f * fovFactor

        inertiaRunnable = object : Runnable {
            override fun run() {
                inertiaVelocity *= 0.92f

                if (kotlin.math.abs(inertiaVelocity) < 0.5f) {
                    stopInertia()
                    return
                }

                yawOffset += inertiaVelocity * 0.1f

                inertiaHandler.postDelayed(this, 16)
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

    private fun getHorizonDirection(rotationMatrix: FloatArray?): FloatArray? {
        if (rotationMatrix == null) return null

        val upX = rotationMatrix[8]
        val upY = rotationMatrix[9]

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

            val axesCorrection = FloatArray(16)
            Matrix.setRotateM(axesCorrection, 0, 180f, 0f, 1f, 0f)

            if (!isYawOffsetInitialized) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(tempMatrix, orientation)
                val initialYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                yawOffset = -initialYaw
                isYawOffsetInitialized = true
            }

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

            (plManager.camera as? PLCamera)?.setRotationMatrix(rotationMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
