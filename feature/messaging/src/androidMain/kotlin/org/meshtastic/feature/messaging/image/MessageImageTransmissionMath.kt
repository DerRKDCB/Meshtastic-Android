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

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.meshtastic.core.model.ChannelOption
import org.meshtastic.core.model.RegionInfo
import org.meshtastic.proto.Config

internal const val MIN_IMAGE_DUTY_CYCLE_PERCENT = 0.1f
internal const val DEFAULT_IMAGE_DUTY_CYCLE_PERCENT = 1.0f
internal const val TRANSMISSION_TIME_SLIDER_STEPS = 10

private data class ModemAirtimeParams(
    val spreadFactor: Int,
    val codingRateDenominator: Int,
)

internal fun maxDutyCyclePercentForRegion(regionCode: Config.LoRaConfig.RegionCode): Float =
    when (regionCode) {
        Config.LoRaConfig.RegionCode.EU_433,
        Config.LoRaConfig.RegionCode.EU_868,
        Config.LoRaConfig.RegionCode.UA_433,
        -> 10.0f

        Config.LoRaConfig.RegionCode.UA_868 -> 1.0f
        else -> 100.0f
    }

private fun modemParamsForPreset(modemPreset: Config.LoRaConfig.ModemPreset): ModemAirtimeParams =
    when (modemPreset) {
        Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> ModemAirtimeParams(spreadFactor = 12, codingRateDenominator = 8)
        Config.LoRaConfig.ModemPreset.LONG_SLOW -> ModemAirtimeParams(spreadFactor = 12, codingRateDenominator = 8)
        Config.LoRaConfig.ModemPreset.LONG_FAST -> ModemAirtimeParams(spreadFactor = 11, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.LONG_MODERATE -> ModemAirtimeParams(spreadFactor = 10, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.LONG_TURBO -> ModemAirtimeParams(spreadFactor = 11, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> ModemAirtimeParams(spreadFactor = 10, codingRateDenominator = 7)
        Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> ModemAirtimeParams(spreadFactor = 9, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_SLOW -> ModemAirtimeParams(spreadFactor = 8, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_FAST -> ModemAirtimeParams(spreadFactor = 7, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_TURBO -> ModemAirtimeParams(spreadFactor = 7, codingRateDenominator = 5)
    }

private fun bandwidthKhz(loraConfig: Config.LoRaConfig): Double {
    if (!loraConfig.use_preset) {
        return when (loraConfig.bandwidth) {
            31 -> 31.25
            62 -> 62.5
            200 -> 203.125
            400 -> 406.25
            800 -> 812.5
            1600 -> 1625.0
            else -> loraConfig.bandwidth.toDouble()
        }
    }

    val baseBandwidthMHz = ChannelOption.from(loraConfig.modem_preset)?.bandwidth ?: ChannelOption.DEFAULT.bandwidth
    val regionScale = if (RegionInfo.fromRegionCode(loraConfig.region)?.wideLora == true) 3.25 else 1.0
    return baseBandwidthMHz.toDouble() * regionScale * 1000.0
}

internal fun estimatePacketAirtimeMillis(payloadBytes: Int, loraConfig: Config.LoRaConfig): Int {
    if (payloadBytes <= 0) {
        return 0
    }

    val modemParams =
        if (loraConfig.use_preset) {
            modemParamsForPreset(loraConfig.modem_preset)
        } else {
            ModemAirtimeParams(
                spreadFactor = loraConfig.spread_factor.coerceIn(7, 12),
                codingRateDenominator = loraConfig.coding_rate.coerceIn(5, 8),
            )
        }

    val sf = modemParams.spreadFactor
    val codingRateTerm = (modemParams.codingRateDenominator - 4).coerceIn(1, 4)
    val bwKhz = bandwidthKhz(loraConfig)
    if (bwKhz <= 0.0) {
        return 0
    }

    val lowDataRateOptimization = if (sf >= 11 && bwKhz <= 125.0) 1 else 0
    val symbolDurationSeconds = (2.0.pow(sf.toDouble())) / (bwKhz * 1000.0)
    val preambleSymbols = 8.0 + 4.25

    val numerator = (8.0 * payloadBytes) - (4.0 * sf) + 28.0 + 16.0
    val denominator = 4.0 * (sf - (2 * lowDataRateOptimization))
    val payloadSymbolSteps = ceil((numerator / denominator).coerceAtLeast(0.0))
    val payloadSymbols = 8.0 + (payloadSymbolSteps * (codingRateTerm + 4))

    val airtimeSeconds = (preambleSymbols + payloadSymbols) * symbolDurationSeconds
    return (airtimeSeconds * 1000.0).roundToInt().coerceAtLeast(1)
}

internal fun interChunkDelayMillisForDutyCycle(
    dutyCyclePercent: Float,
    packetAirtimeMillis: Int,
): Int {
    if (packetAirtimeMillis <= 0) {
        return 0
    }
    val dutyFraction = (dutyCyclePercent / 100f).coerceIn(0.001f, 1f)
    val cycleMillis = packetAirtimeMillis / dutyFraction
    return (cycleMillis - packetAirtimeMillis).roundToInt().coerceAtLeast(0)
}

internal fun estimateTransmissionMillisForChunks(
    chunks: List<ByteArray>,
    dutyCyclePercent: Float,
    loraConfig: Config.LoRaConfig,
): Int {
    if (chunks.isEmpty()) {
        return 0
    }
    val packetPayloadBytes = chunks.maxOfOrNull { it.size } ?: 0
    val packetAirtimeMillis = estimatePacketAirtimeMillis(packetPayloadBytes, loraConfig)
    val delayMillis = interChunkDelayMillisForDutyCycle(dutyCyclePercent, packetAirtimeMillis)
    return (chunks.size * packetAirtimeMillis) + ((chunks.size - 1).coerceAtLeast(0) * delayMillis)
}

internal fun formatDutyCyclePercent(value: Float): String {
    val roundedTenths = (value * 10f).roundToInt() / 10f
    return if (roundedTenths % 1f == 0f) {
        "${roundedTenths.toInt()}%"
    } else {
        "$roundedTenths%"
    }
}

internal fun normalizeTransmissionSliderPosition(seconds: Float, min: Float, max: Float): Float {
    if (max <= min) {
        return 0f
    }
    val normalizedSeconds = ((seconds - min) / (max - min)).coerceIn(0f, 1f)
    return sqrt(normalizedSeconds)
}

internal fun transmissionSecondsFromSliderPosition(position: Float, min: Float, max: Float): Float {
    if (max <= min) {
        return min
    }
    val clampedPosition = position.coerceIn(0f, 1f)
    return min + (clampedPosition * clampedPosition * (max - min))
}

internal fun snapTransmissionSliderPosition(position: Float): Float {
    val stepCount = TRANSMISSION_TIME_SLIDER_STEPS - 1
    if (stepCount <= 0) {
        return position.coerceIn(0f, 1f)
    }
    val snappedStep = (position.coerceIn(0f, 1f) * stepCount).roundToInt().coerceIn(0, stepCount)
    return snappedStep / stepCount.toFloat()
}
