package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.DepartureSummary
import com.hanhyo.pleosshuttle.domain.departureRouteOrder
import com.hanhyo.pleosshuttle.domain.RouteObjective
import com.hanhyo.pleosshuttle.domain.RoutePlanner
import com.hanhyo.pleosshuttle.domain.RouteTimeline
import com.hanhyo.pleosshuttle.domain.RouteTimelines
import com.hanhyo.pleosshuttle.domain.TripState

/**
 * 경로 확인(L2)의 방문 순서 안내. 앱은 순서를 정하지 않는다: 기관(서버)이 정한 정류장 순서를 그대로 따르고,
 * 빈 정류장 빼기·다시 넣기만 한다(다시 넣으면 원래 순서 자리). 처음 한 번 펼쳐 보이고 이후에는 버튼으로만 연다.
 */
internal val routeCriteria = listOf(
    "방문 순서는 기관이 정한 정류장 순서를 그대로 따라요",
    "내릴 어린이가 없는 정류장은 빼고, 다시 생기면 원래 순서 자리에 다시 넣어요",
    "결석하거나 타지 않은 아이는 하차 인원에서 빼요",
)

/** 등원(V-25) 방문 순서 안내. 하원 안내의 "하차"를 "탑승"으로 바꾼 것. */
internal val pickupRouteCriteria = listOf(
    "방문 순서는 기관이 정한 정류장 순서를 그대로 따라요",
    "탈 어린이가 없는 정류장은 빼고, 다시 생기면 원래 순서 자리에 다시 넣어요",
    "결석·직접 등원으로 타지 않을 아이는 탑승 인원에서 빼요",
)

internal fun routeCriteriaFor(trip: TripState): List<String> = if (trip.isPickup) pickupRouteCriteria else routeCriteria

/** 경로 확인에 보여줄 순서. 기관 운행은 서버 순서 그대로다(예시 순서일 때만 운행 시작 때의 자동 계산 결과를 미리 보여 준다). */
internal fun displayedRouteOrder(trip: TripState): List<String> = departureRouteOrder(trip)

/** V-08 출발 전 확인 칩. [warn]이면 경고 톤(벨트 미확인 > 0, 아직 결정 안 된 어린이 > 0). */
internal data class DepartureChip(val text: String, val warn: Boolean, val tag: String)

internal fun departureChips(summary: DepartureSummary): List<DepartureChip> = if (summary.pickup) pickupDepartureChips(summary) else buildList {
    add(DepartureChip("탑승 ${summary.boarded}명 / 예정 ${summary.expected}명", warn = false, tag = "departure-riders"))
    add(DepartureChip("벨트 미확인 ${summary.beltUnchecked}명", warn = summary.beltUnchecked > 0, tag = "departure-belt"))
    if (summary.undecided > 0) add(DepartureChip("아직 결정 안 된 어린이 ${summary.undecided}명", warn = true, tag = "departure-undecided"))
}

/**
 * VS-05·VS-06 등원 출발 전 칩. 출발 때 탄 아이가 없으므로 벨트 칩·0명 탑승 경고가 없다.
 * "미탑승 예정인데 미확인"은 정류장에서 확인하면 되므로 경고 톤이 아니다(막지 않음).
 */
internal fun pickupDepartureChips(summary: DepartureSummary): List<DepartureChip> = buildList {
    add(DepartureChip("탈 어린이 ${summary.pickupRiders}명", warn = false, tag = "departure-riders"))
    if (summary.confirmedNotBoarded > 0) add(DepartureChip("미탑승 확인 ${summary.confirmedNotBoarded}명", warn = false, tag = "departure-not-boarded"))
    if (summary.noticeUnconfirmed > 0) add(DepartureChip("미탑승 예정 · 미확인 ${summary.noticeUnconfirmed}명", warn = false, tag = "departure-notice-unconfirmed"))
}

/** "첫 정류장 서울숲 · 경로 서울숲 → 국립중앙박물관 → 올림픽공원". 등원은 끝에 "→ 기관"(다음 기관). */
internal fun departureRouteText(summary: DepartureSummary): String =
    if (summary.stopNames.isEmpty()) "들를 정류장 없음"
    else "첫 정류장 ${summary.firstStopName} · 경로 ${summary.stopNames.joinToString(" → ")}" + if (summary.pickup) " → 기관" else ""

