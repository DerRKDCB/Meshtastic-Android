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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import org.meshtastic.core.model.PrivateAppPayloadType
import org.meshtastic.core.model.decodePrivateAppPayload
import org.meshtastic.core.model.Message
import org.meshtastic.proto.PortNum

internal data class TimelinePrivateImageRenderState(
    val imageByPayloadKey: Map<TimelineImagePayloadKey, TimelinePrivateImageMessageData>,
)

internal data class TimelineImagePayloadKey(
    val senderNum: Int,
    val payloadId: Int,
)

internal data class PrivateImageDecodeCacheEntry(
    val bitmap: Bitmap?,
    val attemptedChunkCount: Int,
)

internal data class TimelinePrivateImageMessageData(
    val bitmap: Bitmap?,
    val availableChunks: Int,
    val totalChunks: Int,
    val attachmentLabel: String? = null,
    val decodedPayload: org.meshtastic.core.model.DecodedPrivateAppPayload? = null,
)

private val timelineImageLogger = Logger.withTag("MsgTimelineImage")
private const val IMAGE_UNAVAILABLE_DECODE_FAILED_LABEL = "Image unavailable (decode failed)"
private const val IMAGE_UNAVAILABLE_INVALID_CHUNKS_LABEL = "Image unavailable (invalid chunks)"
private const val IMAGE_ATTACHMENT_LABEL = "Image"

private fun isGzipPayload(inputBytes: ByteArray): Boolean {
    return inputBytes.size >= 2 && inputBytes[0] == 0x1f.toByte() && inputBytes[1] == 0x8b.toByte()
}

