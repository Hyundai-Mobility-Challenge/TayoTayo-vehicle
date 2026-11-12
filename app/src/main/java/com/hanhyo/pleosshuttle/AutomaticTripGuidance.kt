package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

internal data class TripCue(val key: String, val text: String)

/** UI refreshes and per-child writes are not announcement events. */
internal fun tripCue(trip: TripState): TripCue? {
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

/** At most one dispatch per current cue within a ViewModel session; cold restore may announce once. */
internal class AutomaticTripGuidance {
    private var speechKey: String? = null
    private var navigationKey: String? = null
    fun takeSpeech(trip: TripState, foreground: Boolean): TripCue? {
        if (!foreground) return null
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