/** 정류장마다 더하는 정차 시간(분). 서버 운행 응답에 정차 시간이 없어 고정값을 쓴다. */
internal const val ROUTE_DWELL_MINUTES = 3

/** 지금 시각(하루 중 분). 경로 확인의 예상 도착 시각은 "지금 출발" 기준으로 어림한다. */
internal fun currentClockMinute(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

/** [startMinute]에 출발한다고 보고, 직선거리로 어림한 이동 시간(RoutePlanner TIME 행렬)과 정차 시간으로 도착 시각을 계산한다. */
internal fun routeTimeline(trip: TripState, order: List<String>, startMinute: Int = currentClockMinute()): RouteTimeline = RouteTimelines.build(
    originId = trip.origin.id,
    order = order,
    matrix = RoutePlanner.syntheticMatrix(trip.origin, trip.stops, RouteObjective.TIME),
    startMinute = startMinute,
    dwellMinutes = ROUTE_DWELL_MINUTES,
    returnToOrigin = trip.returnToOrigin,
)

/** 정류장에서 내릴(등원은 탈) 아이 이름. 운행 준비에는 오늘 탈 아이(미탑승 예정·미탑승 확인 제외), 출발 뒤에는 탄 아이만. */
internal fun ridersAt(trip: TripState, stopId: String): List<String> = trip.children
    .filter { it.stopId == stopId && ridesFrom(trip, it) }
    .map { it.name }

/** 평소와 달라진 점(L2). 지금은 탑승 기록만으로 알 수 있는 것만 보여준다. 결석·인계자 변경 연동은 4단계. */
internal data class RouteChange(val kind: String, val text: String)

internal fun routeChanges(trip: TripState): List<RouteChange> = if (trip.isPickup) pickupRouteChanges(trip) else buildList {
    trip.children.filter { it.status == Attendance.NOT_BOARDED || it.status == Attendance.CONFIRMED_NOT_BOARDED }.forEach {
        add(RouteChange("빠짐", "${it.name} · ${if (it.status == Attendance.CONFIRMED_NOT_BOARDED) "미탑승 확인" else "탑승 기록 없음"}"))
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

/** 등원 경로 확인의 달라진 점: 미탑승 예정(알림)·출발 전 미탑승 확인 아이, 탈 어린이가 없는 정류장, 뺀 정류장. */
private fun pickupRouteChanges(trip: TripState): List<RouteChange> = buildList {
    trip.children.filter { it.status == Attendance.CONFIRMED_NOT_BOARDED || !it.expectedToBoard }.forEach {
        add(RouteChange("빠짐", "${it.name} · ${if (it.status == Attendance.CONFIRMED_NOT_BOARDED) "미탑승 확인" else com.hanhyo.pleosshuttle.domain.PickupFlow.notBoardingBadge(it)}"))
    }
    trip.routeOrder.mapNotNull { id -> trip.stops.find { it.id == id } }
        .filter { stop -> ridersAt(trip, stop.id).isEmpty() }.forEach {
        add(RouteChange("탑승 없음", "${it.shortName} · 빼기 전까지 순서에 남아요"))
    }
    trip.excludedStops.mapNotNull { id -> trip.stops.find { it.id == id } }.forEach {
        add(RouteChange("뺀 정류장", "${it.shortName} · 방문 순서에서 뺐어요"))
    }
}

internal fun clockText(minute: Int): String = RouteTimelines.formatClock(minute)

/** 하루 중 분 → "오후 4시 5분", 정각이면 "오후 4시". 경로 화면에서 운전자·선생님이 바로 읽는 시각. */
internal fun koreanClockText(minute: Int): String {
    val normalized = ((minute % 1_440) + 1_440) % 1_440
    val hour = normalized / 60
    val min = normalized % 60
    val half = if (hour < 12) "오전" else "오후"
    val hour12 = if (hour % 12 == 0) 12 else hour % 12
    return if (min == 0) "$half ${hour12}시" else "$half ${hour12}시 ${min}분"
}

/** "오후 4시 출발" */
internal fun departureClockText(minute: Int): String = "${koreanClockText(minute)} 출발"

/** "오후 4시 23분 도착 예정" */
internal fun arrivalClockText(minute: Int): String = "${koreanClockText(minute)} 도착 예정"
