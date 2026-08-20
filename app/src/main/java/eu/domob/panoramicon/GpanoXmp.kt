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

import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs

/* Support for reading the Google Photo Sphere XMP metadata ("GPano")
   that describes how a partial panorama image is cropped out of a larger
   equirectangular image.  See:
   https://developers.google.com/streetview/spherical-metadata

   Partial panoramas are created by cutting a crop out of a full 2:1
   equirectangular image, and storing the crop geometry in XMP metadata.
   This allows us to repad the crop back into a full 2:1 panorama.  The
   XMP also describes the original image dimensions, so that images can
   be resized after the XMP was written without breaking the metadata.
   */

object GpanoXmp {

    /* The namespace URI of the Google Photo Sphere XMP properties.  */
    private const val GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/"

    /* The six integer GPano properties that describe the crop.  The GPano
       namespace also contains other, string-typed properties (such as
       ProjectionType or StitchingSoftware), which are ignored.  */
    private val GPANO_INTEGER_TAGS = setOf(
        "FullPanoWidthPixels",
        "FullPanoHeightPixels",
        "CroppedAreaImageWidthPixels",
        "CroppedAreaImageHeightPixels",
        "CroppedAreaLeftPixels",
        "CroppedAreaTopPixels"
    )

    /* Read the GPano metadata from the image via a fresh input stream,
       or return null if there is none (or it is not well-formed).  */
    fun read(sourceFactory: () -> InputStream?): GpanoRaw? {
        val stream = sourceFactory() ?: return null
        return try {
            val xmp = stream.use {
                ExifInterface(it).getAttributeBytes(ExifInterface.TAG_XMP)
            }
            if (xmp == null) {
                return null
            }
            parseXmp(String(xmp, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /* Check whether a rectangle of the given dimensions is consistent
       with being scaled from a rectangle of the base dimensions by some
       floating-point factor, with the dimensions rounded to integers
       afterwards.  The allowed deviation is derived from the maximum
       rounding error of half a pixel per dimension (rounded up).  For a
       2:1 base ratio this reduces to the plain check
       |width - 2*height| <= 2.  */
    fun aspectRatioMatches(width: Int, height: Int, baseWidth: Int, baseHeight: Int): Boolean {
        val tolerance = (baseWidth + baseHeight + 1) / 2
        return abs(width.toLong() * baseHeight - height.toLong() * baseWidth) <= tolerance
    }

    /* Normalize the raw GPano metadata with respect to the actual image
       dimensions, handling the case where the image was resized after the
       XMP metadata was written (in which case the XMP values refer to the
       original, pre-resize image).  Returns null if the metadata is not
       usable, i.e. the image is not a valid panorama.  */
    fun normalizeGpano(raw: GpanoRaw, width: Int, height: Int): Gpano? {
        if (width <= 0 || height <= 0) {
            return null
        }
        if (raw.fullW <= 0 || raw.fullH <= 0 || raw.cropW <= 0 || raw.cropH <= 0 ||
            raw.left < 0 || raw.top < 0) {
            return null
        }

        /* Determine the scale factor applied when the image was resized
           from the XMP-described crop.  If the crop matches the image
           dimensions exactly, no scaling happened.  Otherwise, the actual
           dimensions must be consistent with a resize by a floating-point
           factor with subsequent rounding.  */
        val factor: Double =
            if (raw.cropW == width && raw.cropH == height) {
                1.0
            } else {
                if (!aspectRatioMatches(width, height, raw.cropW, raw.cropH)) {
                    return null
                }
                width.toDouble() / raw.cropW
            }

        fun scale(value: Int): Long = Math.round(factor * value)

        val fullW = scale(raw.fullW)
        val fullH = scale(raw.fullH)
        val left = scale(raw.left)
        val top = scale(raw.top)
        if (fullW !in 1..Int.MAX_VALUE.toLong() || fullH !in 1..Int.MAX_VALUE.toLong() ||
            left !in 0..Int.MAX_VALUE.toLong() || top !in 0..Int.MAX_VALUE.toLong()) {
            return null
        }
        val fw = fullW.toInt()
        val fh = fullH.toInt()
        val l = left.toInt()
        val t = top.toInt()

        /* The full panorama must be a 2:1 equirectangular projection, up
           to the same rounding tolerance as a directly checked image.  */
        if (!aspectRatioMatches(fw, fh, 2, 1)) {
            return null
        }

        /* The crop must lie fully within the full panorama.  */
        if (l.toLong() + width > fw || t.toLong() + height > fh) {
            return null
        }

        return Gpano(fw, fh, width, height, l, t)
    }

    /* Parse the six GPano integer properties from the XMP document.  */
    private fun parseXmp(xmp: String): GpanoRaw? {
        val values = mutableMapOf<String, Long>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        try {
            parser.setInput(xmp.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    /* GPano properties can be serialized either as child
                       elements or as attributes of the description
                       element.  Both forms are valid per the XMP spec.  */
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeNamespace(i) == GPANO_NAMESPACE) {
                            val name = parser.getAttributeName(i)
                            if (name in GPANO_INTEGER_TAGS) {
                                val value = parser.getAttributeValue(i).trim()
                                if (value.isNotEmpty()) {
                                    values[name] = value.toLongOrNull() ?: return null
                                }
                            }
                        }
                    }
                    if (parser.namespace == GPANO_NAMESPACE &&
                        parser.name in GPANO_INTEGER_TAGS) {
                        val name = parser.name
                        val value = parser.nextText().trim()
                        if (value.isNotEmpty()) {
                            values[name] = value.toLongOrNull() ?: return null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            return null
        } catch (_: IOException) {
            return null
        }

        fun intValue(name: String): Int {
            val value = values[name] ?: return -1
            if (value !in 0..Int.MAX_VALUE.toLong()) {
                return -1
            }
            return value.toInt()
        }
        val raw = GpanoRaw(
            fullW = intValue("FullPanoWidthPixels"),
            fullH = intValue("FullPanoHeightPixels"),
            cropW = intValue("CroppedAreaImageWidthPixels"),
            cropH = intValue("CroppedAreaImageHeightPixels"),
            left = intValue("CroppedAreaLeftPixels"),
            top = intValue("CroppedAreaTopPixels")
        )
        return if (raw.fullW > 0 && raw.fullH > 0 && raw.cropW > 0 && raw.cropH > 0 &&
            raw.left >= 0 && raw.top >= 0) {
            raw
        } else {
            null
        }
    }
}

/* Raw GPano metadata as stored in the image file.  All values are in
   pixels of the original, uncropped full panorama.  */
data class GpanoRaw(
    val fullW: Int,
    val fullH: Int,
    val cropW: Int,
    val cropH: Int,
    val left: Int,
    val top: Int
)

/* Normalized GPano metadata, with all values scaled to the actual image
   dimensions.  The crop is snapped to the full image size.  */
data class Gpano(
    val fullW: Int,
    val fullH: Int,
    val cropW: Int,
    val cropH: Int,
    val left: Int,
    val top: Int
)
