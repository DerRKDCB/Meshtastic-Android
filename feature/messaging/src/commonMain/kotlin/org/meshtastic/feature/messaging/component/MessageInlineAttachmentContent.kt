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
package org.meshtastic.feature.messaging.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.meshtastic.core.model.DecodedPrivateAppPayload
import org.meshtastic.core.model.Message
import org.meshtastic.core.ui.component.AutoLinkText

@Composable
internal fun MessageInlineAttachmentContent(
    message: Message,
    inlineImageBitmap: ImageBitmap?,
    inlineImageChunkInfoText: String?,
    inlineAttachmentLabel: String?,
    inlineAttachmentPayload: DecodedPrivateAppPayload?,
    isPrivateImageChunk: Boolean,
    inSelectionMode: Boolean,
    messageTextColor: androidx.compose.ui.graphics.Color,
    onLongClick: () -> Unit,
    onOpenActions: () -> Unit,
    onInlineImageClick: () -> Unit,
    onInlineAttachmentClick: () -> Unit,
) {
    if (inlineImageBitmap == null && inlineAttachmentLabel == null && !isPrivateImageChunk) {
        AutoLinkText(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = messageTextColor,
        )
    }

    inlineAttachmentLabel?.let { attachmentLabel ->
        Text(
            text = attachmentLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(top = 4.dp).clickable(enabled = inlineAttachmentPayload != null) {
                    onInlineAttachmentClick()
                },
        )
    }

    inlineImageBitmap?.let { imageBitmap ->
        val imageAspectRatio =
            if (imageBitmap.height > 0) {
                imageBitmap.width.toFloat() / imageBitmap.height.toFloat()
            } else {
                1f
            }
        Image(
            bitmap = imageBitmap,
            contentDescription = "Image message",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.padding(top = 4.dp)
                    .widthIn(min = 180.dp, max = 320.dp)
                    .heightIn(min = 140.dp, max = 360.dp)
                    .combinedClickable(
                        onClick = onInlineImageClick,
                        onLongClick = {
                            onLongClick()
                            if (!inSelectionMode) {
                                onOpenActions()
                            }
                        },
                    )
                    .aspectRatio(imageAspectRatio, matchHeightConstraintsFirst = false),
        )
    }

    inlineImageChunkInfoText?.let { chunkInfoText ->
        Text(
            text = chunkInfoText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

