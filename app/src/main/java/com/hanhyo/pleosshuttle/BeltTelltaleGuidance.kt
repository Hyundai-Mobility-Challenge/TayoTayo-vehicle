package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.sdk.BeltTelltaleState
import com.hanhyo.pleosshuttle.sdk.SdkObservation

/**
 * V-06 벨트 경고등 연동(보조). 차량 벨트 경고등은 좌석 구분이 없는 신호 하나라(OQ-06) 아이·좌석에 붙이지 않고
 * 차량 전체 배너 한 줄 + 음성 한 번으로만 알린다. 센서는 보조(7절): 벨트 확인·출결 기록을 바꾸지 않고 "확인 요청"까지만.
 * 관측값은 메모리에서만 쓰고 운행 파일·원장·서버에는 넣지 않는다(D27).
 */
internal enum class BeltTelltaleSignal { WARNING, CLEAR, UNKNOWN }

/** 경고등 관측 신선도 상한. 어댑터가 2초마다 다시 조회하므로 응답 지연을 감안해 6초. */
internal const val BELT_TELLTALE_MAX_AGE_MILLIS = 6_000L

internal const val BELT_TELLTALE_BANNER = "벨트 확인 필요 · 차량 벨트 경고등이 켜졌어요"

/** On/Blink = 경고, Off = 해제. 오래되었거나 미수신·오류면 UNKNOWN(배너 없음, 해제로도 보지 않음). */
internal fun beltTelltaleSignal(observation: SdkObservation<BeltTelltaleState>, now: Long): BeltTelltaleSignal =
    if (!observation.isFresh(now, BELT_TELLTALE_MAX_AGE_MILLIS)) BeltTelltaleSignal.UNKNOWN
    else when (observation.value) {
        BeltTelltaleState.ON, BeltTelltaleState.BLINK -> BeltTelltaleSignal.WARNING
        BeltTelltaleState.OFF -> BeltTelltaleSignal.CLEAR
        null -> BeltTelltaleSignal.UNKNOWN
    }

/**
 * 경고등을 아이 안전 안내로 쓰는 구간: 운행 준비(탑승 후)·주행·하차·종료 점검 중 차 안에 탑승 기록이 있는 아이가 있을 때.
 * 탑승 전·전원 하차 뒤·운행 완료에는 기사 본인 벨트 경고일 뿐이라 띄우지 않는다(차량 계기판이 따로 알림).
 */
internal fun beltTelltaleTripActive(trip: TripState): Boolean =
    trip.phase != TripPhase.ENDED && !trip.noTripToday && trip.onboardChildren.isNotEmpty()

/** 화면 배너 문구(모든 단계·주행 중에도 상태 줄 한 줄만, 팝업 없음). 보일 때가 아니면 null. */
internal fun beltTelltaleBanner(trip: TripState, signal: BeltTelltaleSignal): String? =
    if (signal == BeltTelltaleSignal.WARNING && beltTelltaleTripActive(trip)) BELT_TELLTALE_BANNER else null

/**
 * 경고 음성. 벨트를 아직 확인하지 않은 탑승 아이가 있으면 V-16 이름 나열 규칙으로 덧붙인다.
 * 경고등이 어느 아이 것인지는 알 수 없으므로 "이 아이가 풀었다"고 말하지 않고, 기존 확인 기록 기준 미확인 이름만 읽는다.
 * 예: "벨트 경고등이 켜졌어요. 정차 후 벨트를 확인해 주세요. 벨트 미확인: 어린이 다, 어린이 라."
 */
internal fun beltTelltaleSpeech(uncheckedNames: List<String>): String =
    "벨트 경고등이 켜졌어요. 정차 후 벨트를 확인해 주세요." +
        if (uncheckedNames.isEmpty()) "" else " 벨트 미확인: ${spokenNameList(uncheckedNames)}."

/**
 * 한 경고 구간(Off → On/Blink)에 음성 한 번. 신선한 Off를 본 뒤에만 다시 읽는다(오래된 값·미수신은 해제로 보지 않음).
 * 다른 안내 음성이 나오는 중이면 끊지 않고 기다렸다가 끝난 뒤 읽는다(운행 안내 우선, 경고는 배너로 이미 보임).
 */
internal class BeltTelltaleGuidance {
    /** 이번 경고 구간에서 이미 읽은 운행 id. 키에는 이름·센서 원값을 넣지 않는다. */
    private var announcedTrip: String? = null

    fun take(trip: TripState, signal: BeltTelltaleSignal, foreground: Boolean, speechBusy: Boolean): TripCue? {
        if (signal == BeltTelltaleSignal.CLEAR) { announcedTrip = null; return null }
        if (signal != BeltTelltaleSignal.WARNING || !foreground || speechBusy || !beltTelltaleTripActive(trip)) return null
        if (announcedTrip == trip.tripId) return null
        announcedTrip = trip.tripId
        return TripCue("${trip.tripId}:belt-telltale", beltTelltaleSpeech(trip.beltUncheckedChildren.map(::spokenChildName)))
    }

    /** 읽던 경고 음성이 더 앞선 운행 안내에 끊겼을 때: 경고가 계속 켜져 있으면 그 안내가 끝난 뒤 한 번 더 읽는다. */
    fun rearm() { announcedTrip = null }
}
