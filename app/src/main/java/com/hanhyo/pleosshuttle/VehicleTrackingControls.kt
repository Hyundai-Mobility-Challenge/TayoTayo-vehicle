package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.TripPhase

/** Presentation/command gates only: never alters attendance or trip phase. */
data class VehicleTrackingControls(
    val canEnable: Boolean,
    val guidance: String,
    val source: String = "위치 출처: Pleos 내비",
)

/** [bound]: 기관에서 불러와 서버에 묶인 운행인지(운행 없음 상태는 false). */
fun vehicleTrackingControls(
    connected: Boolean, bound: Boolean, phase: TripPhase, onboard: Boolean,
    hasStop: Boolean, enabled: Boolean, storageOk: Boolean,
    pickup: Boolean = false,
): VehicleTrackingControls {
    val active = phase == TripPhase.DRIVING || phase == TripPhase.ALIGHTING
    val canEnable = connected && bound && storageOk &&
        phase in listOf(TripPhase.PREPARING, TripPhase.DRIVING, TripPhase.ALIGHTING)
    val message = when {
        !storageOk -> "운행 저장 오류를 먼저 확인해 주세요."
        !connected -> "1. 기관에서 발급한 차량 연결 코드로 연결해 주세요."
        !bound -> "2. 기관 연결에서 오늘 운행을 불러와 주세요."
        phase == TripPhase.ENDED || phase == TripPhase.END_REVIEW -> "운행 위치 공유가 종료되었습니다. 다음 운행은 새 명단으로 준비해 주세요."
        !active || !onboard -> "어린이 탑승 기록 후 운행 시작을 누르면 위치 공유가 자동으로 켜집니다."
        // 등원은 마지막 정류장 뒤 기관으로 가는 구간에 현재 정류장이 없다(하차 지점 문구는 하원 전용).
        !hasStop && pickup -> "기관으로 가는 중이에요. 기관에 도착해 인계를 마치고 운행을 끝내면 위치 공유가 종료됩니다."
        !hasStop -> "오늘의 하차 지점이 없습니다. 기관 명단의 하차 지점을 확인해 주세요."
        !enabled -> "위치 공유가 중지되어 있습니다. 다시 공유하려면 전송을 켜 주세요."
        else -> "출발 후 Pleos 내비에서 받은 진행 상황과 예상 도착 시간을 보호자에게 공유합니다."
    }
    return VehicleTrackingControls(canEnable, message)
}

/**
 * 위치 공유가 켜져 있는데 위치 서비스(TripTrackingService)가 돌지 않아 화면(ViewModel)이 직접 위치를 보고한 뒤의 상태 문구.
 * [active]: 운행 중·정차 중인지, [fixSent]: 유효한 Pleos 위치를 실어 보냈는지, [permission]: 위치 권한이 있는지,
 * [etaFailed]: 위치는 보냈지만 예상 시간 전송만 실패했는지.
 */
fun foregroundTrackingStatus(active: Boolean, fixSent: Boolean, permission: Boolean, etaFailed: Boolean = false): String = when {
    !active -> "출발하면 위치 전송을 시작해요"
    fixSent && etaFailed -> "Pleos 내비 위치 전송 · 예상 시간 전송 실패(다음 주기에 다시 보냄) · 앱이 열려 있는 동안만"
    fixSent -> "Pleos 내비 위치 전송 · 앱이 열려 있는 동안만(위치 서비스 꺼짐)"
    !permission -> "위치 권한 필요 · 운행 권한 확인에서 허용해 주세요. 보호자 화면은 갱신 대기로 표시"
    else -> "위치 미수신 · 보호자 화면에 갱신 대기로 표시"
}
