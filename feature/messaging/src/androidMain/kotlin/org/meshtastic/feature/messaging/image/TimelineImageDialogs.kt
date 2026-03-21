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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.decode_image_save_failed
import org.meshtastic.core.resources.decode_image_saved
import org.meshtastic.core.resources.save

private val timelineImageLogger = Logger.withTag("MsgTimelineImage")

@Composable
internal fun ExpandedTimelineImageDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var saveResultMessageRes by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Expanded image",
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                )

                saveResultMessageRes?.let { messageRes ->
                    Text(text = stringResource(messageRes))
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        saveBitmapToGallery(context, bitmap, imageId = null, logTag = "MsgTimelineImage")
                                    }
                                    timelineImageLogger.d {
                                        "expanded image save requested: success=$success width=${bitmap.width} height=${bitmap.height}"
                                    }
                                    saveResultMessageRes =
                                        if (success) {
                                            Res.string.decode_image_saved
                                        } else {
                                            Res.string.decode_image_save_failed
                                        }
                                }
                            },
                        ) {
                            Text(stringResource(Res.string.save))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(Res.string.close))
                        }
                    }
                }
            }
        }
    }
}
