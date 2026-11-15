package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 경로 확인 화면의 계산(직선거리 어림 이동 시간 · 정차 3분)을 테스트 고정값(출발 15:30 · SampleRoutePlan 규칙)으로 확인한다. */
class RoutePlanningTest {
    private val start = SampleRoutePlan.departureMinute
    private fun routeTimeline(trip: TripState, order: List<String>) = routeTimeline(trip, order, start)
    private val boarded = SampleTrip.create().let { trip -> trip.copy(children = trip.children.map { it.copy(status = Attendance.ONBOARD) }) }
    private fun clocks(trip: TripState, order: List<String>) = routeTimeline(trip, order).stops.map { clockText(it.arrivalMinute) }

    @Test fun `자동 계산 전에는 운행 시작 때 적용될 거리 기준 순서를 미리 보여준다`() {
        assertTrue(boarded.routeIsExample)
        assertEquals(listOf("forest", "park", "museum"), displayedRouteOrder(boarded))
        assertEquals(RoutePlanner.plan(boarded).stopOrder, displayedRouteOrder(boarded))
        val applied = boarded.copy(routeIsExample = false, routeOrder = listOf("park", "forest", "museum"))
        assertEquals(applied.routeOrder, displayedRouteOrder(applied))
    }

    @Test fun `제안 순서는 17시 15분에 복귀한다`() {
        val order = displayedRouteOrder(boarded)
        val timeline = routeTimeline(boarded, order)
        assertEquals(listOf("15:48", "16:16", "16:57"), clocks(boarded, order))
        assertEquals("17:15", clockText(timeline.endMinute))
        assertEquals(105, timeline.totalMinutes)
        assertTrue(timeline.synthetic)
        assertEquals(3, ROUTE_DWELL_MINUTES)
    }

    @Test fun `탑승 기록이 없는 아이와 내릴 친구가 없는 정류장을 달라진 점으로 보여준다`() {
        val trip = SampleTrip.create().let { t -> t.copy(children = t.children.map { if (it.id == "child-c") it else it.copy(status = Attendance.ONBOARD) }) }
        val changes = routeChanges(trip)
        assertEquals(listOf("빠짐", "하차 없음"), changes.map { it.kind })
        assertTrue(changes[0].text.startsWith("어린이 다"))
        assertTrue(changes[1].text.startsWith("국립중앙박물관"))
        assertTrue(routeChanges(boarded).isEmpty())
    }

    @Test fun `출발 전 확인 칩은 벨트 미확인과 미결정만 경고 톤으로 보이고 경로는 방문 순서대로 쓴다`() {
        val summary = departureSummary(boarded)
        assertEquals(listOf("탑승 4명 / 예정 4명", "벨트 미확인 4명"), departureChips(summary).map { it.text })
        assertEquals(listOf(false, true), departureChips(summary).map { it.warn })
        assertEquals("첫 정류장 서울숲 · 경로 서울숲 → 올림픽공원 → 국립중앙박물관", departureRouteText(summary))
        val calm = DepartureSummary(2, 3, 0, 1, emptyList(), driverChecked = false)
        assertEquals(listOf("탑승 2명 / 예정 3명", "벨트 미확인 0명", "아직 결정 안 된 어린이 1명"), departureChips(calm).map { it.text })
        assertEquals(listOf(false, false, true), departureChips(calm).map { it.warn })
        assertEquals("들를 정류장 없음", departureRouteText(calm))
    }
}
