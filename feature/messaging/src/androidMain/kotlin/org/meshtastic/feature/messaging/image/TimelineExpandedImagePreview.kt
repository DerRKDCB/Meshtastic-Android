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

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.meshtastic.core.model.Message

private val timelineImageLogger = Logger.withTag("MsgTimelineImage")

internal data class ExpandedTimelineImageSelection(
    val payloadId: Int,
    val senderNum: Int,
)

internal fun selectExpandedTimelineImage(
    payloadId: Int,
    senderNum: Int,
    onSelect: (ExpandedTimelineImageSelection) -> Unit,
) {
    timelineImageLogger.d {
        "inline image click: payloadId=$payloadId senderNum=$senderNum"
    }
    onSelect(ExpandedTimelineImageSelection(payloadId = payloadId, senderNum = senderNum))
}

@Composable
internal fun TimelineExpandedImagePreviewHost(
    selection: ExpandedTimelineImageSelection?,
    privateImageRenderState: TimelinePrivateImageRenderState,
    messages: List<Message>,
    onDismiss: () -> Unit,
) {
    val messagesByUuid by remember(messages) { derivedStateOf { messages.associateBy { it.uuid } } }

    val expandedImageBitmap by
        remember(selection, privateImageRenderState, messagesByUuid) {
            derivedStateOf {
                val currentSelection = selection ?: return@derivedStateOf null
                privateImageRenderState.imageByMessageUuid.entries
                    .firstOrNull { (uuid, _) ->
                        val message = messagesByUuid[uuid]
                        message?.privatePayloadId == currentSelection.payloadId && message.node.num == currentSelection.senderNum
                    }
                    ?.value
                    ?.bitmap
            }
        }

    expandedImageBitmap?.let { bitmap ->
        ExpandedTimelineImageDialog(
            bitmap = bitmap,
            onDismiss = onDismiss,
        )
    }
}