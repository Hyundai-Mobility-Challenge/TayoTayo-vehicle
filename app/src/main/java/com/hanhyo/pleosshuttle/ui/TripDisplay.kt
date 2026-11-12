package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.Observation
import com.hanhyo.pleosshuttle.domain.Stop
import com.hanhyo.pleosshuttle.domain.TripDirections
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import com.hanhyo.pleosshuttle.ui.component.StopProgress
import com.hanhyo.pleosshuttle.ui.component.TimelineStop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun timeText(at: Long?): String = at?.let { SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(it)) } ?: "미수신"
/** "08:06" — 등원 보호자 호출 시각 등 분 단위 표시(VS-18 "호출함 HH:mm"). */
internal fun hourMinuteText(at: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
internal fun boolText(value: Boolean?, yes: String, no: String) = when (value) { true -> yes; false -> no; null -> "미확인" }
internal fun <T> valueLabel(value: Observation<T>, now: Long): String = if (value.isFresh(now)) value.value.toString() else "미확인/오래됨"

/** '가상 정류장 A · 서울숲' → '서울숲'. 화면에는 짧은 이름만 쓴다. */
internal val Stop.shortName: String get() = com.hanhyo.pleosshuttle.domain.stopLabel(this)

/**
 * 헤더·탭의 단계 칩 문구. 하원은 TripPhase.label 그대로. 등원은 정류장 정차(ALIGHTING)가 "하차"가 아니라
 * 인계받음·탑승 단계이므로 "정류장 탑승", 종료 확인(END_REVIEW)은 기관 도착 뒤라 "기관 인계"로 보인다.
 */
internal fun phaseLabel(trip: TripState): String = if (!trip.isPickup) trip.phase.label else when (trip.phase) {
    TripPhase.ALIGHTING -> "정류장 탑승"
    TripPhase.END_REVIEW -> "기관 인계"
    else -> trip.phase.label
}

/** 운행 화면 제목: 준비·완료 단계 이름 또는 지금 처리할 지점. */
internal fun flowTitle(trip: TripState): String = when (trip.phase) {
    TripPhase.PREPARING -> "탑승 준비"
    TripPhase.ENDED -> "${TripDirections.label(trip.direction)} 완료"
    else -> (trip.currentStop ?: trip.origin).shortName
}

/**
 * 출발 · 방문 순서 · 복귀를 타임라인 행으로 바꾼다. 선생님이 남긴 기록과 엔진 단계만 읽으며
 * 위치·센서로 완료를 추정하지 않는다.
 */
/**
 * 정류장 하차 인원에 들어가는 아이. 운행 준비에는 오늘 탈 아이(탑승 예정이고 미탑승 확인 전, 또는 탑승 기록 있음),
 * 출발 뒤에는 실제 탑승·하차 기록이 있는 아이만. "오늘 운행 없음"이면 모두 0명으로 보인다. 정류장 자체는 빼지 않는다(V-09).
 */
internal fun ridesFrom(trip: TripState, child: ChildRecord): Boolean =
    if (trip.phase == TripPhase.PREPARING) child.ridesToday
    // 등원(V-25): 출발 뒤에도 아직 정류장에서 결정 전인 아이는 그 정류장에서 탈 아이로 센다.
    else if (trip.isPickup) child.status != Attendance.CONFIRMED_NOT_BOARDED
    else child.status == Attendance.ONBOARD || child.status == Attendance.ALIGHTED

internal fun timelineStops(trip: TripState): List<TimelineStop> {
    val preparing = trip.phase == TripPhase.PREPARING
    val visiting = trip.phase == TripPhase.DRIVING || trip.phase == TripPhase.ALIGHTING
    val ended = trip.phase == TripPhase.ENDED
    val returning = trip.phase == TripPhase.END_REVIEW || (visiting && trip.currentStop == null)
    val boarded = trip.children.count { it.status == Attendance.ONBOARD || it.status == Attendance.ALIGHTED }
    val origin = TimelineStop(
        marker = "출",
        name = "${trip.origin.shortName} 출발",
        // 분모는 오늘 탈 아이(미탑승 예정·미탑승 확인 제외, 실제 탑승 기록은 포함).
        // 등원은 출발지에서 타는 아이가 없다(정류장에서 탑승). 준비 중엔 탈 어린이 수, 출발 뒤엔 "출발함"만.
        detail = if (preparing && trip.isPickup) "탈 어린이 ${trip.children.count { it.ridesToday }}명"
            else if (trip.isPickup) "출발함"
            else if (preparing) "탑승 기록 ${trip.onboardChildren.size}/${trip.children.count { it.ridesToday }}" else "탑승 ${boarded}명",
        progress = if (preparing) StopProgress.CURRENT else StopProgress.DONE,
    )
    val stops = trip.routeOrder.mapIndexedNotNull { index, id ->
        val stop = trip.stops.find { it.id == id } ?: return@mapIndexedNotNull null
        val riders = trip.children.filter { it.stopId == id && ridesFrom(trip, it) }
        val alighted = riders.count { it.status == Attendance.ALIGHTED }
        val progress = when {
            ended || returning -> StopProgress.DONE
            !visiting -> StopProgress.UPCOMING
            index < trip.currentStopIndex -> StopProgress.DONE
            index == trip.currentStopIndex -> StopProgress.CURRENT
            else -> StopProgress.UPCOMING
        }
        val boardedHere = riders.count { it.status == Attendance.ONBOARD || it.status == Attendance.ALIGHTED }
        val detail = if (trip.isPickup) when (progress) {
            StopProgress.CURRENT -> if (trip.phase == TripPhase.ALIGHTING) "탑승 기록 $boardedHere/${riders.size}" else "탑승 예정 ${riders.size}명"
            StopProgress.DONE -> "탑승 ${boardedHere}명"
            StopProgress.UPCOMING -> "탑승 ${riders.size}명"
        } else when (progress) {
            StopProgress.CURRENT -> if (trip.phase == TripPhase.ALIGHTING) "하차 기록 $alighted/${riders.size}" else "하차 예정 ${riders.size}명"
            StopProgress.DONE -> "하차 기록 ${alighted}명"
            StopProgress.UPCOMING -> "하차 ${riders.size}명"
        }
        TimelineStop("${index + 1}", stop.shortName, detail, progress)
    }
    val back = TimelineStop(
        marker = if (trip.isPickup) "기" else "복",
        name = if (trip.isPickup) "기관 도착" else "${trip.origin.shortName} 복귀",
        detail = if (ended) "종료 기록 저장" else if (trip.isPickup) "기관 인계 · 종료 점검" else "종료 점검",
        progress = when { ended -> StopProgress.DONE; returning -> StopProgress.CURRENT; else -> StopProgress.UPCOMING },
    )
    return listOf(origin) + stops + back
}
