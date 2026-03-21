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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.meshtastic.core.model.Message
import org.meshtastic.feature.messaging.parseImageChunk

internal suspend fun decodeImageFromTimelineMessage(
    messageText: String,
    loadMessages: suspend () -> List<Message>,
    initialState: DecodeImageUiState,
    setState: (DecodeImageUiState) -> Unit,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var uiState = initialState

    fun update(newState: DecodeImageUiState) {
        uiState = newState
        setState(newState)
    }

    val seedChunk = parseImageChunk(messageText)
    if (seedChunk == null) {
        update(
            DecodeImageUiState(
                visible = true,
                isSearching = false,
                error = DecodeImageError.DecodeFailed,
            ),
        )
        return
    }

    update(
        DecodeImageUiState(
            visible = true,
            imageId = seedChunk.imageId,
            foundChunks = 1,
            totalChunks = seedChunk.totalParts,
            isSearching = true,
        ),
    )

    runCatching {
        val allMessages = loadMessages()
        val scanResult =
            scanImageChunks(
                seedChunk = seedChunk,
                messages = allMessages,
                onProgress = { found, total ->
                    update(
                        uiState.copy(
                            foundChunks = found,
                            totalChunks = total,
                            isSearching = true,
                            error = null,
                        ),
                    )
                },
            )

        val foundChunks = scanResult.partsByIndex.size
        val totalChunks = scanResult.totalParts
        val missingCount = (1..totalChunks).count { index -> !scanResult.partsByIndex.containsKey(index) }

        if (foundChunks == 0) {
            update(
                uiState.copy(
                    isSearching = false,
                    decodedBitmap = null,
                    error = DecodeImageError.NoMatchingChunks,
                    foundChunks = foundChunks,
                    totalChunks = totalChunks,
                ),
            )
            return@runCatching
        }

        if (missingCount > 0) {
            update(
                uiState.copy(
                    isSearching = false,
                    decodedBitmap = null,
                    error = DecodeImageError.MissingChunks(foundChunks, totalChunks),
                    foundChunks = foundChunks,
                    totalChunks = totalChunks,
                ),
            )
            return@runCatching
        }

        val decodedBitmap =
            withContext(decodeDispatcher) {
                decodeBitmapFromChunks(
                    partsByIndex = scanResult.partsByIndex,
                    totalParts = scanResult.totalParts,
                )
            }

        update(
            if (decodedBitmap != null) {
                uiState.copy(
                    isSearching = false,
                    decodedBitmap = decodedBitmap,
                    error = null,
                    foundChunks = foundChunks,
                    totalChunks = totalChunks,
                )
            } else {
                uiState.copy(
                    isSearching = false,
                    decodedBitmap = null,
                    error = DecodeImageError.DecodeFailed,
                    foundChunks = foundChunks,
                    totalChunks = totalChunks,
                )
            },
        )
    }.onFailure {
        update(
            uiState.copy(
                isSearching = false,
                decodedBitmap = null,
                error = DecodeImageError.DecodeFailed,
            ),
        )
    }
}
