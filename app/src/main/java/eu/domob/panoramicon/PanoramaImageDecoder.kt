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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.panoramagl.PLConstants
import java.io.InputStream

/* Decode a panorama image while keeping the memory footprint bounded.
   The image is never read into a single byte array, the full-resolution
   bitmap is never allocated, and the result is scaled to an exact
   power-of-two, 2:1 size (so that the rendering library does not have
   to resize it afterwards).  */
object PanoramaImageDecoder {

    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        data class InvalidAspect(val width: Int, val height: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    /* Decode the image, opening a fresh input stream for each pass via
       sourceFactory.  This allows streaming from content providers,
       assets, temp files, etc., with the same code path.  */
    fun decode(sourceFactory: () -> InputStream?): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        sourceFactory()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return Result.Failure(describeNonImageContent(sourceFactory))
        }

        /* If the original panorama had an exact 2:1 aspect ratio but it
           was scaled by a floating-point factor and then the dimensions
           rounded, it may be that the image we have is off by up to two
           pixels.  Accept this, as it is a tiny divergence (that won't
           cause any display issues) and it is for a potentially valid
           reason in practice.  */
        val aspectDifference = width - 2 * height
        if (aspectDifference > 2 || aspectDifference < -2) {
            return Result.InvalidAspect(width, height)
        }

        /* Subsample so that the decoded bitmap is no wider than the
           maximum texture size.  This avoids allocating a huge
           full-resolution bitmap.  */
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(width, PLConstants.kTextureMaxSize)
        }
        val bitmap = sourceFactory()?.use { BitmapFactory.decodeStream(it, null, options) }
        if (bitmap == null) {
            return Result.Failure("Failed to decode image.")
        }

        return Result.Success(scaleToPowerOfTwo(bitmap))
    }

    /* Give a more helpful error message when the content cannot be
       decoded as an image.  In particular, a downloaded URL that points
       to a web page (rather than a direct image file) is a common case.  */
    private fun describeNonImageContent(sourceFactory: () -> InputStream?): String {
        val head = ByteArray(1024)
        val count = sourceFactory()?.use { it.read(head, 0, head.size) } ?: -1
        if (count <= 0) {
            return "The image file is empty."
        }
        val sample = String(head, 0, count, Charsets.ISO_8859_1).trimStart().lowercase()
        if (sample.startsWith("<!doctype html") || sample.startsWith("<html") ||
            sample.startsWith("<head") || sample.startsWith("<body") || sample.startsWith("<meta")) {
            return "The file does not appear to be an image (it looks like a web page).\nThe link must point directly to an image file."
        }
        return "The file does not appear to be a valid image."
    }

    /* Largest power of two such that width / sample <= maxSize.  */
    private fun computeSampleSize(width: Int, maxSize: Int): Int {
        var sample = 1
        while ((width + sample - 1) / sample > maxSize) {
            sample *= 2
        }
        return sample
    }

    /* Scale to the next power-of-two 2:1 size, which is a no-op when
       the bitmap already has those dimensions.  */
    private fun scaleToPowerOfTwo(bitmap: Bitmap): Bitmap {
        val targetWidth = nextPowerOfTwo(bitmap.width)
        val targetHeight = targetWidth / 2
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            return bitmap
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        bitmap.recycle()
        return scaled
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var power = 1
        while (power < value) {
            power = power shl 1
        }
        return power
    }
}
