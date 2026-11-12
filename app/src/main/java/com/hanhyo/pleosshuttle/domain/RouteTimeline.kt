package com.hanhyo.pleosshuttle.domain

import kotlin.math.roundToInt

/*
 * 순서 바꾸기 화면의 도착 시각 재계산과 경고(docs/design/FLOW.md '순서 바꾸기 계산').
 * CostMatrix 값을 '분'으로 해석한다. 합성 행렬이면 결과도 합성 시각이며 실제 도착 예측이 아니다.
 */

/** 정류장별 규칙. 시각은 자정 기준 분(예: 16:00 = 960). */
data class StopRule(
    val stopId: String,
    /** 이 시각보다 일찍 도착하면 경고. 예: 보호자와 16:00 이후 약속. */
    val earliestArrivalMinute: Int? = null,
    /** 경고 문구 앞부분. 예: "서아 할머니와의 약속(16:00 이후)" */
    val earliestLabel: String? = null,
    /** 이 지점에서 바로 올 때만 인도 쪽으로 내릴 수 있다. null이면 제약 없음. */
    val allowedPreviousIds: Set<String>? = null,
    val approachMessage: String? = null,
    /** 경로 확인 화면의 정류장 메모(정차 위치·진입 방향 등). */
    val memo: String? = null,
)

data class TimedStop(
    val stopId: String,
    val previousId: String,
    val legMinutes: Int,
    val arrivalMinute: Int,
)

data class RouteTimeline(
    val originId: String,
    val order: List<String>,
    val startMinute: Int,
    val dwellMinutes: Int,
    val stops: List<TimedStop>,
    /** 출발지로 돌아가지 않으면 null. */
    val returnLegMinutes: Int?,
    /** 복귀(등원은 기관 도착) 시각. 복귀하지 않으면 마지막 정류장 출발 시각. */
    val endMinute: Int,
    val synthetic: Boolean,
) {
    val totalMinutes: Int get() = endMinute - startMinute
    fun arrivalOf(stopId: String): Int? = stops.firstOrNull { it.stopId == stopId }?.arrivalMinute
}

enum class RouteWarningKind { TOO_EARLY, APPROACH_DIRECTION, END_DEADLINE }

data class RouteWarning(
    val kind: RouteWarningKind,
    /** END_DEADLINE이면 null. */
    val stopId: String?,
    /** 이르거나 늦은 분. APPROACH_DIRECTION이면 null. */
    val minutes: Int?,
    val message: String,
)

object RouteTimelines {
    /**
     * 도착 시각 = 출발 시각 + 이전 구간 이동 누적 + 앞 정류장 정차 누적.
     * 복귀 시각 = 마지막 도착 + 정차 + 출발지까지 이동.
     */
    fun build(
        originId: String,
        order: List<String>,
        matrix: CostMatrix,
        startMinute: Int,
        dwellMinutes: Int,
        returnToOrigin: Boolean = true,
    ): RouteTimeline {
        require(dwellMinutes >= 0) { "정차 시간은 0분 이상이어야 합니다." }
        require(order.toSet().size == order.size) { "정류장 ID는 중복될 수 없습니다." }
        require(originId !in order) { "출발지는 방문 지점과 다른 ID를 사용합니다." }
        var clock = startMinute
        var previous = originId
        val timed = order.map { id ->
            val leg = matrix.cost(previous, id).roundToInt()
            clock += leg
            val stop = TimedStop(id, previous, leg, clock)
            clock += dwellMinutes
            previous = id
            stop
        }
        if (order.isEmpty()) clock = startMinute
        val returnLeg = if (returnToOrigin && order.isNotEmpty()) matrix.cost(previous, originId).roundToInt() else null
        if (returnLeg != null) clock += returnLeg
        return RouteTimeline(originId, order, startMinute, dwellMinutes, timed, returnLeg, clock, matrix.synthetic)
    }

    /** index 위치의 정류장을 delta(-1 위로, +1 아래로)만큼 옮긴다. 범위를 벗어나면 그대로 둔다. */
    fun move(order: List<String>, index: Int, delta: Int): List<String> {
        require(index in order.indices) { "옮길 정류장 위치가 범위를 벗어났습니다." }
        val target = index + delta
        if (target !in order.indices || delta == 0) return order
        return order.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun warnings(timeline: RouteTimeline, rules: List<StopRule>, endDeadlineMinute: Int? = null): List<RouteWarning> = buildList {
        val byStop = rules.associateBy { it.stopId }
        timeline.stops.forEach { stop ->
            val rule = byStop[stop.stopId] ?: return@forEach
            val earliest = rule.earliestArrivalMinute
            if (earliest != null && stop.arrivalMinute < earliest) {
                val early = earliest - stop.arrivalMinute
                add(RouteWarning(RouteWarningKind.TOO_EARLY, stop.stopId, early, "${rule.earliestLabel ?: "약속 시간(${formatClock(earliest)} 이후)"}보다 ${early}분 일찍 도착해요"))
            }
            val allowed = rule.allowedPreviousIds
            if (allowed != null && stop.previousId !in allowed) {
                add(RouteWarning(RouteWarningKind.APPROACH_DIRECTION, stop.stopId, null, rule.approachMessage ?: "진입 방향 때문에 길을 건너 내릴 수 있어요"))
            }
        }
        if (endDeadlineMinute != null && timeline.endMinute > endDeadlineMinute) {
            val late = timeline.endMinute - endDeadlineMinute
            add(RouteWarning(RouteWarningKind.END_DEADLINE, null, late, "${formatClock(endDeadlineMinute)}보다 ${late}분 늦어요"))
        }
    }

    fun parseClock(text: String): Int {
        val parts = text.split(":")
        require(parts.size == 2) { "시각은 HH:mm 형식이어야 합니다." }
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        require(hour in 0..23 && minute in 0..59) { "시각 범위를 벗어났습니다." }
        return hour * 60 + minute
    }

    fun formatClock(minuteOfDay: Int): String {
        val normalized = ((minuteOfDay % 1_440) + 1_440) % 1_440
        return "%02d:%02d".format(normalized / 60, normalized % 60)
    }
}
