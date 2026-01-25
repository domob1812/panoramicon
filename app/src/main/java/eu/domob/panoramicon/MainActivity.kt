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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.domob.panoramicon

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.panoramagl.*
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var panoramaViewer: PanoramaViewer
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
    private lateinit var buttonExample: Button
    private lateinit var buttonCancelDownload: Button
    private var isSystemUIVisible = false
    private var isDownloading = false
    private lateinit var networkLoader: PanoramaNetworkLoader

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
        buttonExample = findViewById(R.id.button_example)
        buttonCancelDownload = findViewById(R.id.button_cancel_download)

        // Initialize network loader
        networkLoader = PanoramaNetworkLoader(this)

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

        // Set up button click listeners
        buttonOpenImage.setOnClickListener {
            openImagePicker()
        }
        buttonExample.setOnClickListener {
            loadExamplePanorama()
        }
        buttonCancelDownload.setOnClickListener {
            cancelDownload()
        }

        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        // Hide system UI for fullscreen immersive experience
        hideSystemUI()

        // Initialize panorama viewer
        panoramaViewer = PanoramaViewer(this, panoramaContainer) {
            toggleSystemUI()
        }

        // Handle back button
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDownloading) {
                    // Cancel download and return to about screen
                    cancelDownload()
                } else if (aboutContainer.visibility != View.VISIBLE) {
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
            Intent.ACTION_VIEW -> {
                intent.data?.let { loadPanoramaFromUri(it) }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedText?.let { text ->
                        val url = extractImageUrl(text)
                        if (url != null) {
                            loadPanoramaFromUrl(url)
                        } else {
                            showError("No valid image URL found in shared text.")
                        }
                    }
                } else {
                    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    uri?.let { loadPanoramaFromUri(it) }
                }
            }
            else -> {
                // No image provided, show about screen
                showAbout()
            }
        }
    }

    private fun extractImageUrl(text: String): String? {
        val urlPattern = "https?://[^\\s<>\"]+".toRegex()
        val match = urlPattern.find(text)
        val url = match?.value?.trimEnd(',', '.', ')', ']', '}')
        return url
    }

    private fun loadPanoramaFromUri(uri: Uri) {
        val scheme = uri.scheme
        if (scheme == "http" || scheme == "https") {
            loadPanoramaFromUrl(uri.toString())
        } else {
            showLoading("Loading image...")
            val handler = Handler(Looper.getMainLooper())
            Thread {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val bytes = inputStream.readBytes()
                        inputStream.close()
                        
                        handler.post {
                            loadingText.text = "Processing image..."
                            try {
                                val processedStream = bytes.inputStream()
                                loadPanoramaFromStream(processedStream)
                                processedStream.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                showError("Error processing image: ${e.message}")
                            }
                        }
                    } else {
                        handler.post {
                            showError("Failed to open image.")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    handler.post {
                        showError("Error loading image: ${e.message}")
                    }
                }
            }.start()
        }
    }

    private fun loadPanoramaFromUrl(url: String) {
        networkLoader.loadFromUrl(url, object : PanoramaNetworkLoader.LoadCallback {
            override fun onProgress(message: String) {
                showLoading(message, isDownload = true)
            }

            override fun onDownloadProgress(bytesDownloaded: Long, totalBytes: Long) {
                val percentage = ((bytesDownloaded * 100) / totalBytes).toInt()
                val downloadedMB = bytesDownloaded / (1024.0 * 1024.0)
                val totalMB = totalBytes / (1024.0 * 1024.0)
                loadingProgress.isIndeterminate = false
                loadingProgress.progress = percentage
                loadingText.text = String.format("Downloading image... %d%%\n%.1f MB / %.1f MB", percentage, downloadedMB, totalMB)
            }

            override fun onSuccess(data: ByteArray) {
                isDownloading = false
                buttonCancelDownload.visibility = View.GONE
                loadingProgress.isIndeterminate = true
                loadingProgress.progress = 0
                loadingText.text = "Processing image..."
                Handler(Looper.getMainLooper()).post {
                    try {
                        val processedStream = data.inputStream()
                        loadPanoramaFromStream(processedStream)
                        processedStream.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showError("Error processing image: ${e.message}")
                    }
                }
            }

            override fun onError(message: String) {
                isDownloading = false
                buttonCancelDownload.visibility = View.GONE
                showError(message)
            }
        })
    }

    private fun loadExamplePanorama() {
        showLoading("Loading image...")
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val inputStream = assets.open("examples/Sihlwald.jpg")
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                handler.post {
                    loadingText.text = "Processing image..."
                    try {
                        val processedStream = bytes.inputStream()
                        loadPanoramaFromStream(processedStream)
                        processedStream.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showError("Error processing image: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post {
                    showError("Error loading example image: ${e.message}")
                }
            }
        }.start()
    }

    private fun loadPanoramaFromStream(inputStream: InputStream) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap == null) {
                    handler.post {
                        showError("Failed to decode image.\nPlease try with a different image format.")
                    }
                    return@Thread
                }

                val width = bitmap.width
                val height = bitmap.height

                if (width != 2 * height) {
                    val aspectRatio = width.toDouble() / height.toDouble()
                    bitmap.recycle()
                    handler.post {
                        hideLoading()
                        showAbout()
                        AlertDialog.Builder(this)
                            .setTitle("Invalid Image")
                            .setMessage("The selected image does not appear to be a spherical panorama in equirectangular projection.\n\nSpherical panoramas must have an aspect ratio of exactly 2:1 (width:height).\n\nThis image has an aspect ratio of ${String.format("%.2f", aspectRatio)}:1.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }

                val scaledBitmap = scaleImageIfNeeded(bitmap)
                handler.post {
                    panoramaViewer.setImage(scaledBitmap)
                    hideLoading()
                    hideError()
                    hideAbout()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post {
                    showError("Error processing image: ${e.message}")
                }
            }
        }.start()
    }

    private fun scaleImageIfNeeded(bitmap: Bitmap): Bitmap {
        val maxSize = PLConstants.kTextureMaxSize
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val (newWidth, newHeight) = if (width > height) {
            maxSize to (height.toLong() * maxSize / width).toInt()
        } else {
            (width.toLong() * maxSize / height).toInt() to maxSize
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        bitmap.recycle()
        return scaledBitmap
    }

    private fun showLoading(message: String = "Loading...", isDownload: Boolean = false) {
        loadingText.text = message
        loadingProgress.isIndeterminate = true
        loadingProgress.progress = 0
        loadingContainer.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        aboutContainer.visibility = View.GONE
        showSystemUI()
        if (isDownload) {
            isDownloading = true
            buttonCancelDownload.visibility = View.VISIBLE
        } else {
            isDownloading = false
            buttonCancelDownload.visibility = View.GONE
        }
    }

    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
        isDownloading = false
        buttonCancelDownload.visibility = View.GONE
    }

    private fun showError(message: String) {
        hideLoading()
        showAbout()
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun cancelDownload() {
        networkLoader.cancel()
        isDownloading = false
        hideLoading()
        showAbout()
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
        errorText.visibility = View.GONE
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, panoramaContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        isSystemUIVisible = false
    }

    private fun showSystemUI() {
        WindowInsetsControllerCompat(window, panoramaContainer).let { controller ->
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        isSystemUIVisible = true
    }

    private fun toggleSystemUI() {
        if (panoramaContainer.visibility == View.VISIBLE && aboutContainer.visibility != View.VISIBLE && loadingContainer.visibility != View.VISIBLE) {
            if (isSystemUIVisible) {
                hideSystemUI()
            } else {
                showSystemUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        panoramaViewer.onResume()
        enableEdgeToEdge()
        if (aboutContainer.visibility != View.VISIBLE && loadingContainer.visibility != View.VISIBLE) {
            hideSystemUI()
        }
    }

    override fun onPause() {
        panoramaViewer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        panoramaViewer.onDestroy()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return panoramaViewer.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableEdgeToEdge()
            if (aboutContainer.visibility != View.VISIBLE && loadingContainer.visibility != View.VISIBLE) {
                hideSystemUI()
            }
        }
    }
}
