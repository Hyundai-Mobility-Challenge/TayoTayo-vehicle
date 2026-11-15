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
    /** V-16: 벨트 미확인 아이의 음성용 이름(명단 순서). 이름은 차량 안 음성·화면에만 쓰고 키·원장·서버에는 넣지 않는다. */
    val beltUncheckedNames: List<String> = emptyList(),
    /** V-25 등원 운행이면 true. 출발 때 탄 아이가 없어 벨트 줄·0명 탑승 경고가 없다(VS-05 등원). */
    val pickup: Boolean = false,
    /** 등원: 오늘 탈 아이(미탑승 확인 제외, 미탑승 예정 미확인 포함 안 함) 수. */
    val pickupRiders: Int = 0,
    /** 등원: 출발 전 미탑승 확인한 아이 수. */
    val confirmedNotBoarded: Int = 0,
    /** 등원: 미탑승 예정 알림이 있는데 아직 미탑승 확인 전인 아이 수(정류장에서 확인 가능, 막지 않음). */
    val noticeUnconfirmed: Int = 0,
) {
    val firstStopName: String? get() = stopNames.firstOrNull()
}

/** 화면·음성에 쓰는 짧은 정류장 이름. "정류장 A · 서울숲" → "서울숲"(" · " 앞 구분 이름을 뗀다). */
fun stopLabel(stop: Stop): String = stop.name.substringAfter(" · ")

/** 출발 때 실제로 쓸 방문 순서. 예시 순서면 [운행 시작]이 자동 계산할 결과(RoutePlanner.plan)를 미리 쓴다. */
fun departureRouteOrder(trip: TripState): List<String> =
    if (trip.phase == TripPhase.PREPARING && trip.routeIsExample) runCatching { RoutePlanner.plan(trip).stopOrder }.getOrDefault(trip.routeOrder)
    else trip.routeOrder

/** 흐름 카드가 [운행 시작] 단계(start-trip)인 조건: 운행 준비 · 탈 아이 있음 · 모두 결정 · 탑승 1명 이상. */
val TripState.awaitingDeparture: Boolean
    get() = phase == TripPhase.PREPARING && !noTripToday &&
        // 등원(V-25)은 정류장에서 태우므로 출발 전 결정·탑승 기록이 필요 없다. 알림 없는 아이는 정류장에서 확정(결정 31).
        (isPickup || (undecidedChildren.isEmpty() && onboardChildren.isNotEmpty()))

fun departureSummary(trip: TripState, order: List<String> = departureRouteOrder(trip)): DepartureSummary = DepartureSummary(
    boarded = trip.onboardChildren.size,
    expected = trip.expectedRiderCount,
    beltUnchecked = trip.beltUncheckedCount,
    undecided = trip.undecidedChildren.size,
    stopNames = order.mapNotNull { id -> trip.stops.find { it.id == id }?.let(::stopLabel) },
    driverChecked = trip.driverCheckedAtMillis != null,
    beltUncheckedNames = trip.beltUncheckedChildren.map(::spokenChildName),
    pickup = trip.isPickup,
    pickupRiders = trip.children.count { it.ridesToday },
    confirmedNotBoarded = trip.confirmedNotBoardedChildren.size,
    noticeUnconfirmed = trip.children.count { !it.expectedToBoard && it.status == Attendance.NOT_BOARDED },
)

/** 음성으로 읽는 이름(V-15·V-16 공통). 화면 표시와 같다. */
fun spokenChildName(child: ChildRecord): String = child.name

/** V-16 음성 이름 목록 상한. 이보다 많으면 앞 [SPOKEN_NAMES_WHEN_TRUNCATED]명만 읽고 "외 n명"으로 줄인다. */
const val MAX_SPOKEN_NAMES = 4
const val SPOKEN_NAMES_WHEN_TRUNCATED = 3

/**
 * V-16 이름 나열. 4명까지는 모두 읽고, 5명 이상이면 앞 3명 + "외 n명"(한 문장이 4명 분량을 넘지 않게).
 * 예: "어린이 다, 어린이 라" / "어린이 가, 어린이 나, 어린이 다 외 3명".
 */
fun spokenNameList(names: List<String>): String =
    if (names.size <= MAX_SPOKEN_NAMES) names.joinToString(", ")
    else "${names.take(SPOKEN_NAMES_WHEN_TRUNCATED).joinToString(", ")} 외 ${names.size - SPOKEN_NAMES_WHEN_TRUNCATED}명"

/** V-08 음성 요약(V-15·V-16과 같은 존댓말 짧은 문장). 기사님이 듣고 말로 답하면 선생님이 체크한다(D37). */
fun departureSpeech(summary: DepartureSummary): String = if (summary.pickup) pickupDepartureSpeech(summary) else buildString {
    append("출발 전 확인입니다. 탑승 ${summary.boarded}명")
    if (summary.expected != summary.boarded) append(", 예정 ${summary.expected}명")
    summary.firstStopName?.let { append(", 첫 정류장 $it") }
    if (summary.stopNames.isNotEmpty()) append(", 경로 ${summary.stopNames.joinToString(", ")}")
    append(". ")
    append(when {
        summary.beltUnchecked <= 0 -> "벨트는 모두 확인했습니다. "
        // V-16: 출발 시 미확인 아이 이름 안내(인원 수 + 이름). 이름이 없으면(이전 호출 형식) 수만 읽는다.
        summary.beltUncheckedNames.isEmpty() -> "벨트 미확인 ${summary.beltUnchecked}명. "
        else -> "벨트 미확인 ${summary.beltUnchecked}명, ${spokenNameList(summary.beltUncheckedNames)}. "
    })
    if (summary.undecided > 0) append("아직 결정 안 된 어린이 ${summary.undecided}명. ")
    append("기사님, 확인해 주세요.")
}

/** VS-05 등원 음성 요약. 예: "출발 전 확인입니다. 탈 어린이 3명, 첫 정류장 서울숲, 다음 기관. 기사님, 확인해 주세요." */
fun pickupDepartureSpeech(summary: DepartureSummary): String = buildString {
    append("출발 전 확인입니다. 탈 어린이 ${summary.pickupRiders}명")
    summary.firstStopName?.let { append(", 첫 정류장 $it") }
    append(", 다음 기관. 기사님, 확인해 주세요.")
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
