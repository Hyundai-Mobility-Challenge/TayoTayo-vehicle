package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.ui.component.StopProgress
import org.junit.Assert.*
import org.junit.Test

class TripDisplayTest {
    private fun withStatus(trip: TripState, vararg statuses: Pair<String, Attendance>) =
        trip.copy(children = trip.children.map { child -> statuses.firstOrNull { it.first == child.id }?.let { child.copy(status = it.second) } ?: child })

    @Test fun `운행 준비에는 출발이 현재이고 정류장과 복귀는 모두 예정이다`() {
        val rows = timelineStops(withStatus(SampleTrip.create(), "child-a" to Attendance.ONBOARD))
        assertEquals(listOf("출", "1", "2", "3", "복"), rows.map { it.marker })
        assertEquals(StopProgress.CURRENT, rows.first().progress)
        assertEquals("탑승 기록 1/4", rows.first().detail)
        assertTrue(rows.drop(1).all { it.progress == StopProgress.UPCOMING })
        assertEquals("서울숲", rows[1].name)
        assertEquals("하차 예정 2명", rows[1].detail)
    }

    @Test fun `하차 중에는 지난 정류장이 완료이고 현재 정류장은 하차 기록 수를 보여준다`() {
        val trip = withStatus(
            SampleTrip.create(),
            "child-a" to Attendance.ALIGHTED, "child-b" to Attendance.ALIGHTED,
            "child-c" to Attendance.ALIGHTED, "child-d" to Attendance.ONBOARD,
        ).copy(phase = TripPhase.ALIGHTING, currentStopIndex = 2)
        val rows = timelineStops(trip)
        assertEquals(listOf(StopProgress.DONE, StopProgress.DONE, StopProgress.DONE, StopProgress.CURRENT, StopProgress.UPCOMING), rows.map { it.progress })
        assertEquals("하차 기록 0/1", rows[3].detail)
        assertEquals("하차 기록 2명", rows[1].detail)
        assertEquals("올림픽공원", flowTitle(trip))
    }

    @Test fun `탑승하지 않은 아이는 운행이 시작되면 정류장 하차 인원에서 빠진다`() {
        val trip = withStatus(SampleTrip.create(), "child-a" to Attendance.ONBOARD, "child-c" to Attendance.ONBOARD)
            .copy(phase = TripPhase.DRIVING)
        val rows = timelineStops(trip)
        assertEquals("탑승 2명", rows.first().detail)
        assertEquals("하차 예정 1명", rows[1].detail)
    }

    @Test fun `모든 정류장을 지나 복귀하는 동안과 종료 점검에서는 복귀가 현재다`() {
        val base = SampleTrip.create()
        val returning = base.copy(phase = TripPhase.DRIVING, currentStopIndex = base.routeOrder.size)
        assertEquals(StopProgress.CURRENT, timelineStops(returning).last().progress)
        assertTrue(timelineStops(returning).dropLast(1).all { it.progress == StopProgress.DONE })
        assertEquals(StopProgress.CURRENT, timelineStops(base.copy(phase = TripPhase.END_REVIEW)).last().progress)
    }

    @Test fun `운행을 마치면 모든 행이 완료이고 제목은 하원 완료다`() {
        val ended = SampleTrip.create().copy(phase = TripPhase.ENDED, currentStopIndex = 3)
        assertTrue(timelineStops(ended).all { it.progress == StopProgress.DONE })
        assertEquals("종료 기록 저장", timelineStops(ended).last().detail)
        assertEquals("하원 완료", flowTitle(ended))
    }
}
