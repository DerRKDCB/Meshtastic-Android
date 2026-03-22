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
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import co.touchlab.kermit.Logger
import org.meshtastic.core.model.DecodedPrivateAppPayload
import org.meshtastic.core.model.PrivateAppPayloadType

internal fun savePrivateFileAttachmentToDevice(
    context: Context,
    payload: DecodedPrivateAppPayload,
    logTag: String = "MsgAttachmentSaver",
): Uri? {
    if (payload.type != PrivateAppPayloadType.File) {
        return null
    }
    val fileName = payload.fileName?.takeIf { it.isNotBlank() } ?: "meshtastic_attachment_${System.currentTimeMillis()}"
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Meshtastic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
        Logger.withTag(logTag).w { "savePrivateFileAttachmentToDevice failed: insert returned null fileName=$fileName" }
        return null
    }

    val savedUri = runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            output.write(payload.payload)
        } ?: error("openOutputStream returned null")
        uri
    }.getOrElse { throwable ->
        Logger.withTag(logTag).e(throwable) { "savePrivateFileAttachmentToDevice exception: fileName=$fileName uri=$uri" }
        resolver.delete(uri, null, null)
        null
    }

    if (savedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val pendingValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, pendingValues, null, null)
    }
    if (savedUri == null) {
        resolver.delete(uri, null, null)
    }
    Logger.withTag(logTag).d { "savePrivateFileAttachmentToDevice result: success=${savedUri != null} fileName=$fileName uri=$uri bytes=${payload.payload.size}" }
    return savedUri
}

internal fun openPrivateFileAttachmentWithApp(context: Context, payload: DecodedPrivateAppPayload): Boolean {
    val uri = savePrivateFileAttachmentToDevice(context, payload) ?: return false
    val fileName = payload.fileName.orEmpty()
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse { throwable ->
        Logger.withTag("MsgAttachmentSaver").e(throwable) { "openPrivateFileAttachmentWithApp failed: fileName=$fileName uri=$uri" }
        false
    }
}
