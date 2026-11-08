package com.bikeos.core.peloton

/**
 * Minimal view of an android.os.Parcel so the Peloton wire format can be decoded (and unit tested)
 * without depending on the Android framework.
 */
interface ParcelSource {
    fun readLong(): Long
    fun readInt(): Int
    fun readFloat(): Float
    fun readString(): String?
    fun createByteArray(): ByteArray?
    fun createIntArray(): IntArray?
}

/**
 * The `BikeData` parcelable published by Peloton's on-tablet sensor service
 * (`com.onepeloton.affernetservice`). The same structure is used by the Bike (V1 interface)
 * and the Bike+ (IBikeInterface).
 *
 * Field order was established by the open-source Grupetto project's analysis of the
 * Peloton system APKs; it is the order the service writes fields into the Parcel.
 */
data class PelotonBikeData(
    val rpm: Long,
    /** Power in centi-watts (divide by 100 for watts). */
    val powerCentiWatts: Long,
    val stepperMotorPosition: Long,
    val loadCellReading: Long,
    val currentResistance: Int,
    val targetResistance: Int,
    val firmwareVersion: String? = null,
    val calibrationState: Int = 0,
    val systemState: Int = 0,
    val serial: String? = null,
    val hardwareVersion: String? = null,
    val v1Resistance: Float = 0f,
    val powerZoneAutoFollowEnabled: Int = 0,
    val powerZoneAutoFollowPowerSetPoint: Int = 0,
    val powerZoneAutoFollowTargetResistance: Float = 0f,
    val powerZoneAutoFollowStatus: Int = 0,
) {
    val powerWatts: Float get() = powerCentiWatts / 100f

    companion object {
        /**
         * Decode a BikeData parcel. The six leading fields (rpm, power, motor, load cell,
         * current/target resistance) are required; everything after is best-effort diagnostic
         * data, because firmware revisions have been seen to append fields.
         */
        fun decode(p: ParcelSource): PelotonBikeData {
            val rpm = p.readLong()
            val power = p.readLong()
            val stepper = p.readLong()
            val loadCell = p.readLong()
            val currentResistance = p.readInt()
            val targetResistance = p.readInt()
            val base = PelotonBikeData(rpm, power, stepper, loadCell, currentResistance, targetResistance)
            return try {
                decodeTail(p, base)
            } catch (_: Throwable) {
                base
            }
        }

        private fun decodeTail(p: ParcelSource, base: PelotonBikeData): PelotonBikeData {
            val fw = p.readString()
            p.createByteArray() // raw packet
            p.readString() // packet time
            p.readInt() // stepper start
            p.readInt() // stepper end
            val calibration = p.readInt()
            p.readInt() // encoder angle
            val systemState = p.readInt()
            p.readInt() // error index
            repeat(5) { p.readInt(); p.readString() } // error 1..5 code + time
            p.createIntArray() // error map
            p.readString() // load cell table
            p.readInt() // load cell table crc
            val pSerial = p.readString()
            p.readString() // q serial
            p.readString() // frame serial
            p.readString() // load cell serial
            p.readFloat() // load cell offset
            p.readInt() // data write cycle
            p.readString() // data write date
            p.readString() // data write time
            p.readInt() // load cell zero
            p.readInt() // load cell cal span
            p.readInt() // load cell temp count
            p.readFloat() // resistance offset
            p.readInt() // position offset
            p.readInt() // load cell table status
            val v1Resistance = p.readFloat()
            p.readString() // load cell version
            p.readInt() // applied position offset
            p.readInt() // stall threshold
            val hw = p.readString()
            p.readInt() // AD value
            val pzafEnabled = p.readInt()
            val pzafSetPoint = p.readInt()
            val pzafTargetResistance = p.readFloat()
            val pzafStatus = p.readInt()
            return base.copy(
                firmwareVersion = fw,
                calibrationState = calibration,
                systemState = systemState,
                serial = pSerial,
                hardwareVersion = hw,
                v1Resistance = v1Resistance,
                powerZoneAutoFollowEnabled = pzafEnabled,
                powerZoneAutoFollowPowerSetPoint = pzafSetPoint,
                powerZoneAutoFollowTargetResistance = pzafTargetResistance,
                powerZoneAutoFollowStatus = pzafStatus,
            )
        }
    }
}

/** Constants describing Peloton's on-tablet sensor service, discovered by the community. */
object PelotonService {
    const val AFFERNET_PACKAGE = "com.onepeloton.affernetservice"
    const val AFFERNET_SERVICE_CLASS = "com.onepeloton.affernetservice.AffernetService"

    /** Bike (gen 2 OS and newer): callback based interface. */
    const val V1_INTERFACE = "com.onepeloton.affernetservice.IV1Interface"
    const val V1_CALLBACK = "com.onepeloton.affernetservice.IV1Callback"
    const val V1_TX_REGISTER_CALLBACK = 1
    const val V1_TX_UNREGISTER_CALLBACK = 2
    const val V1_CB_ON_SENSOR_DATA = 1
    const val V1_CB_ON_SENSOR_ERROR = 2
    const val V1_CB_ON_CALIBRATION = 3

    /** Bike+: polled interface. Transaction 14 returns the current BikeData. */
    const val BIKE_PLUS_INTERFACE = "com.onepeloton.affernetservice.IBikeInterface"
    const val BIKE_PLUS_TX_GET_BIKE_DATA = 14

    /** Older Bike OS builds: Messenger based service. */
    const val LEGACY_ACTION = "android.intent.action.peloton.SensorData"
    const val LEGACY_PACKAGE = "com.peloton.service.SensorData"
    const val LEGACY_CMD_RPM = 1
    const val LEGACY_CMD_POWER = 2
    const val LEGACY_CMD_RESISTANCE = 3
    const val LEGACY_KEY_REQUEST_ID = "requestId"
    const val LEGACY_KEY_HEX = "responseHexString"
    const val LEGACY_KEY_TIME = "time"
    const val LEGACY_KEY_DATA = "data"
    const val LEGACY_TIMEOUT = "TIME_OUT"

    const val SENSOR_PERMISSION = "onepeloton.permission.ACCESS_SENSOR_SERVICE"

    /** The Peloton app itself, so BikeOS can still launch it for members who keep a subscription. */
    const val PELOTON_APP_PACKAGE = "com.onepeloton.callisto"

    const val BRAND = "Peloton"
    const val BIKE_PLUS_MODEL = "PLTN-TTR01"
}
