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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ChunkedPayload
import org.meshtastic.core.model.DecodedPrivateAppPayload
import org.meshtastic.core.model.PrivateAppPayloadType
import org.meshtastic.core.model.decodePrivateAppPayload
import org.meshtastic.core.model.encodePrivateAppPayload

internal const val IMAGE_CHUNK_PAYLOAD_BYTES = 150
internal const val MAX_AUTO_IMAGE_JPEG_QUALITY = 90

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
    payloadBytes: ByteArray,
    zipCompressionEnabled: Boolean = true,
    chunkPayloadBytes: Int = IMAGE_CHUNK_PAYLOAD_BYTES,
): List<ByteArray> {
    val zippedPayloadBytes = maybeGzip(payloadBytes, zipCompressionEnabled)
    if (zippedPayloadBytes.isEmpty()) {
        return emptyList()
    }
    val totalParts = ((zippedPayloadBytes.size + chunkPayloadBytes - 1) / chunkPayloadBytes).coerceAtLeast(1)
    val payloadId = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    var partIndex = 1
    while (offset < zippedPayloadBytes.size) {
        val nextOffset = (offset + chunkPayloadBytes).coerceAtMost(zippedPayloadBytes.size)
        val packet =
            ChunkedPayload(
                payload_id = payloadId,
                chunk_count = totalParts,
                chunk_index = partIndex,
                payload_chunk = zippedPayloadBytes.copyOfRange(offset, nextOffset).toByteString(),
            )
        chunks += ChunkedPayload.ADAPTER.encode(packet)
        offset = nextOffset
        partIndex++
    }
    return chunks
}

internal fun decodePrivateAppPayloadFromOutgoingChunkedPayloads(chunks: List<ByteArray>): DecodedPrivateAppPayload? {
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
    val decodedBytes = maybeGunzip(payload)
    return decodePrivateAppPayload(decodedBytes)
}

internal fun decodeBitmapFromOutgoingChunkedPayloads(chunks: List<ByteArray>): Bitmap? {
    val decodedPayload = decodePrivateAppPayloadFromOutgoingChunkedPayloads(chunks) ?: return null
    if (decodedPayload.type != PrivateAppPayloadType.Image) {
        return null
    }
    val imageBytes = decodedPayload.payload
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

internal fun buildImagePayloadBytes(imageBytes: ByteArray): ByteArray =
    encodePrivateAppPayload(PrivateAppPayloadType.Image, imageBytes)

internal fun buildPositionPayloadBytes(positionBytes: ByteArray): ByteArray =
    encodePrivateAppPayload(PrivateAppPayloadType.Position, positionBytes)

internal fun buildFilePayloadBytes(fileName: String, fileBytes: ByteArray): ByteArray =
    encodePrivateAppPayload(PrivateAppPayloadType.File, fileBytes, fileName = fileName)
