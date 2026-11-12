package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.DepartureSummary
import com.hanhyo.pleosshuttle.domain.departureRouteOrder
import com.hanhyo.pleosshuttle.domain.RouteObjective
import com.hanhyo.pleosshuttle.domain.RoutePlanner
import com.hanhyo.pleosshuttle.domain.RouteTimeline
import com.hanhyo.pleosshuttle.domain.RouteTimelines
import com.hanhyo.pleosshuttle.domain.RouteWarning
import com.hanhyo.pleosshuttle.domain.SampleRoutePlan
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState

/** 순서 바꾸기에서 고를 수 있는 이유. 고르지 않아도 정할 수 있다. */
internal val reorderReasons = listOf("보호자 요청", "도로 상황", "기타")

/** 순서를 정하는 기준(L2). 처음 한 번 펼쳐 보이고 이후에는 버튼으로만 연다. */
internal val routeCriteria = listOf(
    "기관이 승인한 정류장만 들러요",
    "아이가 인도 쪽으로 내릴 수 있게 진입 방향을 맞춰요",
    "길을 건너는 하차를 줄여요",
    "보호자와 약속한 시간보다 일찍 도착하지 않게 해요",
    "결석하거나 타지 않은 아이는 하차 인원에서 빼요",
)

/** 경로 확인에 보여줄 순서. 운행 시작 때 자동 계산될 순서라면 그 결과(RoutePlanner.plan)를 미리 보여준다. */
internal fun displayedRouteOrder(trip: TripState): List<String> = departureRouteOrder(trip)

/** V-08 출발 전 확인 칩. [warn]이면 경고 톤(벨트 미확인 > 0, 아직 결정 안 된 어린이 > 0). */
internal data class DepartureChip(val text: String, val warn: Boolean, val tag: String)

internal fun departureChips(summary: DepartureSummary): List<DepartureChip> = buildList {
    add(DepartureChip("탑승 ${summary.boarded}명 / 예정 ${summary.expected}명", warn = false, tag = "departure-riders"))
    add(DepartureChip("벨트 미확인 ${summary.beltUnchecked}명", warn = summary.beltUnchecked > 0, tag = "departure-belt"))
    if (summary.undecided > 0) add(DepartureChip("아직 결정 안 된 어린이 ${summary.undecided}명", warn = true, tag = "departure-undecided"))
}

/** "첫 정류장 서울숲 · 경로 서울숲 → 국립중앙박물관 → 올림픽공원" */
internal fun departureRouteText(summary: DepartureSummary): String =
    if (summary.stopNames.isEmpty()) "들를 정류장 없음"
    else "첫 정류장 ${summary.firstStopName} · 경로 ${summary.stopNames.joinToString(" → ")}"

/** '제안 순서로 되돌리기'의 기준. 정차 중이면 지나온 지점과 지금 정류장 위치는 고정한 채 거리 기준으로 다시 계산한다. */
internal fun suggestedRouteOrder(trip: TripState, lockedCount: Int): List<String> = runCatching {
    RoutePlanner.optimize(
        originId = trip.origin.id,
        approvedStopIds = trip.routeOrder,
        matrix = RoutePlanner.syntheticMatrix(trip.origin, trip.stops, RouteObjective.DISTANCE),
        objective = RouteObjective.DISTANCE,
        returnToOrigin = trip.returnToOrigin,
        fixedPositions = trip.routeOrder.take(lockedCount).withIndex().associate { it.index to it.value },
    ).stopOrder
}.getOrDefault(trip.routeOrder)

/** 가상 출발 시각과 합성 이동 시간(RoutePlanner TIME 행렬, '모의 분')으로 도착 시각을 계산한다. */
internal fun routeTimeline(trip: TripState, order: List<String>): RouteTimeline = RouteTimelines.build(
    originId = trip.origin.id,
    order = order,
    matrix = RoutePlanner.syntheticMatrix(trip.origin, trip.stops, RouteObjective.TIME),
    startMinute = SampleRoutePlan.departureMinute,
    dwellMinutes = SampleRoutePlan.DWELL_MINUTES,
    returnToOrigin = trip.returnToOrigin,
)

internal fun routeWarnings(timeline: RouteTimeline): List<RouteWarning> =
    RouteTimelines.warnings(timeline, SampleRoutePlan.rules, SampleRoutePlan.returnDeadlineMinute)

internal fun stopMemo(stopId: String): String? = SampleRoutePlan.rules.firstOrNull { it.stopId == stopId }?.memo

/** 정류장에서 내릴 아이 이름. 운행 준비에는 오늘 탈 아이(미탑승 예정·미탑승 확인 제외), 출발 뒤에는 탄 아이만. */
internal fun ridersAt(trip: TripState, stopId: String): List<String> = trip.children
    .filter { it.stopId == stopId && ridesFrom(trip, it) }
    .map { it.name.removePrefix("가상 ") }

/** 평소와 달라진 점(L2). 지금은 탑승 기록만으로 알 수 있는 것만 보여준다. 결석·인계자 변경 연동은 4단계. */
internal data class RouteChange(val kind: String, val text: String)

internal fun routeChanges(trip: TripState): List<RouteChange> = buildList {
    trip.children.filter { it.status == Attendance.NOT_BOARDED || it.status == Attendance.CONFIRMED_NOT_BOARDED }.forEach {
        add(RouteChange("빠짐", "${it.name.removePrefix("가상 ")} · ${if (it.status == Attendance.CONFIRMED_NOT_BOARDED) "미탑승 확인" else "탑승 기록 없음"}"))
    }
    trip.routeOrder.mapNotNull { id -> trip.stops.find { it.id == id } }
        .filter { stop -> trip.children.none { it.stopId == stop.id && (it.status == Attendance.ONBOARD || it.status == Attendance.ALIGHTED) } }.forEach {
        add(RouteChange("하차 없음", "${it.shortName} · 빼기 전까지 순서에 남아요"))
    }
    // V-09·D17: 선생님이 뺀 정류장. 다시 탈 아이가 생기면 자동으로 다시 들어간다.
    trip.excludedStops.mapNotNull { id -> trip.stops.find { it.id == id } }.forEach {
        add(RouteChange("뺀 정류장", "${it.shortName} · 방문 순서에서 뺐어요"))
    }
}

internal fun clockText(minute: Int): String = RouteTimelines.formatClock(minute)

/** '+7분', '-3분', '같음' */
internal fun minuteDiffText(diff: Int): String = when {
    diff > 0 -> "+${diff}분"
    diff < 0 -> "${diff}분"
    else -> "같음"
}
