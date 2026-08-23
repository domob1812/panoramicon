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
    private var manualBase: FloatArray? = null
    private var manualMode = false
    private var manualArc: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var lastArcballVector: FloatArray? = null
    private var swipeStartArcballVector: FloatArray? = null
    private var swipeStartTime = 0L
    private var isScrolling = false
    private var wasMultiTouch = false
    private var wasInertiaActiveOnTouch = false
    private val inertiaHandler = Handler(Looper.getMainLooper())
    private var inertiaRunnable: Runnable? = null
    private var inertiaAxis: FloatArray? = null
    private var inertiaAngle = 0f

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
                swipeStartTime = System.currentTimeMillis()
                isScrolling = false
                val arcball = arcballVector(e.x, e.y)
                lastArcballVector = arcball
                swipeStartArcballVector = arcball
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (wasMultiTouch) {
                    return false
                }

                val last = lastArcballVector ?: return true
                val current = arcballVector(e2.x, e2.y)

                isScrolling = true

                if (manualMode) {
                    val axisAngle = arcballAxisAngle(last, current)
                    val angleDeg = Math.toDegrees(axisAngle[3].toDouble()).toFloat()
                    if (angleDeg > 0f) {
                        val delta = FloatArray(16)
                        Matrix.setRotateM(delta, 0, angleDeg, axisAngle[0], axisAngle[1], axisAngle[2])
                        val next = FloatArray(16)
                        Matrix.multiplyMM(next, 0, delta, 0, manualArc, 0)
                        manualArc = next
                        manualBase?.let { applySensorRotation(it) }
                    }
                } else {
                    val pole = poleAxis() ?: return true
                    val angleRad = signedAngleAroundAxis(last, current, pole)
                    yawOffset -= Math.toDegrees(angleRad.toDouble()).toFloat()
                    yawOffset = normalizeYaw(yawOffset)
                    currentRotationMatrix?.let { applySensorRotation(it) }
                }

                lastArcballVector = current
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
        manualBase = null
        resetManualArc()

        if (manualMode) {
            manualBase = currentRotationMatrix?.copyOf()
            manualBase?.let { applySensorRotation(it) }
        }
    }

    fun setManualMode(enabled: Boolean) {
        if (manualMode == enabled) {
            return
        }
        manualMode = enabled
        if (enabled) {
            manualBase = currentRotationMatrix?.copyOf()
            resetManualArc()
        } else {
            realignHorizon()
            manualBase = null
        }
    }

    private fun realignHorizon() {
        val fresh = currentRotationMatrix ?: return
        val rcam = (plManager.camera as? PLCamera)?.getRotationMatrix()

        if (rcam != null) {
            val y180 = FloatArray(16)
            Matrix.setRotateM(y180, 0, 180f, 0f, 1f, 0f)
            val xm90 = FloatArray(16)
            Matrix.setRotateM(xm90, 0, -90f, 1f, 0f, 0f)
            val y180Rcam = FloatArray(16)
            Matrix.multiplyMM(y180Rcam, 0, y180, 0, rcam, 0)
            val y180RcamXm90 = FloatArray(16)
            Matrix.multiplyMM(y180RcamXm90, 0, y180Rcam, 0, xm90, 0)
            val freshT = FloatArray(16)
            Matrix.transposeM(freshT, 0, fresh, 0)
            val m = FloatArray(16)
            Matrix.multiplyMM(m, 0, freshT, 0, y180RcamXm90, 0)
            val angle = Math.toDegrees(Math.atan2(m[1].toDouble(), m[0].toDouble())).toFloat()
            yawOffset = normalizeYaw(angle - 180f)
        }

        resetManualArc()
        applySensorRotation(fresh)
    }

    private fun deviceAzimuth(matrix: FloatArray): Float {
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        return Math.toDegrees(orientation[0].toDouble()).toFloat()
    }

    private fun normalizeYaw(deg: Float): Float {
        var result = deg % 360f
        if (result > 180f) {
            result -= 360f
        }
        if (result < -180f) {
            result += 360f
        }
        return result
    }

    fun onResume() {
        plManager.onResume()
        registerSensor()
    }

    fun onPause() {
        unregisterSensor()
        plManager.onPause()
    }

    private fun registerSensor() {
        rotationSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
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
            val start = swipeStartArcballVector
            val end = lastArcballVector
            if (start != null && end != null && gestureDuration < 300) {
                if (manualMode) {
                    val axisAngle = arcballAxisAngle(start, end)
                    val angleDeg = Math.toDegrees(axisAngle[3].toDouble()).toFloat()
                    if (angleDeg > 1f) {
                        startInertia(floatArrayOf(axisAngle[0], axisAngle[1], axisAngle[2]), angleDeg * 0.25f)
                    }
                } else {
                    val pole = poleAxis()
                    if (pole != null) {
                        val angleRad = signedAngleAroundAxis(start, end, pole)
                        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                        if (kotlin.math.abs(angleDeg) > 1f) {
                            startInertia(null, angleDeg * 0.25f)
                        }
                    }
                }
            }
        }

        return gestureHandled
    }

    private fun startInertia(axis: FloatArray?, angle: Float) {
        stopInertia()

        inertiaAxis = axis?.copyOf()
        inertiaAngle = angle

        inertiaRunnable = object : Runnable {
            override fun run() {
                inertiaAngle *= 0.92f

                if (kotlin.math.abs(inertiaAngle) < 0.05f) {
                    stopInertia()
                    return
                }

                if (manualMode) {
                    val axis3 = inertiaAxis
                    if (axis3 != null) {
                        val delta = FloatArray(16)
                        Matrix.setRotateM(delta, 0, inertiaAngle, axis3[0], axis3[1], axis3[2])
                        val next = FloatArray(16)
                        Matrix.multiplyMM(next, 0, delta, 0, manualArc, 0)
                        manualArc = next
                        manualBase?.let { applySensorRotation(it) }
                    }
                } else {
                    yawOffset -= inertiaAngle
                    yawOffset = normalizeYaw(yawOffset)
                    currentRotationMatrix?.let { applySensorRotation(it) }
                }

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
        inertiaAxis = null
        inertiaAngle = 0f
    }

    private fun arcballVector(x: Float, y: Float): FloatArray {
        val camera = plManager.camera as? PLCamera
        val fov = camera?.fov ?: PLConstants.kDefaultFov
        val f = (1.0 / Math.tan(Math.toRadians((fov / 2.0).toDouble()))).toFloat()

        val width = container.width.toFloat()
        val height = container.height.toFloat()

        val ndcX = (2f * x - width) / 4096f
        val ndcY = (height - 2f * y) / 4096f

        val dx = ndcX / f
        val dy = ndcY / f
        val dz = -1f
        val dLen = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val ax = -dx / dLen
        val ay = dy / dLen
        val az = -dz / dLen / 5.12f
        val aLen = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        return floatArrayOf(ax / aLen, ay / aLen, az / aLen)
    }

    private fun arcballAxisAngle(from: FloatArray, to: FloatArray): FloatArray {
        val c = cross(from, to)
        val crossLen = kotlin.math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
        val dot = (from[0] * to[0] + from[1] * to[1] + from[2] * to[2]).coerceIn(-1f, 1f)

        if (crossLen < 1e-6f) {
            if (dot > 0f) {
                return floatArrayOf(1f, 0f, 0f, 0f)
            }
            val axis = normalize(cross(from, anyPerpendicular(from)))
            return floatArrayOf(axis[0], axis[1], axis[2], Math.PI.toFloat())
        }

        val axis = floatArrayOf(c[0] / crossLen, c[1] / crossLen, c[2] / crossLen)
        return floatArrayOf(axis[0], axis[1], axis[2], Math.acos(dot.toDouble()).toFloat())
    }

    private fun signedAngleAroundAxis(u0: FloatArray, u1: FloatArray, axis: FloatArray): Float {
        val p0 = projectToPlane(u0, axis)
        val p1 = projectToPlane(u1, axis)
        val l0 = kotlin.math.sqrt(p0[0] * p0[0] + p0[1] * p0[1] + p0[2] * p0[2])
        val l1 = kotlin.math.sqrt(p1[0] * p1[0] + p1[1] * p1[1] + p1[2] * p1[2])
        if (l0 < 1e-6f || l1 < 1e-6f) {
            return 0f
        }
        val a = floatArrayOf(p0[0] / l0, p0[1] / l0, p0[2] / l0)
        val b = floatArrayOf(p1[0] / l1, p1[1] / l1, p1[2] / l1)
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        val angle = Math.acos(dot.toDouble()).toFloat()
        val c = cross(a, b)
        val sign = c[0] * axis[0] + c[1] * axis[1] + c[2] * axis[2]
        return if (sign < 0f) -angle else angle
    }

    private fun poleAxis(): FloatArray? {
        val rcam = (plManager.camera as? PLCamera)?.getRotationMatrix() ?: return null
        return floatArrayOf(-rcam[4], -rcam[5], -rcam[6])
    }

    private fun projectToPlane(v: FloatArray, normal: FloatArray): FloatArray {
        val dot = v[0] * normal[0] + v[1] * normal[1] + v[2] * normal[2]
        return floatArrayOf(
            v[0] - dot * normal[0],
            v[1] - dot * normal[1],
            v[2] - dot * normal[2]
        )
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    private fun normalize(v: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-9f) {
            return floatArrayOf(0f, 0f, 0f)
        }
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun anyPerpendicular(v: FloatArray): FloatArray {
        val absX = kotlin.math.abs(v[0])
        val absY = kotlin.math.abs(v[1])
        val absZ = kotlin.math.abs(v[2])
        val axis = if (absX < absY && absX < absZ) {
            floatArrayOf(1f, 0f, 0f)
        } else if (absY < absZ) {
            floatArrayOf(0f, 1f, 0f)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }
        return normalize(cross(v, axis))
    }

    private fun resetManualArc() {
        Matrix.setIdentityM(manualArc, 0)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val tempMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(tempMatrix, event.values)
            currentRotationMatrix = tempMatrix

            if (!manualMode) {
                applySensorRotation(tempMatrix)
            }
        }
    }

    private fun applySensorRotation(baseMatrix: FloatArray) {
        val axesCorrection = FloatArray(16)
        Matrix.setRotateM(axesCorrection, 0, 180f, 0f, 1f, 0f)

        if (!isYawOffsetInitialized) {
            yawOffset = -deviceAzimuth(baseMatrix)
            isYawOffsetInitialized = true
        }

        val pitchRot = FloatArray(16)
        Matrix.setRotateM(pitchRot, 0, 90f, 1f, 0f, 0f)
        val yawRot = FloatArray(16)
        Matrix.setRotateM(yawRot, 0, 180f + yawOffset, 0f, 0f, 1f)
        val viewCorrection = FloatArray(16)
        Matrix.multiplyMM(viewCorrection, 0, yawRot, 0, pitchRot, 0)

        val intermediate = FloatArray(16)
        Matrix.multiplyMM(intermediate, 0, axesCorrection, 0, baseMatrix, 0)
        val rotationMatrix = FloatArray(16)
        Matrix.multiplyMM(rotationMatrix, 0, intermediate, 0, viewCorrection, 0)

        val withArc = FloatArray(16)
        Matrix.multiplyMM(withArc, 0, manualArc, 0, rotationMatrix, 0)

        (plManager.camera as? PLCamera)?.setRotationMatrix(withArc)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
