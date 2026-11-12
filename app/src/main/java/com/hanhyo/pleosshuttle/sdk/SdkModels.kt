package com.hanhyo.pleosshuttle.sdk

enum class SdkOutcome { UNVERIFIED, PERMISSION_REQUIRED, UNSUPPORTED, ACCEPTED, TARGET_OBSERVED, PARTIAL, ERROR, TIMEOUT }

data class SdkObservation<T>(
    val value: T? = null,
    val observedAtMillis: Long? = null,
    val source: String = "실제 SDK · 미수신",
    val outcome: SdkOutcome = SdkOutcome.UNVERIFIED,
    val error: String? = null,
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val sourceTimestampElapsedNanos: Long? = null,
) {
    fun isFresh(nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = 5_000): Boolean =
        value != null && observedAtMillis != null && nowMillis - observedAtMillis in 0..maxAgeMillis &&
            outcome == SdkOutcome.TARGET_OBSERVED
}

data class VehicleSnapshot(
    val speedMetersPerSecond: SdkObservation<Float> = SdkObservation(),
    val gear: SdkObservation<Int> = SdkObservation(),
    val parkingBrake: SdkObservation<Boolean> = SdkObservation(),
    val parkingBrakeSupported: Boolean = false,
    val doorLocked: SdkObservation<Boolean> = SdkObservation(),
    val seatOccupancy: Map<String, SdkObservation<Boolean>> = emptyMap(),
    val seatBelts: Map<String, SdkObservation<Boolean>> = emptyMap(),
    val uxRestricted: SdkObservation<Boolean> = SdkObservation(),
    val activeUxRestrictions: Int? = null,
    val supportedDoorAreas: List<String> = emptyList(),
    val selectedDoorArea: String? = null,
    val supportedSeatAreas: List<String> = emptyList(),
    val supportedBeltAreas: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val versions: Map<String, String> = emptyMap(),
    val connected: Boolean = false,
    val emulatorGuard: Boolean = false,
    val doorRequest: SdkObservation<Boolean> = SdkObservation(),
    val unlockBlockReason: String = "실제 입력 확인 중",
)

/** Adapter-issued, short-lived token; no simulated values may be supplied to the adapter. */
data class UnlockConfirmation internal constructor(internal val nonce: Long, internal val revision: Long, internal val createdElapsed: Long, internal val areaKey: String? = null)

data class NaviDestination(val name: String, val latitude: Double, val longitude: Double)
data class NaviSnapshot(
    val destination: SdkObservation<NaviDestination> = SdkObservation(),
    val routeState: SdkObservation<String> = SdkObservation(),
    val navigationStatus: String = "미수신",
    val routeMode: String? = null,
    val routeIsRouting: Boolean? = null,
    val termsAccepted: Boolean? = null,
    val requested: NaviDestination? = null,
    val outcome: SdkOutcome = SdkOutcome.UNVERIFIED,
    val message: String = "내비 미검증",
    val busy: Boolean = false,
    val versions: Map<String, String> = emptyMap(),
    val diagnostics: List<String> = emptyList(),
    val initialSnapshot: String? = null,
)
