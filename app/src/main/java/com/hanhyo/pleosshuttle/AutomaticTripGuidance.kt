package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

internal data class TripCue(val key: String, val text: String)

/** UI refreshes and per-child writes are not announcement events. */
internal fun tripCue(trip: TripState): TripCue? {
    if (trip.phase == TripPhase.PREPARING) return departureCue(trip)
    val stop = trip.currentStop ?: trip.origin
    val name = stop.name.substringAfter(" · ")
    val text = when (trip.phase) {
        TripPhase.DRIVING -> if (trip.currentStop == null) "하차 지점 방문을 마쳤습니다. 출발지인 ${name}으로 돌아갑니다."
            else "다음 지점은 ${name}입니다. 내릴 어린이는 ${trip.currentStopChildren.count { it.status == Attendance.ONBOARD }}명입니다."
        TripPhase.ALIGHTING -> "${name} 도착을 확인했습니다. 보호자 인계와 하차를 기록해 주세요."
        TripPhase.END_REVIEW -> "운행을 마치기 전에 차량 실내를 직접 확인해 주세요."
        TripPhase.ENDED -> "하원 기록을 저장했습니다."
        TripPhase.PREPARING -> return null
    }
    return TripCue("${trip.tripId}:${trip.integrationMode}:${trip.routeVersion}:${trip.currentStopIndex}:${trip.phase}", text)
}

/**
 * V-08 출발 전 음성 요약. [운행 시작] 단계(모두 결정 · 탑승 1명 이상)에서만. 키에 출결·출발 순서를 넣어
 * 같은 내용이면 다시 읽지 않고, 탑승 인원·순서가 바뀌어 기사 확인이 풀리면 바뀐 요약을 한 번 더 읽는다(벨트 토글은 제외).
 */
internal fun departureCue(trip: TripState): TripCue? {
    if (!trip.awaitingDeparture) return null
    val order = departureRouteOrder(trip)
    return TripCue("${trip.tripId}:${trip.integrationMode}:departure:${departureChangeKey(trip)}", departureSpeech(departureSummary(trip, order)))
}

/** At most one dispatch per current cue within a ViewModel session; cold restore may announce once. */
internal class AutomaticTripGuidance {
    private var speechKey: String? = null
    private var navigationKey: String? = null
    /**
     * [departureReady]: 출발 전 요약은 정차 확인(V-11)·차량 UX 제한 없음일 때만 읽는다. 아니면 키를 쓰지 않고 기다렸다가
     * 정차가 확인되면 그때 한 번 읽는다. 운행 중 안내(목적지·도착)에는 영향이 없다.
     */
    fun takeSpeech(trip: TripState, foreground: Boolean, departureReady: Boolean = true): TripCue? {
        if (!foreground) return null
        if (trip.phase == TripPhase.PREPARING && !departureReady) return null
        val cue = tripCue(trip) ?: return null
        if (cue.key == speechKey) return null
        speechKey = cue.key
        return cue
    }
    fun takeNavigation(trip: TripState, foreground: Boolean, ready: Boolean): Stop? {
        if (!foreground || !ready || trip.phase != TripPhase.DRIVING) return null
        val key = tripCue(trip)!!.key
        if (key == navigationKey) return null
        navigationKey = key
        return trip.currentStop ?: trip.origin
    }
}
