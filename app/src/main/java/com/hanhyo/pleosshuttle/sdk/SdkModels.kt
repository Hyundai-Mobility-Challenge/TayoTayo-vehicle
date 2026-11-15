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

/**
 * V-06 차량 벨트 경고등(Seat Belt telltale) 상태. 좌석 구분이 없는 차량 전체 신호 하나다(OQ-06).
 * 화면 안내·음성 판단에만 쓰고 운행 파일·원장·서버에는 넣지 않는다(D27).
 */
enum class BeltTelltaleState { OFF, ON, BLINK }

data class VehicleSnapshot(
    val speedMetersPerSecond: SdkObservation<Float> = SdkObservation(),
    val gear: SdkObservation<Int> = SdkObservation(),
    val parkingBrake: SdkObservation<Boolean> = SdkObservation(),
    val parkingBrakeSupported: Boolean = false,
    val doorLocked: SdkObservation<Boolean> = SdkObservation(),
    val seatBelts: Map<String, SdkObservation<Boolean>> = emptyMap(),
    /** V-06 차량 벨트 경고등. 메모리 관측값만(저장·전송 안 함). */
    val seatBeltTelltale: SdkObservation<BeltTelltaleState> = SdkObservation(),
    val uxRestricted: SdkObservation<Boolean> = SdkObservation(),
    val activeUxRestrictions: Int? = null,
    val supportedDoorAreas: List<String> = emptyList(),
    val selectedDoorArea: String? = null,
    val supportedBeltAreas: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val versions: Map<String, String> = emptyMap(),
    val connected: Boolean = false,
)

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
