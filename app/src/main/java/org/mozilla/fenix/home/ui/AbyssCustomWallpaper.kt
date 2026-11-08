/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Helpers for the user's own homepage wallpaper, stored in the app's private files folder.
 * The decoded picture is kept in memory so the homepage can draw it on its first frame.
 */
object AbyssWallpaper {
    const val FILE_NAME = "abyss_custom_wallpaper.jpg"
    private const val MAX_SIDE = 1920

    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap? = null

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    private fun keyOf(file: File): String = file.path + ":" + file.lastModified() + ":" + file.length()

    /** Returns the picture only if it is already decoded in memory and still current. */
    @Synchronized
    fun cached(file: File): Bitmap? = if (cachedKey == keyOf(file)) cachedBitmap else null

    /** Decodes the picture (slow, call off the main thread) and remembers it. */
    @Synchronized
    fun load(file: File): Bitmap? {
        if (!file.exists()) {
            cachedKey = null
            cachedBitmap = null
            return null
        }
        val key = keyOf(file)
        if (cachedKey == key) return cachedBitmap
        val bmp = decodeScaled(file.path)
        cachedKey = key
        cachedBitmap = bmp
        return bmp
    }

    /** Decodes the image at [path], shrinking it so big photos don't run out of memory. */
    fun decodeScaled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
    }
}

/**
 * Draws the user's custom wallpaper full screen. Draws nothing if the file doesn't exist.
 *
 * @param file The saved wallpaper image.
 * @param version Change this number to force a reload after the image is replaced.
 */
@Composable
fun AbyssCustomWallpaper(file: File, version: Int) {
    var bitmap by remember(file.path, version) { mutableStateOf(AbyssWallpaper.cached(file)) }

    LaunchedEffect(file.path, version) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { AbyssWallpaper.load(file) }
        }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}