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

import android.text.format.Formatter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.database.entity.NodeEntity.Companion.degD
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.DecodedPrivateAppPayload
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.PrivateAppPayloadType
import org.meshtastic.core.model.Reaction
import org.meshtastic.core.model.decodePrivateAppPayload
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.image_timeline_chunk_progress
import org.meshtastic.core.resources.position
import org.meshtastic.core.ui.util.rememberOpenMap
import org.meshtastic.feature.messaging.component.MessageItem
import org.meshtastic.feature.messaging.image.TimelinePrivateImageMessageData
import org.meshtastic.feature.messaging.image.deleteImageUuidsFor
import org.meshtastic.feature.messaging.image.openPrivateFileAttachmentWithApp
import org.meshtastic.feature.messaging.image.savePrivateFileAttachmentToDevice
import java.util.Locale

@Suppress("LongParameterList")
@Composable
internal fun RenderPagedChatMessageRow(
    message: Message,
    inlineImageData: TimelinePrivateImageMessageData?,
    inlineAttachmentLabel: String?,
    inlineAttachmentPayload: DecodedPrivateAppPayload?,
    state: MessageListPagedState,
    nodeMap: Map<Int, Node>,
    handlers: MessageListHandlers,
    inSelectionMode: Boolean,
    coroutineScope: CoroutineScope,
    haptics: HapticFeedback,
    listState: LazyListState,
    messages: List<Message>,
    onShowStatusDialog: (Message) -> Unit,
    onShowReactions: (List<Reaction>) -> Unit,
    onInlineImageClick: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    showUserName: Boolean,
    hasSamePrev: Boolean,
    hasSameNext: Boolean,
    quickEmojis: List<String>,
) {
    val ourNode = state.ourNode ?: return
    val context = LocalContext.current
    val openMap = rememberOpenMap()
    val positionTitle = stringResource(Res.string.position)
    var fileAttachmentDialog by remember { mutableStateOf<DecodedPrivateAppPayload?>(null) }
    val selected by
        remember(message.uuid, state.selectedIds.value) {
            derivedStateOf { state.selectedIds.value.contains(message.uuid) }
        }
    val node = nodeMap[message.node.num] ?: message.node
    val inlineImageBitmap =
        remember(message.uuid, inlineImageData?.bitmap) {
            inlineImageData?.bitmap?.asImageBitmap()
        }
    val inlineImageChunkInfoText =
        inlineImageData?.let {
            stringResource(Res.string.image_timeline_chunk_progress, it.availableChunks, it.totalChunks)
        }
    val directAttachmentPayload =
        remember(message.uuid, message.privatePayloadBytes) {
            message.privatePayloadBytes?.let { decodePrivateAppPayload(it) }
        }
    val resolvedAttachmentPayload = inlineAttachmentPayload ?: directAttachmentPayload
    val resolvedAttachmentLabel =
        inlineAttachmentLabel ?: directAttachmentPayload?.let { payload ->
            when (payload.type) {
                PrivateAppPayloadType.File -> {
                    val fileSize = payload.fileSize ?: payload.payload.size.toLong()
                    val sizeText = Formatter.formatShortFileSize(context, fileSize)
                    payload.fileName?.let { "File: $it • $sizeText" } ?: "File • $sizeText"
                }
                PrivateAppPayloadType.Position -> payload.position?.let {
                    val latitude = degD(it.latitude_i ?: 0)
                    val longitude = degD(it.longitude_i ?: 0)
                    String.format(Locale.US, "%s: %.6f, %.6f", positionTitle, latitude, longitude)
                } ?: positionTitle
                PrivateAppPayloadType.Image -> "Image"
            }
        }

    MessageItem(
        modifier = modifier,
        node = node,
        ourNode = ourNode,
        message = message,
        inlineImageBitmap = inlineImageBitmap,
        inlineImageChunkInfoText = inlineImageChunkInfoText,
        inlineAttachmentLabel = resolvedAttachmentLabel,
        inlineAttachmentPayload = resolvedAttachmentPayload,
        selected = selected,
        inSelectionMode = inSelectionMode,
        onClick = { if (inSelectionMode) state.selectedIds.toggle(message.uuid) },
        onLongClick = {
            if (inSelectionMode) {
                state.selectedIds.toggle(message.uuid)
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onSelect = { state.selectedIds.toggle(message.uuid) },
        onDelete = { handlers.onDeleteMessages(deleteImageUuidsFor(message, state.imageChunkMessages)) },
        onClickChip = handlers.onClickChip,
        onStatusClick = { onShowStatusDialog(message) },
        onReply = { handlers.onReply(message) },
        emojis = message.emojis,
        showUserName = showUserName,
        sendReaction = { emoji ->
            val hasReacted =
                message.emojis.any { reaction ->
                    (reaction.user.id == ourNode.user.id || reaction.user.id == DataPacket.ID_LOCAL) && reaction.emoji == emoji
                }
            if (!hasReacted) {
                handlers.onSendReaction(emoji, message.packetId)
            }
        },
        onShowReactions = { onShowReactions(message.emojis) },
        onNavigateToOriginalMessage = {
            coroutineScope.launch {
                val targetIndex = messages.indexOfFirst { it.packetId == message.replyId }.takeIf { it != -1 }

                if (targetIndex != null) {
                    listState.animateScrollToItem(index = targetIndex)
                }
            }
        },
        onInlineImageClick = {
            message.privatePayloadId?.let { payloadId ->
                onInlineImageClick(payloadId, message.node.num)
            }
        },
        onInlineAttachmentClick = {
            resolvedAttachmentPayload?.let { payload ->
                when (payload.type) {
                    PrivateAppPayloadType.File -> fileAttachmentDialog = payload
                    PrivateAppPayloadType.Position -> payload.position?.let {
                        openMap(
                            degD(it.latitude_i ?: 0),
                            degD(it.longitude_i ?: 0),
                            positionTitle,
                        )
                    }
                    PrivateAppPayloadType.Image -> Unit
                }
            }
        },
        hasSamePrev = hasSamePrev,
        hasSameNext = hasSameNext,
        quickEmojis = quickEmojis,
    )

    fileAttachmentDialog?.let { payload ->
        val fileName = payload.fileName ?: "attachment"
        val fileSize = payload.fileSize
        val fileSizeText = fileSize?.let { Formatter.formatShortFileSize(context, it) }
        val isComplete = fileSize == null || payload.payload.size.toLong() >= fileSize
        FileAttachmentActionDialog(
            fileNameText = fileName,
            fileSizeText = fileSizeText,
            isComplete = isComplete,
            onSaveToPhone = {
                savePrivateFileAttachmentToDevice(context, payload)
                fileAttachmentDialog = null
            },
            onOpenWithApp = {
                openPrivateFileAttachmentWithApp(context, payload)
                fileAttachmentDialog = null
            },
            onDismiss = { fileAttachmentDialog = null },
        )
    }
}