private fun gzipDataOffset(inputBytes: ByteArray): Int? {
    if (!isGzipPayload(inputBytes) || inputBytes.size < 10) {
        return null
    }

    val flags = inputBytes[3].toInt() and 0xFF
    var offset = 10

    if ((flags and 0x04) != 0) {
        if (offset + 2 > inputBytes.size) return null
        val extraLength = (inputBytes[offset].toInt() and 0xFF) or ((inputBytes[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2 + extraLength
    }

    if ((flags and 0x08) != 0) {
        while (offset < inputBytes.size && inputBytes[offset] != 0.toByte()) {
            offset++
        }
        offset++
    }

    if ((flags and 0x10) != 0) {
        while (offset < inputBytes.size && inputBytes[offset] != 0.toByte()) {
            offset++
        }
        offset++
    }

    if ((flags and 0x02) != 0) {
        offset += 2
    }

    return offset.takeIf { it in 0..inputBytes.size }
}

private fun gunzipStrict(inputBytes: ByteArray): ByteArray? {
    if (!isGzipPayload(inputBytes)) {
        timelineImageLogger.d { "gunzipStrict skipped: not-gzip bytes=${inputBytes.size}" }
        return null
    }
    return runCatching {
        ByteArrayInputStream(inputBytes).use { byteInput ->
            GZIPInputStream(byteInput).use { gzipInput ->
                gzipInput.readBytes()
            }
        }
    }.getOrNull().also { output ->
        timelineImageLogger.d { "gunzipStrict result: success=${output != null} inputBytes=${inputBytes.size} outputBytes=${output?.size ?: 0}" }
    }
}

private fun gunzipBestEffortPartial(inputBytes: ByteArray): ByteArray? {
    val dataOffset = gzipDataOffset(inputBytes) ?: return null
    val inflater = Inflater(true)
    return runCatching {
        inflater.setInput(inputBytes, dataOffset, inputBytes.size - dataOffset)
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val inflated = inflater.inflate(buffer)
                if (inflated > 0) {
                    output.write(buffer, 0, inflated)
                } else {
                    break
                }
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull().also {
        timelineImageLogger.d {
            "gunzipBestEffortPartial result: success=${it != null} inputBytes=${inputBytes.size} outputBytes=${it?.size ?: 0} dataOffset=$dataOffset"
        }
        inflater.end()
    }
}

private fun decodeBitmapBestEffort(payloadBytes: ByteArray): Bitmap? {
    if (payloadBytes.isEmpty()) {
        timelineImageLogger.d { "decodeBitmapBestEffort skipped: empty payload" }
        return null
    }

    val candidates = mutableListOf<ByteArray>()
    candidates += payloadBytes

    gunzipStrict(payloadBytes)?.let { candidates += it }
    gunzipBestEffortPartial(payloadBytes)?.let { candidates += it }

    candidates.forEach { candidateBytes ->
        runCatching {
            BitmapFactory.decodeByteArray(candidateBytes, 0, candidateBytes.size)
        }.getOrNull()?.let {
            timelineImageLogger.d {
                "decodeBitmapBestEffort decoded direct: payloadBytes=${payloadBytes.size} candidateBytes=${candidateBytes.size}"
            }
            return it
        }

        val withEoi = candidateBytes + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        runCatching {
            BitmapFactory.decodeByteArray(withEoi, 0, withEoi.size)
        }.getOrNull()?.let {
            timelineImageLogger.d {
                "decodeBitmapBestEffort decoded withEOI: payloadBytes=${payloadBytes.size} candidateBytes=${candidateBytes.size}"
            }
            return it
        }
    }

    timelineImageLogger.d {
        "decodeBitmapBestEffort failed: payloadBytes=${payloadBytes.size} candidates=${candidates.size}"
    }
    return null
}

internal data class LoadedTimelineImageRows(
    val imageByMessageUuid: Map<Long, TimelinePrivateImageMessageData>,
    val hiddenChunkMessageUuids: Set<Long>,
    val attachmentLabelByMessageUuid: Map<Long, String>,
    val attachmentPayloadByMessageUuid: Map<Long, org.meshtastic.core.model.DecodedPrivateAppPayload>,
)

internal data class TimelineImageRowsResult(
    val rows: LoadedTimelineImageRows,
    val onInlineImageClick: (payloadId: Int, senderNum: Int) -> Unit,
)

/**
 * Returns chunk UUIDs that should be deleted when the user deletes [message].
 * For image messages every chunk sharing the same payloadId is included; for regular
 * messages only the single UUID is returned.
 */
internal fun deleteImageUuidsFor(message: Message, imageChunkMessages: List<Message>): List<Long> {
    val payloadId = message.privatePayloadId ?: return listOf(message.uuid)
    val senderNum = message.node.num
    val payloadMessages = imageChunkMessages.filter { it.privatePayloadId == payloadId && it.node.num == senderNum }
    return payloadMessages.map { it.uuid }.ifEmpty { listOf(message.uuid) }
}

/**
 * Builds and memoises the image-row mapping for the timeline.
 *
 * Returns a [LoadedTimelineImageRows] that maps each representative chunk UUID to its
 * decoded image data and the set of sibling chunk UUIDs that should be hidden.
 *
 * The representative UUID per payload is kept stable across recompositions so list keys
 * do not churn when new chunks arrive.
 */
@Composable
internal fun rememberTimelineImageRows(
    contactKey: String,
    displayedMessages: List<Message>,
    imageChunkMessages: List<Message>,
): TimelineImageRowsResult {
    val privateImageDecodeCache = remember(contactKey) { mutableMapOf<String, PrivateImageDecodeCacheEntry>() }
    var expandedImageSelection by remember(contactKey) { mutableStateOf<ExpandedTimelineImageSelection?>(null) }

    val privateImageRenderState by
        remember(imageChunkMessages) {
            derivedStateOf {
                buildPrivateImageRenderState(imageChunkMessages, privateImageDecodeCache)
            }
        }

    val representativeRowUuidByPayload = remember(contactKey) { mutableMapOf<TimelineImagePayloadKey, Long>() }

    val loadedImageRows by
        remember(displayedMessages, privateImageRenderState) {
            derivedStateOf {
                val imageByMessageUuid = mutableMapOf<Long, TimelinePrivateImageMessageData>()
                val hiddenChunkMessageUuids = mutableSetOf<Long>()
                val attachmentLabelByMessageUuid = mutableMapOf<Long, String>()
                val attachmentPayloadByMessageUuid = mutableMapOf<Long, org.meshtastic.core.model.DecodedPrivateAppPayload>()
                val activePayloadKeys = mutableSetOf<TimelineImagePayloadKey>()

                displayedMessages
                    .filter { it.privatePayloadId != null && it.privateChunkIndex != null }
                    .groupBy {
                        TimelineImagePayloadKey(
                            senderNum = it.node.num,
                            payloadId = it.privatePayloadId ?: -1,
                        )
                    }
                    .forEach { (payloadKey, group) ->
                        activePayloadKeys += payloadKey
                        val representative =
                            representativeRowUuidByPayload[payloadKey]
                                ?.let { stableUuid -> group.firstOrNull { it.uuid == stableUuid } }
                                ?: group.minByOrNull { it.privateChunkIndex ?: Int.MAX_VALUE }
                                ?: return@forEach

                        val imageData =
                            privateImageRenderState.imageByPayloadKey[payloadKey]
                                ?: TimelinePrivateImageMessageData(
                                    bitmap = null,
                                    availableChunks = group.count { (it.privateChunkBytes?.isNotEmpty() == true) },
                                    totalChunks =
                                    group.firstOrNull()?.privateChunkCount?.takeIf { it > 0 }
                                        ?: group.size,
                                    attachmentLabel = IMAGE_UNAVAILABLE_INVALID_CHUNKS_LABEL,
                                )

                        representativeRowUuidByPayload[payloadKey] = representative.uuid
                        imageByMessageUuid[representative.uuid] = imageData
                        imageData.attachmentLabel?.let { attachmentLabelByMessageUuid[representative.uuid] = it }
                        imageData.decodedPayload?.let { attachmentPayloadByMessageUuid[representative.uuid] = it }
                        group.filter { it.uuid != representative.uuid }.forEach { hiddenChunkMessageUuids += it.uuid }
                    }

                representativeRowUuidByPayload.keys.retainAll(activePayloadKeys)

                LoadedTimelineImageRows(
                    imageByMessageUuid = imageByMessageUuid,
                    hiddenChunkMessageUuids = hiddenChunkMessageUuids,
                    attachmentLabelByMessageUuid = attachmentLabelByMessageUuid,
                    attachmentPayloadByMessageUuid = attachmentPayloadByMessageUuid,
                )
            }
        }

    TimelineExpandedImagePreviewHost(
        selection = expandedImageSelection,
        privateImageRenderState = privateImageRenderState,
        onDismiss = { expandedImageSelection = null },
    )

    val onInlineImageClick: (Int, Int) -> Unit = remember(contactKey) {
        { payloadId, senderNum ->
            selectExpandedTimelineImage(payloadId = payloadId, senderNum = senderNum) { selection ->
                expandedImageSelection = selection
            }
        }
    }

    return TimelineImageRowsResult(
        rows = loadedImageRows,
        onInlineImageClick = onInlineImageClick,
    )
}

internal fun buildPrivateImageRenderState(
    messages: List<Message>,
    decodeCache: MutableMap<String, PrivateImageDecodeCacheEntry>,
): TimelinePrivateImageRenderState {
    data class ChunkMessage(val message: Message, val payloadId: Int, val index: Int, val count: Int, val bytes: ByteArray)

    val chunkMessages =
        messages.mapNotNull { message ->
            val payloadId = message.privatePayloadId
            val chunkIndex = message.privateChunkIndex
            val chunkCount = message.privateChunkCount
            val chunkBytes = message.privateChunkBytes
            val hasValidChunkMetadata =
                payloadId != null &&
                    chunkIndex != null &&
                    chunkCount != null &&
                    chunkIndex > 0 &&
                    chunkCount > 0 &&
                    chunkIndex <= chunkCount &&
                    chunkBytes != null &&
                    chunkBytes.isNotEmpty()
            if (
                message.dataType == PortNum.PRIVATE_APP.value &&
                    hasValidChunkMetadata
            ) {
                ChunkMessage(message, payloadId, chunkIndex, chunkCount, chunkBytes)
            } else {
                null
            }
        }

    if (chunkMessages.isEmpty()) {
        timelineImageLogger.d { "buildPrivateImageRenderState: no chunk messages in snapshot=${messages.size}" }
        return TimelinePrivateImageRenderState(emptyMap())
    }

    val imageByPayloadKey = mutableMapOf<TimelineImagePayloadKey, TimelinePrivateImageMessageData>()

    chunkMessages.groupBy { TimelineImagePayloadKey(senderNum = it.message.node.num, payloadId = it.payloadId) }
        .forEach { (payloadKey, group) ->
        val expectedCount = group.firstOrNull()?.count ?: return@forEach
        val chunksByIndex =
            group
                .groupBy { it.index }
                .mapValues { (_, duplicateChunks) ->
                    duplicateChunks.maxWithOrNull(
                        compareBy<ChunkMessage>({ it.message.receivedTime }, { it.message.uuid }),
                    ) ?: return@forEach
                }
        val availableChunks = chunksByIndex.size
        val duplicateChunkCount = group.size - availableChunks
        val payloadId = payloadKey.payloadId
        val senderNum = payloadKey.senderNum
        val cacheKey = "$senderNum:$payloadId"
        val cachedEntry = decodeCache[cacheKey]
        val payloadBytes =
            ByteArrayOutputStream().use { output ->
                chunksByIndex.keys.sorted()
                    .mapNotNull { chunkIndex -> chunksByIndex[chunkIndex]?.bytes }
                    .forEach { chunkBytes -> output.write(chunkBytes) }
                output.toByteArray()
            }
        val decompressedPayload = gunzipStrict(payloadBytes) ?: gunzipBestEffortPartial(payloadBytes) ?: payloadBytes
        val decodedPayload = decodePrivateAppPayload(decompressedPayload)
        val liveImageBytes =
            when {
                decodedPayload?.type == PrivateAppPayloadType.Image -> decodedPayload.payload
                decompressedPayload.firstOrNull() == PrivateAppPayloadType.Image.code -> decompressedPayload.drop(1).toByteArray()
                else -> null
            }

        val shouldRetryDecode = cachedEntry == null || availableChunks > cachedEntry.attemptedChunkCount
        val bitmap =
            if (shouldRetryDecode) {
                timelineImageLogger.d {
                    "decode attempt: cacheKey=$cacheKey payloadId=$payloadId availableChunks=$availableChunks expectedCount=$expectedCount duplicates=$duplicateChunkCount cachedAttempted=${cachedEntry?.attemptedChunkCount ?: 0}"
                }
                val decodedBitmap =
                    if (liveImageBytes != null) {
                        decodeBitmapBestEffort(liveImageBytes)
                    } else {
                        null
                    }
                decodedBitmap.also { bitmapResult ->
                    decodeCache[cacheKey] =
                        PrivateImageDecodeCacheEntry(
                            bitmap = bitmapResult,
                            attemptedChunkCount = availableChunks,
                        )
                    timelineImageLogger.d {
                        "decode stored: cacheKey=$cacheKey decoded=${bitmapResult != null} payloadBytes=${payloadBytes.size} availableChunks=$availableChunks expectedCount=$expectedCount duplicates=$duplicateChunkCount type=${decodedPayload?.type}"
                    }
                }
            } else {
                timelineImageLogger.d {
                    "decode cache hit: cacheKey=$cacheKey bitmap=${cachedEntry.bitmap != null} attemptedChunkCount=${cachedEntry.attemptedChunkCount}"
                }
                cachedEntry.bitmap
            }
        val attachmentLabel =
            when (decodedPayload?.type) {
                PrivateAppPayloadType.File -> {
                    val fileName = decodedPayload.fileName
                    val fileSizeText = decodedPayload.fileSize?.let { formatByteCount(it) }
                    when {
                        fileName != null && fileSizeText != null -> "File: $fileName • $fileSizeText"
                        fileName != null -> "File: $fileName"
                        fileSizeText != null -> "File • $fileSizeText"
                        else -> "File"
                    }
                }
                PrivateAppPayloadType.Position -> decodedPayload.position?.let {
                    "Position: ${it.latitude_i ?: 0}, ${it.longitude_i ?: 0}"
                } ?: "Position"
                PrivateAppPayloadType.Image -> "Image"
                null -> null
            }
        val fallbackAttachmentLabel =
            when {
                attachmentLabel != null -> attachmentLabel
                decodedPayload?.type == PrivateAppPayloadType.Image && bitmap == null -> IMAGE_UNAVAILABLE_DECODE_FAILED_LABEL
                decodedPayload?.type == PrivateAppPayloadType.Image -> IMAGE_ATTACHMENT_LABEL
                else -> null
            }

        imageByPayloadKey[payloadKey] =
            TimelinePrivateImageMessageData(
                bitmap = bitmap,
                availableChunks = availableChunks,
                totalChunks = expectedCount,
                attachmentLabel = fallbackAttachmentLabel,
                decodedPayload = decodedPayload,
            )
    }

    timelineImageLogger.d {
        "buildPrivateImageRenderState summary: messages=${messages.size} chunkMessages=${chunkMessages.size} renderedImages=${imageByPayloadKey.size}"
    }

    return TimelinePrivateImageRenderState(imageByPayloadKey)
}

private fun formatByteCount(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format("%.1f %s", value, units[unitIndex])
    }
}
