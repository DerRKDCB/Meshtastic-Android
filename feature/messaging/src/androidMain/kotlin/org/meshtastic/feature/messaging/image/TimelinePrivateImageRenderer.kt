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
import co.touchlab.kermit.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
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
)

private val timelineImageLogger = Logger.withTag("MsgTimelineImage")

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
        BitmapFactory.decodeByteArray(candidateBytes, 0, candidateBytes.size)?.let {
            timelineImageLogger.d {
                "decodeBitmapBestEffort decoded direct: payloadBytes=${payloadBytes.size} candidateBytes=${candidateBytes.size}"
            }
            return it
        }

        val withEoi = candidateBytes + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        BitmapFactory.decodeByteArray(withEoi, 0, withEoi.size)?.let {
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
            if (
                message.dataType == PortNum.PRIVATE_APP.value &&
                    payloadId != null &&
                    chunkIndex != null &&
                    chunkCount != null &&
                    chunkBytes != null
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

    chunkMessages.groupBy { it.payloadId }.values.forEach { group ->
        val expectedCount = group.firstOrNull()?.count ?: return@forEach
        val chunksByIndex = group.associateBy { it.index }
        val availableChunks = chunksByIndex.size
        val payloadId = group.firstOrNull()?.payloadId ?: return@forEach
        val senderNum = group.firstOrNull()?.message?.node?.num ?: 0
        val cacheKey = "$senderNum:$payloadId"
        val cachedEntry = decodeCache[cacheKey]

        val shouldRetryDecode = cachedEntry == null || availableChunks > cachedEntry.attemptedChunkCount
        val bitmap =
            if (shouldRetryDecode) {
                timelineImageLogger.d {
                    "decode attempt: cacheKey=$cacheKey payloadId=$payloadId availableChunks=$availableChunks expectedCount=$expectedCount cachedAttempted=${cachedEntry?.attemptedChunkCount ?: 0}"
                }
                val payloadBytes =
                    ByteArrayOutputStream().use { output ->
                        chunksByIndex.keys.sorted()
                            .mapNotNull { chunkIndex -> chunksByIndex[chunkIndex]?.bytes }
                            .forEach { chunkBytes -> output.write(chunkBytes) }
                        output.toByteArray()
                    }
                decodeBitmapBestEffort(payloadBytes).also { decodedBitmap ->
                    decodeCache[cacheKey] =
                        PrivateImageDecodeCacheEntry(
                            bitmap = decodedBitmap,
                            attemptedChunkCount = availableChunks,
                        )
                    timelineImageLogger.d {
                        "decode stored: cacheKey=$cacheKey decoded=${decodedBitmap != null} payloadBytes=${payloadBytes.size} availableChunks=$availableChunks expectedCount=$expectedCount"
                    }
                }
            } else {
                timelineImageLogger.d {
                    "decode cache hit: cacheKey=$cacheKey bitmap=${cachedEntry.bitmap != null} attemptedChunkCount=${cachedEntry.attemptedChunkCount}"
                }
                cachedEntry.bitmap
            }
        imageByPayloadKey[TimelineImagePayloadKey(senderNum = senderNum, payloadId = payloadId)] =
            TimelinePrivateImageMessageData(
                bitmap = bitmap,
                availableChunks = availableChunks,
                totalChunks = expectedCount,
            )
    }

    timelineImageLogger.d {
        "buildPrivateImageRenderState summary: messages=${messages.size} chunkMessages=${chunkMessages.size} renderedImages=${imageByPayloadKey.size}"
    }

    return TimelinePrivateImageRenderState(imageByPayloadKey)
}
