/*
 * Panoramicon - Spherical panorama viewer
 * Copyright (C) 2026 Daniel Kraft <d@domob.eu>
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class PanoramaNetworkLoader(private val context: Context) {

    private var currentThread: Thread? = null
    private var cancelled = false

    interface LoadCallback {
        fun onProgress(message: String)
        fun onDownloadProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onSuccess(data: File)
        fun onError(message: String)
    }

    fun cancel() {
        cancelled = true
        currentThread?.interrupt()
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun loadFromUrl(url: String, callback: LoadCallback) {
        if (!isNetworkAvailable()) {
            callback.onError("No network connection available.")
            return
        }
        
        cancelled = false
        callback.onProgress("Downloading image...")
        val handler = Handler(Looper.getMainLooper())
        currentThread = Thread {
            var tempFile: File? = null
            var delivered = false
            try {
                if (cancelled) return@Thread
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val contentLength = connection.contentLengthLong
                    tempFile = File.createTempFile("panorama-", ".img", context.cacheDir)
                    val inputStream = connection.inputStream
                    downloadWithProgress(inputStream, contentLength, tempFile!!, handler, callback)
                    inputStream.close()
                    connection.disconnect()

                    if (!cancelled) {
                        handler.post {
                            callback.onSuccess(tempFile)
                        }
                        delivered = true
                    }
                } else {
                    connection.disconnect()
                    handler.post {
                        callback.onError("Failed to download image.\nHTTP ${connection.responseCode}")
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                android.util.Log.e("PanoramaNetworkLoader", "DNS resolution failed", e)
                handler.post {
                    callback.onError("Cannot resolve host.\nPlease check your network connection.")
                }
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("PanoramaNetworkLoader", "Connection timeout", e)
                handler.post {
                    callback.onError("Connection timeout.\nPlease check your network connection.")
                }
            } catch (e: Exception) {
                android.util.Log.e("PanoramaNetworkLoader", "Download failed", e)
                e.printStackTrace()
                if (!cancelled) {
                    handler.post {
                        callback.onError("Error downloading image: ${e.message}")
                    }
                }
            } finally {
                if (tempFile != null && !delivered) {
                    tempFile.delete()
                }
            }
        }
        currentThread?.start()
    }

    private fun downloadWithProgress(
        inputStream: InputStream,
        totalBytes: Long,
        destFile: File,
        handler: Handler,
        callback: LoadCallback
    ) {
        val buffer = ByteArray(8192)
        FileOutputStream(destFile).use { output ->
            var bytesRead: Int
            var totalRead: Long = 0
            var lastReportedProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (cancelled) {
                    throw InterruptedException("Download cancelled")
                }
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    val progress = ((totalRead * 100) / totalBytes).toInt()
                    if (progress != lastReportedProgress) {
                        lastReportedProgress = progress
                        handler.post {
                            callback.onDownloadProgress(totalRead, totalBytes)
                        }
                    }
                }
            }

            if (totalBytes > 0 && totalRead < totalBytes) {
                throw java.io.IOException("Download incomplete: received $totalRead of $totalBytes bytes.")
            }
        }
    }
}
