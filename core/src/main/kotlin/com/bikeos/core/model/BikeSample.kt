package com.bikeos.core.model

import kotlinx.serialization.Serializable

/**
 * One reading from the bike, normalised across every sensor source BikeOS supports.
 *
 * @property timestampMs wall-clock time of the reading (epoch millis)
 * @property cadenceRpm crank cadence in revolutions per minute
 * @property powerWatts instantaneous power output in watts
 * @property resistancePct resistance as shown on a Peloton (0-100)
 * @property heartRateBpm heart rate from a paired strap, or null if none is connected
 */
@Serializable
data class BikeSample(
    val timestampMs: Long,
    val cadenceRpm: Float,
    val powerWatts: Float,
    val resistancePct: Float,
    val heartRateBpm: Int? = null,
) {
    /** Estimated road speed in mph, using the Peloton power->speed model. */
    val speedMph: Float get() = com.bikeos.core.metrics.SpeedModel.mphFromPower(powerWatts)

    val isPedaling: Boolean get() = cadenceRpm >= 5f

    companion object {
        fun idle(now: Long) = BikeSample(now, 0f, 0f, 0f, null)
    }
}

enum class Units { IMPERIAL, METRIC }
