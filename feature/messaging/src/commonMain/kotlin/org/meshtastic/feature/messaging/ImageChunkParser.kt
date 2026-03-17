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
package org.meshtastic.feature.messaging

import org.meshtastic.core.model.Message

internal data class ImageChunk(
    val imageId: String,
    val partIndex: Int,
    val totalParts: Int,
    val payload: String,
)

private val compactImageChunkRegex = Regex("^IMG:([^|]+)\\|(\\d+)/(\\d+)\\|([\\s\\S]+)$")
private val legacyImageChunkRegex = Regex("^IMG:([^|]+)\\|PART:(\\d+)/(\\d+)\\|DATA:([\\s\\S]+)$")

internal fun parseImageChunk(text: String): ImageChunk? {
    val normalizedText = text.trim()
    val match =
        compactImageChunkRegex.matchEntire(normalizedText)
            ?: legacyImageChunkRegex.matchEntire(normalizedText)
            ?: return null
    val imageId = match.groupValues[1]
    val partIndex = match.groupValues[2].toIntOrNull() ?: return null
    val totalParts = match.groupValues[3].toIntOrNull() ?: return null
    val payload = match.groupValues[4].trim()
    if (imageId.isBlank() || partIndex <= 0 || totalParts <= 0 || partIndex > totalParts || payload.isEmpty()) {
        return null
    }
    return ImageChunk(imageId = imageId, partIndex = partIndex, totalParts = totalParts, payload = payload)
}

internal fun Message.isImageChunkMessage(): Boolean = parseImageChunk(text) != null