package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.Observation
import com.hanhyo.pleosshuttle.domain.Stop
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import com.hanhyo.pleosshuttle.ui.component.StopProgress
import com.hanhyo.pleosshuttle.ui.component.TimelineStop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun timeText(at: Long?): String = at?.let { SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(it)) } ?: "미수신"
internal fun boolText(value: Boolean?, yes: String, no: String) = when (value) { true -> yes; false -> no; null -> "미확인" }
internal fun <T> valueLabel(value: Observation<T>, now: Long): String = if (value.isFresh(now)) value.value.toString() else "미확인/오래됨"

/** '가상 정류장 A · 서울숲' → '서울숲'. 화면에는 짧은 이름만 쓴다. */
internal val Stop.shortName: String get() = if (name.endsWith(" · 가상")) name.removeSuffix(" · 가상") else name.substringAfter(" · ")

/** 운행 화면 제목: 준비·완료 단계 이름 또는 지금 처리할 지점. */
internal fun flowTitle(trip: TripState): String = when (trip.phase) {
    TripPhase.PREPARING -> "탑승 준비"
    TripPhase.ENDED -> "하원 완료"
    else -> (trip.currentStop ?: trip.origin).shortName
}

/**
 * 출발 · 방문 순서 · 복귀를 타임라인 행으로 바꾼다. 선생님이 남긴 기록과 엔진 단계만 읽으며
 * 위치·센서로 완료를 추정하지 않는다.
 */
internal fun timelineStops(trip: TripState): List<TimelineStop> {
    val preparing = trip.phase == TripPhase.PREPARING
    val visiting = trip.phase == TripPhase.DRIVING || trip.phase == TripPhase.ALIGHTING
    val ended = trip.phase == TripPhase.ENDED
    val returning = trip.phase == TripPhase.END_REVIEW || (visiting && trip.currentStop == null)
    val boarded = trip.children.count { it.status != Attendance.NOT_BOARDED }
    val origin = TimelineStop(
        marker = "출",
        name = "${trip.origin.shortName} 출발",
        detail = if (preparing) "탑승 기록 ${trip.onboardChildren.size}/${trip.children.size}" else "탑승 ${boarded}명",
        progress = if (preparing) StopProgress.CURRENT else StopProgress.DONE,
    )
    val stops = trip.routeOrder.mapIndexedNotNull { index, id ->
        val stop = trip.stops.find { it.id == id } ?: return@mapIndexedNotNull null
        val riders = trip.children.filter { it.stopId == id && (preparing || it.status != Attendance.NOT_BOARDED) }
        val alighted = riders.count { it.status == Attendance.ALIGHTED }
        val progress = when {
            ended || returning -> StopProgress.DONE
            !visiting -> StopProgress.UPCOMING
            index < trip.currentStopIndex -> StopProgress.DONE
            index == trip.currentStopIndex -> StopProgress.CURRENT
            else -> StopProgress.UPCOMING
        }
        val detail = when (progress) {
            StopProgress.CURRENT -> if (trip.phase == TripPhase.ALIGHTING) "하차 기록 $alighted/${riders.size}" else "하차 예정 ${riders.size}명"
            StopProgress.DONE -> "하차 기록 ${alighted}명"
            StopProgress.UPCOMING -> "하차 ${riders.size}명"
        }
        TimelineStop("${index + 1}", stop.shortName, detail, progress)
    }
    val back = TimelineStop(
        marker = "복",
        name = "${trip.origin.shortName} 복귀",
        detail = if (ended) "종료 기록 저장" else "종료 점검",
        progress = when { ended -> StopProgress.DONE; returning -> StopProgress.CURRENT; else -> StopProgress.UPCOMING },
    )
    return listOf(origin) + stops + back
}
