package com.hanhyo.pleosshuttle.domain

/**
 * V-08 출발 전 확인(v1.2 기사 함께 확인, D15·D37). 요약 숫자·음성 문장·기사 확인 해제 기준을 순수 함수로 둔다.
 * 출발을 막는 데 쓰지 않는다(경고만).
 */
data class DepartureSummary(
    /** 탑승 기록이 있는 아이 수. */
    val boarded: Int,
    /** 오늘 탑승 예정 아이 수(D20 기본값 탑승). */
    val expected: Int,
    /** 탑승 중인데 벨트를 아직 확인하지 않은 아이 수(V-05). */
    val beltUnchecked: Int,
    /** 탑승 기록도 미탑승 확인도 없는 아이 수. */
    val undecided: Int,
    /** 방문 순서대로 정류장 이름(내릴 아이가 없는 정류장도 순서에 남는다). */
    val stopNames: List<String>,
    val driverChecked: Boolean,
) {
    val firstStopName: String? get() = stopNames.firstOrNull()
}

/** 화면·음성에 쓰는 짧은 정류장 이름. "가상 정류장 A · 서울숲" → "서울숲", "OO · 가상" → "OO". */
fun stopLabel(stop: Stop): String =
    if (stop.name.endsWith(" · 가상")) stop.name.removeSuffix(" · 가상") else stop.name.substringAfter(" · ")

/** 출발 때 실제로 쓸 방문 순서. 예시 순서면 [운행 시작]이 자동 계산할 결과(RoutePlanner.plan)를 미리 쓴다. */
fun departureRouteOrder(trip: TripState): List<String> =
    if (trip.phase == TripPhase.PREPARING && trip.routeIsExample) runCatching { RoutePlanner.plan(trip).stopOrder }.getOrDefault(trip.routeOrder)
    else trip.routeOrder

/** 흐름 카드가 [운행 시작] 단계(start-trip)인 조건: 운행 준비 · 탈 아이 있음 · 모두 결정 · 탑승 1명 이상. */
val TripState.awaitingDeparture: Boolean
    get() = phase == TripPhase.PREPARING && !noTripToday && undecidedChildren.isEmpty() && onboardChildren.isNotEmpty()

fun departureSummary(trip: TripState, order: List<String> = departureRouteOrder(trip)): DepartureSummary = DepartureSummary(
    boarded = trip.onboardChildren.size,
    expected = trip.expectedRiderCount,
    beltUnchecked = trip.beltUncheckedCount,
    undecided = trip.undecidedChildren.size,
    stopNames = order.mapNotNull { id -> trip.stops.find { it.id == id }?.let(::stopLabel) },
    driverChecked = trip.driverCheckedAtMillis != null,
)

/** V-08 음성 요약(V-15·V-16과 같은 존댓말 짧은 문장). 기사님이 듣고 말로 답하면 선생님이 체크한다(D37). */
fun departureSpeech(summary: DepartureSummary): String = buildString {
    append("출발 전 확인입니다. 탑승 ${summary.boarded}명")
    if (summary.expected != summary.boarded) append(", 예정 ${summary.expected}명")
    summary.firstStopName?.let { append(", 첫 정류장 $it") }
    if (summary.stopNames.isNotEmpty()) append(", 경로 ${summary.stopNames.joinToString(", ")}")
    append(". ")
    append(if (summary.beltUnchecked > 0) "벨트 미확인 ${summary.beltUnchecked}명. " else "벨트는 모두 확인했습니다. ")
    if (summary.undecided > 0) append("아직 결정 안 된 어린이 ${summary.undecided}명. ")
    append("기사님, 확인해 주세요.")
}

/**
 * 기사 확인을 풀어야 하는 "출발 전 변경"의 기준(D15 "탑승 인원 변경 포함"): 아이별 출결 상태와 출발 방문 순서.
 * 벨트 확인은 넣지 않는다 — 기사 확인 뒤 선생님이 벨트를 마저 확인할 때마다 기사에게 다시 묻게 되기 때문.
 */
fun departureChangeKey(trip: TripState): List<Any?> =
    listOf(trip.children.map { it.id to it.status }, departureRouteOrder(trip))

object DriverCheckRecords {
    const val CHECKED = "기사 확인 · 기사 구두 답을 듣고 선생님이 체크"
    const val UNCHECKED = "기사 확인 해제 · 선생님"
    const val MISSING = "기사 확인 없음 · 체크 없이 출발"
    fun autoCleared(cause: String) = "기사 확인 자동 해제 · 출발 전 변경($cause)"
}
