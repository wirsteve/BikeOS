package com.bikeos.sensor

import android.os.Parcel
import com.bikeos.core.peloton.ParcelSource
import kotlinx.coroutines.flow.StateFlow

/** Raw reading straight from a bike sensor, before filtering / HR merge. */
data class RawReading(
    val cadenceRpm: Float,
    val powerWatts: Float,
    val resistancePct: Float,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class SensorState { STOPPED, CONNECTING, CONNECTED, NO_DATA, UNAVAILABLE }

data class SensorStatus(
    val state: SensorState,
    val sourceName: String,
    val detail: String? = null,
)

/**
 * A source of live bike data. Implementations must be restartable: start() after stop().
 */
interface SensorSource {
    val name: String
    val status: StateFlow<SensorStatus>
    fun start(onReading: (RawReading) -> Unit)
    fun stop()
}

/** Adapts android.os.Parcel to the platform-neutral decoder in :core. */
class AndroidParcelSource(private val p: Parcel) : ParcelSource {
    override fun readLong() = p.readLong()
    override fun readInt() = p.readInt()
    override fun readFloat() = p.readFloat()
    override fun readString(): String? = p.readString()
    override fun createByteArray(): ByteArray? = p.createByteArray()
    override fun createIntArray(): IntArray? = p.createIntArray()
}
