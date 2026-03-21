/*
 * Copyright (c) 2025-2026 Meshtastic LLC
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
package org.meshtastic.feature.messaging.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import co.touchlab.kermit.Logger

internal fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    imageId: String? = null,
    logTag: String = "MsgImagePipeline",
): Boolean {
    val logger = Logger.withTag(logTag)
    val now = System.currentTimeMillis()
    val displayName =
        if (imageId.isNullOrBlank()) {
            "meshtastic_$now.jpg"
        } else {
            "meshtastic_${imageId}_$now.jpg"
        }
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meshtastic")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
        logger.w { "saveBitmapToGallery failed: insert returned null imageId=$imageId" }
        return false
    }
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        } ?: false
    }
        .getOrElse {
            logger.e(it) { "saveBitmapToGallery exception: imageId=$imageId uri=$uri" }
            resolver.delete(uri, null, null)
            false
        }
        .also { success ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, pendingValues, null, null)
            }
            if (!success) {
                resolver.delete(uri, null, null)
            }
            logger.d {
                "saveBitmapToGallery result: success=$success imageId=$imageId uri=$uri width=${bitmap.width} height=${bitmap.height}"
            }
        }
}
