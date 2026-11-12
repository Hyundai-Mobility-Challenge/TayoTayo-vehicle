package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

internal data class TripCue(val key: String, val text: String)

/**
 * UI refreshes and per-child writes are not announcement events.
 * V-15: 다음 지점 안내 키에는 그 지점에서 내릴 아이 id 집합을 넣어, 주행 중 하차 대상이 바뀌면(출결 정정 등) 한 번 더 읽고
 * 같은 내용이면 다시 읽지 않는다. 도착 안내 키에는 넣지 않는다(정차 중 아이별 하차 기록은 안내 사건이 아니다).
 * 키에는 이름 대신 id만 넣는다(이름은 차량 안 음성으로만).
 */
internal fun tripCue(trip: TripState): TripCue? {
    if (trip.phase == TripPhase.PREPARING) return departureCue(trip)
    val stop = trip.currentStop ?: trip.origin
    val name = stopLabel(stop)
    val alighting = alightingChildren(trip, trip.currentStop)
    val text = when (trip.phase) {
        TripPhase.DRIVING -> redepartureBeltPrefix(trip) + (if (trip.currentStop == null) "하차 지점 방문을 마쳤습니다. 출발지인 ${name}으로 돌아갑니다."
            else approachSpeech(name, alighting.map(::spokenChildName)))
        TripPhase.ALIGHTING -> arrivalSpeech(name, alighting.map(::spokenChildName))
        TripPhase.END_REVIEW -> stayOnboardReturnPrefix(trip) + "운행을 마치기 전에 차량 실내를 직접 확인해 주세요."
        TripPhase.ENDED -> "하원 기록을 저장했습니다."
        TripPhase.PREPARING -> return null
    }
    val base = stageKey(trip)
    val key = if (trip.phase == TripPhase.DRIVING && trip.currentStop != null) "$base:${alighting.map { it.id }.sorted().joinToString("|")}" else base
    return TripCue(key, text)
}

/** 단계 키(지점·단계 단위). 내비 요청은 하차 대상이 바뀌어도 목적지가 같으므로 이 키만 쓴다. */
private fun stageKey(trip: TripState) = "${trip.tripId}:${trip.integrationMode}:${trip.routeVersion}:${trip.currentStopIndex}:${trip.phase}"

/**
 * V-15 하차 안내 대상: 지금 탑승 기록(ONBOARD)이고 이 지점에서 내리는 아이만, 명단 순서. 미탑승 확인·이미 하차·탑승 전(미예정 포함)은 넣지 않는다.
 * V-21 동승 유지 아이는 내리지 않으므로 넣지 않는다.
 */
internal fun alightingChildren(trip: TripState, stop: Stop?): List<ChildRecord> =
    if (stop == null) emptyList() else trip.children.filter { it.stopId == stop.id && it.awaitingAlighting }

/** V-22 복귀 안내 앞말. 동승 유지(V-21) 아이가 있으면 "동승 유지 어린이 가 기관에 인계해 주세요. "를 붙이고, 없으면 빈 문자열. */
internal fun stayOnboardReturnPrefix(trip: TripState): String {
    val names = trip.stayOnboardChildren.map(::spokenChildName)
    return if (names.isEmpty()) "" else "동승 유지 ${spokenNameList(names)} 기관에 인계해 주세요. "
}

/**
 * V-16 재출발 벨트 안내. 정류장 기록을 마치고(또는 운행 복귀로) 다시 DRIVING이 된 첫 안내(currentStopIndex > 0)의 앞에 붙인다.
 * 별도 안내를 만들지 않고 그 지점의 다음 목적지 안내와 한 문장으로 읽으므로 키·중복 방지·발화 조건이 그대로 같다
 * (같은 단계 키면 다시 읽지 않음). 운행(DRIVING) 중에는 벨트 기록을 바꿀 수 없어 문구가 그 사이 바뀌지 않는다.
 * 첫 출발(index 0)은 출발 전 요약(departureCue)이 이름을 읽으므로 붙이지 않는다. 미확인이 없으면 아무것도 붙이지 않는다.
 */
internal fun redepartureBeltPrefix(trip: TripState): String =
    if (trip.phase != TripPhase.DRIVING || trip.currentStopIndex == 0) ""
    else redepartureBeltSpeech(trip.beltUncheckedChildren.map(::spokenChildName))?.let { "$it " } ?: ""

/** 예: "출발 전 벨트 확인: 어린이 다, 어린이 라 벨트 미확인." 미확인이 없으면 null(덧붙이지 않음). */
internal fun redepartureBeltSpeech(names: List<String>): String? =
    if (names.isEmpty()) null else "출발 전 벨트 확인: ${spokenNameList(names)} 벨트 미확인."

/** 다음 지점 접근 안내. 예: "다음은 서울숲, 어린이 가, 어린이 나 하차." */
internal fun approachSpeech(stopName: String, names: List<String>): String =
    if (names.isEmpty()) "다음은 ${stopName}, 내릴 어린이 없음." else "다음은 ${stopName}, ${names.joinToString(", ")} 하차."

/** 지점 도착 안내. 예: "서울숲 도착, 어린이 가, 어린이 나 하차 예정. 보호자 인계와 하차를 기록해 주세요." */
internal fun arrivalSpeech(stopName: String, names: List<String>): String =
    if (names.isEmpty()) "${stopName} 도착, 내릴 어린이 없음."
    else "${stopName} 도착, ${names.joinToString(", ")} 하차 예정. 보호자 인계와 하차를 기록해 주세요."

/** V-15 등원 문구(등원 흐름이 아직 없어 연결하지 않음). 예: "다음은 서울숲 정류장, 어린이 가 탑승." */
internal fun boardingApproachSpeech(stopName: String, names: List<String>): String =
    if (names.isEmpty()) "다음은 ${stopName} 정류장, 탈 어린이 없음." else "다음은 ${stopName} 정류장, ${names.joinToString(", ")} 탑승."

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
        val key = stageKey(trip)
        if (key == navigationKey) return null
        navigationKey = key
        return trip.currentStop ?: trip.origin
    }
}
