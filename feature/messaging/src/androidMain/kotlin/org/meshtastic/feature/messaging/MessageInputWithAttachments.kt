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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.net.Uri as AndroidUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.hasLocationPermission
import org.meshtastic.core.common.util.HomoglyphCharacterStringTransformer
import org.meshtastic.core.model.Channel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.attach_file
import org.meshtastic.core.resources.attach_image
import org.meshtastic.core.resources.attachment
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.message_input_label
import org.meshtastic.core.resources.position
import org.meshtastic.core.resources.send
import org.meshtastic.core.resources.type_a_message
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.core.ui.util.findActivity
import org.meshtastic.feature.messaging.component.MESSAGE_CHARACTER_LIMIT_BYTES
import org.meshtastic.feature.messaging.image.ImageAdjustmentDialog
import org.meshtastic.proto.Config
import java.nio.charset.StandardCharsets

private const val ROUNDED_CORNER_PERCENT = 100
private const val MAX_LINES = 3
private val locationUiLogger = Logger.withTag("MsgLocationDebug")

@Suppress("LongMethod")
@Composable
internal fun MessageInput(
    isEnabled: Boolean,
    isHomoglyphEncodingEnabled: Boolean,
    loraConfig: Config.LoRaConfig,
    textFieldState: TextFieldState,
    modifier: Modifier = Modifier,
    isSendingChunks: Boolean = false,
    maxByteSize: Int = MESSAGE_CHARACTER_LIMIT_BYTES,
    onSendMessage: () -> Unit,
    viewModel: MessageViewModel?,
    contactKey: String,
    onStopSendingChunks: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentTextRaw = textFieldState.text.toString()
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showPositionDialog by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    var openSettingsOnPermissionDenied by remember { mutableStateOf(false) }

    fun refreshLocationPermissionState(reason: String) {
        val previousPermission = hasLocationPermission
        hasLocationPermission = context.hasLocationPermission()
        locationUiLogger.i {
            "refreshLocationPermissionState reason=$reason previous=$previousPermission " +
                "current=$hasLocationPermission showPositionDialog=$showPositionDialog"
        }
    }

    val appSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            locationUiLogger.i {
                "Returned from app settings. showPositionDialog=$showPositionDialog " +
                    "openSettingsOnPermissionDenied=$openSettingsOnPermissionDenied"
            }
            refreshLocationPermissionState(reason = "app_settings_result")
        }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    locationUiLogger.i {
                        "Lifecycle ON_RESUME observed in MessageInput. showPositionDialog=$showPositionDialog"
                    }
                    refreshLocationPermissionState(reason = "lifecycle_on_resume")
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openAppSettingsForLocation() {
        locationUiLogger.w {
            "Opening app settings for location permission management. hasLocationPermission=$hasLocationPermission"
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = AndroidUri.fromParts("package", context.packageName, null)
        }
        appSettingsLauncher.launch(intent)
    }

    fun isLocationPermissionPermanentlyDenied(): Boolean {
        val activity = context.findActivity() ?: return false
        val fineDenied =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        val coarseDenied =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        val fineRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        val permanentlyDenied = fineDenied && coarseDenied && !fineRationale && !coarseRationale
        locationUiLogger.i {
            "isLocationPermissionPermanentlyDenied: permanentlyDenied=$permanentlyDenied " +
                "fineDenied=$fineDenied coarseDenied=$coarseDenied " +
                "fineRationale=$fineRationale coarseRationale=$coarseRationale"
        }
        return permanentlyDenied
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            val previousPermission = hasLocationPermission
            hasLocationPermission = grantResults.any { (_, granted) -> granted } || context.hasLocationPermission()
            locationUiLogger.i {
                "Permission launcher callback: grantResults=$grantResults previousHasLocationPermission=$previousPermission " +
                    "finalHasLocationPermission=$hasLocationPermission openSettingsOnPermissionDenied=$openSettingsOnPermissionDenied"
            }
            if (!hasLocationPermission && openSettingsOnPermissionDenied) {
                if (isLocationPermissionPermanentlyDenied()) {
                    locationUiLogger.w { "Permission denied and permanently denied. Redirecting to app settings." }
                    openAppSettingsForLocation()
                } else {
                    locationUiLogger.i { "Permission denied but not permanently denied. Staying in-app." }
                }
            }
            openSettingsOnPermissionDenied = false
        }

    val currentText =
        if (isHomoglyphEncodingEnabled) {
            HomoglyphCharacterStringTransformer.optimizeUtf8StringWithHomoglyphs(currentTextRaw)
        } else {
            currentTextRaw
        }

    val currentByteLength =
        remember(currentText) {
            currentText.toByteArray(StandardCharsets.UTF_8).size
        }

    val isOverLimit = currentByteLength > maxByteSize
    val canSend = !isOverLimit && currentText.isNotEmpty() && isEnabled

    val currentLocation by
        if (showPositionDialog && hasLocationPermission && viewModel != null) {
            viewModel.currentLocation.collectAsStateWithLifecycle(initialValue = null)
        } else {
            remember { mutableStateOf<Location?>(null) }
        }

    LaunchedEffect(showPositionDialog, hasLocationPermission) {
        locationUiLogger.i {
            "Position dialog state changed: showPositionDialog=$showPositionDialog hasLocationPermission=$hasLocationPermission"
        }
    }

    LaunchedEffect(showPositionDialog, currentLocation) {
        if (showPositionDialog) {
            locationUiLogger.i {
                "Current location update while dialog visible: hasLocation=${currentLocation != null} " +
                    "lat=${currentLocation?.latitude} lon=${currentLocation?.longitude}"
            }
        }
    }

    LaunchedEffect(isEnabled, isSendingChunks) {
        if (!isEnabled && !isSendingChunks) {
            showAttachmentMenu = false
        }
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

    fun openAttachmentMenu() {
        showAttachmentMenu = true
    }

    fun dismissAttachmentMenu() {
        showAttachmentMenu = false
    }

    fun openPositionDialog() {
        showPositionDialog = true
    }

    fun dismissPositionDialog() {
        locationUiLogger.i { "Position dialog dismissed." }
        showPositionDialog = false
    }

    fun clearSelectedImage() {
        selectedImageUri = null
    }

    fun clearSelectedFile() {
        selectedFileUri = null
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
    }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedFileUri = uri
    }

    OutlinedTextField(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        state = textFieldState,
        lineLimits = TextFieldLineLimits.MultiLine(1, MAX_LINES),
        label = { Text(stringResource(Res.string.message_input_label)) },
        enabled = isEnabled,
        shape = RoundedCornerShape(ROUNDED_CORNER_PERCENT.toFloat()),
        isError = isOverLimit,
        placeholder = { Text(stringResource(Res.string.type_a_message)) },
        leadingIcon = {
            if (isSendingChunks) {
                IconButton(onClick = ::openAttachmentMenu, enabled = true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = {
                        if (isEnabled) {
                            openAttachmentMenu()
                        }
                    },
                    enabled = isEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringResource(Res.string.attachment),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = {
            if (isEnabled) {
                Text(
                    text = "$currentByteLength/$maxByteSize",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isOverLimit) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        },
        trailingIcon = {
            IconButton(onClick = { if (canSend) onSendMessage() }, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.Send,
                    contentDescription = stringResource(Res.string.send),
                )
            }
        },
    )

    DropdownMenu(
        expanded = showAttachmentMenu && (isEnabled || isSendingChunks),
        onDismissRequest = ::dismissAttachmentMenu,
    ) {
        if (isSendingChunks) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.cancel)) },
                onClick = {
                    dismissAttachmentMenu()
                    onStopSendingChunks()
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_image)) },
                onClick = {
                    dismissAttachmentMenu()
                    imagePickerLauncher.launch("image/*")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.position)) },
                onClick = {
                    dismissAttachmentMenu()
                    locationUiLogger.i {
                        "Position menu clicked. hasLocationPermission=$hasLocationPermission " +
                            "showPositionDialog=$showPositionDialog"
                    }
                    if (!hasLocationPermission) {
                        locationUiLogger.i { "Position menu branch=request_permission_then_show_dialog" }
                        locationPermissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    } else {
                        locationUiLogger.i { "Position menu branch=permission_already_granted_show_dialog" }
                    }
                    openPositionDialog()
                    locationUiLogger.i { "Position dialog opened from menu." }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_file)) },
                onClick = {
                    dismissAttachmentMenu()
                    filePickerLauncher.launch("*/*")
                },
            )
        }
    }

    selectedFileUri?.let { uri ->
        FileAdjustmentDialog(
            fileUri = uri,
            loraConfig = loraConfig,
            onSend = { chunks, delayMillis ->
                if (viewModel != null) {
                    viewModel.sendChunkedPayloadChunks(
                        chunks = chunks,
                        contactKey = contactKey,
                        delayMillis = delayMillis,
                    )
                }
                clearSelectedFile()
            },
            onCancel = ::clearSelectedFile,
        )
    }

    if (showPositionDialog) {
        PositionShareDialog(
            currentLocation = currentLocation,
            canUseCurrentLocation = hasLocationPermission,
            onRequestCurrentLocationPermission = {
                openSettingsOnPermissionDenied = true
                locationUiLogger.i {
                    "Position dialog requested location permission. openSettingsOnPermissionDenied=true " +
                        "hasLocationPermission=$hasLocationPermission"
                }
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            onSend = { payloadBytes ->
                locationUiLogger.i {
                    "Position dialog send pressed. payloadSize=${payloadBytes.size} hasCurrentLocation=${currentLocation != null}"
                }
                if (viewModel != null) {
                    viewModel.sendPrivateAppPayload(payload = payloadBytes, contactKey = contactKey)
                }
            },
            onCancel = ::dismissPositionDialog,
        )
    }

    selectedImageUri?.let { uri ->
        ImageAdjustmentDialog(
            imageUri = uri,
            loraConfig = loraConfig,
            onSend = { chunks, delayMillis ->
                if (viewModel != null) {
                    viewModel.sendChunkedPayloadChunks(
                        chunks = chunks,
                        contactKey = contactKey,
                        delayMillis = delayMillis,
                    )
                }
                clearSelectedImage()
            },
            onCancel = ::clearSelectedImage,
        )
    }
}

@PreviewLightDark
@Composable
private fun MessageInputPreview() {
    AppTheme {
        Surface {
            Column(modifier = Modifier.padding(8.dp)) {
                val dummyContactKey = "preview"
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("Hello"),
                    onSendMessage = {},
                    viewModel = null,
                    contactKey = dummyContactKey,
                )
                Spacer(Modifier.size(16.dp))
                MessageInput(
                    isEnabled = false,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("Disabled"),
                    onSendMessage = {},
                    viewModel = null,
                    contactKey = dummyContactKey,
                )
                Spacer(Modifier.size(16.dp))
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState =
                        rememberTextFieldState(
                            "A very long message that might exceed the byte limit " +
                                "and cause an error state display for the user to see clearly.",
                        ),
                    onSendMessage = {},
                    maxByteSize = 50,
                    viewModel = null,
                    contactKey = dummyContactKey,
                )
                Spacer(Modifier.size(16.dp))
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("こんにちは世界"),
                    onSendMessage = {},
                    maxByteSize = 10,
                    viewModel = null,
                    contactKey = dummyContactKey,
                )
            }
        }
    }
}

