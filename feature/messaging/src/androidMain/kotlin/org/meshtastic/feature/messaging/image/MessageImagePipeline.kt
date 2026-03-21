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
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.Message
import org.meshtastic.feature.messaging.ImageChunk
import org.meshtastic.feature.messaging.parseImageChunk
import org.meshtastic.proto.ChunkedPayload

internal const val IMAGE_HISTORY_SCAN_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
internal const val IMAGE_CHUNK_PAYLOAD_BYTES = 150
internal const val MAX_AUTO_IMAGE_JPEG_QUALITY = 90

internal data class ImageChunkScanResult(
    val partsByIndex: Map<Int, String>,
    val totalParts: Int,
)

internal fun scanImageChunks(
    seedChunk: ImageChunk,
    messages: List<Message>,
    onProgress: (foundChunks: Int, totalChunks: Int) -> Unit,
): ImageChunkScanResult {
    var totalParts = seedChunk.totalParts
    val partsByIndex = mutableMapOf(seedChunk.partIndex to seedChunk.payload)
    var earliestFoundChunkTime: Long? = null
    var searchLowerBoundInclusive = Long.MIN_VALUE

    onProgress(partsByIndex.size, totalParts)

    for (historyMessage in messages) {
        if (earliestFoundChunkTime != null && historyMessage.receivedTime < searchLowerBoundInclusive) {
            break
        }

        val parsedChunk = parseImageChunk(historyMessage.text) ?: continue
        if (parsedChunk.imageId != seedChunk.imageId) continue

        if (parsedChunk.totalParts > totalParts) {
            totalParts = parsedChunk.totalParts
        }

        if (earliestFoundChunkTime == null || historyMessage.receivedTime < earliestFoundChunkTime) {
            earliestFoundChunkTime = historyMessage.receivedTime
            searchLowerBoundInclusive = historyMessage.receivedTime - IMAGE_HISTORY_SCAN_WINDOW_MILLIS
        }

        val wasAdded = partsByIndex.putIfAbsent(parsedChunk.partIndex, parsedChunk.payload) == null
        if (wasAdded) {
            onProgress(partsByIndex.size, totalParts)
        }

        if (partsByIndex.size >= totalParts) {
            break
        }
    }

    return ImageChunkScanResult(partsByIndex = partsByIndex, totalParts = totalParts)
}

internal fun decodeBitmapFromChunks(partsByIndex: Map<Int, String>, totalParts: Int): Bitmap? {
    if (totalParts <= 0 || partsByIndex.isEmpty()) {
        return null
    }
    if ((1..totalParts).any { partIndex -> !partsByIndex.containsKey(partIndex) }) {
        return null
    }
    val payload =
        (1..totalParts)
            .mapNotNull { partIndex -> partsByIndex[partIndex] }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), "")
    if (payload.isEmpty()) {
        return null
    }
    val payloadBytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    val imageBytes = maybeGunzip(payloadBytes)
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun maybeGzip(inputBytes: ByteArray, isEnabled: Boolean): ByteArray {
    if (!isEnabled) {
        return inputBytes
    }
    return runCatching {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(inputBytes)
        }
        outputStream.toByteArray()
    }.getOrDefault(inputBytes)
}

private fun maybeGunzip(inputBytes: ByteArray): ByteArray {
    if (inputBytes.size < 2) return inputBytes
    val isGzip = inputBytes[0] == 0x1f.toByte() && inputBytes[1] == 0x8b.toByte()
    if (!isGzip) {
        return inputBytes
    }
    return runCatching {
        ByteArrayInputStream(inputBytes).use { byteInput ->
            GZIPInputStream(byteInput).use { gzipInput ->
                gzipInput.readBytes()
            }
        }
    }.getOrDefault(inputBytes)
}

internal fun buildChunkedPayloadPackets(
    jpegBytes: ByteArray,
    zipCompressionEnabled: Boolean = true,
    chunkPayloadBytes: Int = IMAGE_CHUNK_PAYLOAD_BYTES,
): List<ByteArray> {
    val payloadBytes = maybeGzip(jpegBytes, zipCompressionEnabled)
    if (payloadBytes.isEmpty()) {
        return emptyList()
    }
    val totalParts = ((payloadBytes.size + chunkPayloadBytes - 1) / chunkPayloadBytes).coerceAtLeast(1)
    val payloadId = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    var partIndex = 1
    while (offset < payloadBytes.size) {
        val nextOffset = (offset + chunkPayloadBytes).coerceAtMost(payloadBytes.size)
        val packet =
            ChunkedPayload(
                payload_id = payloadId,
                chunk_count = totalParts,
                chunk_index = partIndex,
                payload_chunk = payloadBytes.copyOfRange(offset, nextOffset).toByteString(),
            )
        chunks += ChunkedPayload.ADAPTER.encode(packet)
        offset = nextOffset
        partIndex++
    }
    return chunks
}

internal fun decodeBitmapFromOutgoingChunkedPayloads(chunks: List<ByteArray>): Bitmap? {
    if (chunks.isEmpty()) {
        return null
    }
    val decodedChunks =
        chunks.mapNotNull { chunkBytes ->
            runCatching { ChunkedPayload.ADAPTER.decode(chunkBytes.toByteString()) }.getOrNull()
        }
    if (decodedChunks.size != chunks.size) {
        return null
    }

    val firstChunk = decodedChunks.firstOrNull() ?: return null
    val payloadId = firstChunk.payload_id
    val totalParts = firstChunk.chunk_count
    val partsByIndex = mutableMapOf<Int, ByteArray>()

    decodedChunks.forEach { chunk ->
        if (chunk.payload_id != payloadId || chunk.chunk_count != totalParts) {
            return null
        }
        partsByIndex[chunk.chunk_index] = chunk.payload_chunk.toByteArray()
    }

    val payload =
        (1..totalParts)
            .mapNotNull { partIndex -> partsByIndex[partIndex] }
            .fold(ByteArrayOutputStream()) { stream, part ->
                stream.apply { write(part) }
            }
            .toByteArray()
    if (payload.isEmpty()) {
        return null
    }
    val imageBytes = maybeGunzip(payload)
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
