package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.IntegrationMode

/** Presentation/command gates only: never alters attendance or trip phase. */
data class VehicleTrackingControls(
    val canEnable: Boolean,
    val canSendDemo: Boolean,
    val guidance: String,
    val source: String,
)

fun vehicleTrackingControls(
    connected: Boolean, bound: Boolean, phase: TripPhase, onboard: Boolean,
    hasStop: Boolean, mode: IntegrationMode, enabled: Boolean, storageOk: Boolean,
    pickup: Boolean = false,
): VehicleTrackingControls {
    val active = phase == TripPhase.DRIVING || phase == TripPhase.ALIGHTING
    val canEnable = connected && bound && storageOk &&
        phase in listOf(TripPhase.PREPARING, TripPhase.DRIVING, TripPhase.ALIGHTING)
    val message = when {
        !storageOk -> "운행 저장 오류를 먼저 확인해 주세요."
        !connected -> "1. 기관에서 발급한 차량 연결 코드로 연결해 주세요."
        !bound -> "현재는 기관 명단과 다른 체험 운행입니다. 체험을 보관하고 기관 명단으로 준비해 주세요."
        phase == TripPhase.ENDED || phase == TripPhase.END_REVIEW -> "운행 위치 공유가 종료되었습니다. 다음 운행은 새 명단으로 준비해 주세요."
        !active || !onboard -> "어린이 탑승 기록 후 운행 시작을 누르면 위치 공유가 자동으로 켜집니다."
        // 등원은 마지막 정류장 뒤 기관으로 가는 구간에 현재 정류장이 없다(하차 지점 문구는 하원 전용).
        !hasStop && pickup -> "기관으로 가는 중이에요. 기관에 도착해 인계를 마치고 운행을 끝내면 위치 공유가 종료됩니다."
        !hasStop -> "오늘의 하차 지점이 없습니다. 기관 명단의 하차 지점을 확인해 주세요."
        !enabled -> "위치 공유가 중지되어 있습니다. 다시 공유하려면 전송을 켜 주세요."
        mode == IntegrationMode.SIMULATION -> "모의 체험 · 출발하면 이동 시연이 자동으로 시작됩니다. 보호자 화면에 합성 위치와 예상 시간이 표시됩니다."
        else -> "출발 후 Pleos 내비에서 받은 진행 상황과 예상 도착 시간을 보호자에게 공유합니다."
    }
    return VehicleTrackingControls(canEnable,
        canEnable && active && onboard && hasStop && enabled && mode == IntegrationMode.SIMULATION,
        message, if (mode == IntegrationMode.SDK) "위치 출처: Pleos 내비" else "위치 출처: 모의 시험 · 실제 차량 위치 아님")
}

/** Only an unbound local fictional trip can be archived early for the connected demo. */
fun canArchiveLocalTrackingDemo(debug: Boolean, connected: Boolean, bound: Boolean,
    mode: IntegrationMode, tripId: String, storageOk: Boolean, busy: Boolean) =
    debug && connected && !bound && mode == IntegrationMode.SIMULATION &&
        tripId.startsWith("fictional-") && storageOk && !busy
