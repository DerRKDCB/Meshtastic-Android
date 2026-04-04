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
package org.meshtastic.core.data.repository

import org.meshtastic.core.database.entity.PacketEntity
import org.meshtastic.proto.ChunkedPayload
import org.meshtastic.proto.PortNum

private const val IMAGE_CHUNK_HISTORY_PAGE_SIZE = 100

private data class ImagePayloadKey(
    val senderId: String,
    val payloadId: Int,
)

private data class ImageChunkMetadata(
    val key: ImagePayloadKey,
    val chunkIndex: Int,
    val chunkCount: Int,
)

internal suspend fun org.meshtastic.core.database.dao.PacketDao.extendImageChunksForOldestPartialPayload(
    contact: String,
    recentPackets: List<PacketEntity>,
): List<PacketEntity> {
    if (recentPackets.isEmpty()) return emptyList()

    val packets = recentPackets.toMutableList()
    val oldestPartialKey = findOldestPartialPayloadKey(packets) ?: return packets

    var offset = packets.size
    while (!isPayloadComplete(oldestPartialKey, packets)) {
        val nextPage = getImageChunksPage(contact, IMAGE_CHUNK_HISTORY_PAGE_SIZE, offset)
        if (nextPage.isEmpty()) {
            break
        }
        packets += nextPage
        offset += nextPage.size
    }

    return packets
}

private fun findOldestPartialPayloadKey(packets: List<PacketEntity>): ImagePayloadKey? {
    val chunkIndexesByPayload = mutableMapOf<ImagePayloadKey, MutableSet<Int>>()
    val expectedChunkCountByPayload = mutableMapOf<ImagePayloadKey, Int>()

    packets.forEach { packetEntity ->
        packetEntity.getImageChunkMetadataOrNull()?.let { metadata ->
            chunkIndexesByPayload.getOrPut(metadata.key) { mutableSetOf() } += metadata.chunkIndex
            expectedChunkCountByPayload[metadata.key] =
                maxOf(expectedChunkCountByPayload[metadata.key] ?: 0, metadata.chunkCount)
        }
    }

    return packets.asReversed()
        .asSequence()
        .mapNotNull { it.getImageChunkMetadataOrNull()?.key }
        .firstOrNull { key ->
            val expected = expectedChunkCountByPayload[key] ?: return@firstOrNull false
            val available = chunkIndexesByPayload[key]?.size ?: 0
            available in 1 until expected
        }
}

private fun isPayloadComplete(key: ImagePayloadKey, packets: List<PacketEntity>): Boolean {
    val metadata =
        packets
            .asSequence()
            .mapNotNull { it.getImageChunkMetadataOrNull() }
            .filter { it.key == key }
            .toList()

    if (metadata.isEmpty()) {
        return true
    }

    val availableChunks = metadata.map { it.chunkIndex }.toSet().size
    val expectedChunks = metadata.maxOfOrNull { it.chunkCount } ?: return true
    return availableChunks >= expectedChunks
}

private fun PacketEntity.getImageChunkMetadataOrNull(): ImageChunkMetadata? {
    val packetData = packet.data
    if (packetData.dataType != PortNum.PRIVATE_APP.value) {
        return null
    }

    val chunkedPayload =
        packetData.bytes
            ?.let { bytes -> runCatching { ChunkedPayload.ADAPTER.decode(bytes) }.getOrNull() }
            ?: return null
    val payloadId = chunkedPayload.payload_id
    val chunkIndex = chunkedPayload.chunk_index
    val chunkCount = chunkedPayload.chunk_count
    if (payloadId <= 0 || chunkIndex <= 0 || chunkCount <= 0 || chunkIndex > chunkCount) {
        return null
    }

    return ImageChunkMetadata(
        key = ImagePayloadKey(senderId = packetData.from.orEmpty(), payloadId = payloadId),
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
    )
}

