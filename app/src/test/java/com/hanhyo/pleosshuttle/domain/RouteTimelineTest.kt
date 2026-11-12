package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

/** 통학메이트 디자인 시안(L9 순서 바꾸기, 프로토타입)과 같은 합성 값으로 계산을 고정한다. */
class RouteTimelineTest {
    private fun symmetric(legs: Map<Pair<String, String>, Double>): CostMatrix {
        val costs = legs.flatMap { (pair, minutes) -> listOf(pair to minutes, (pair.second to pair.first) to minutes) }.toMap()
        return CostMatrix(costs, "모의 분", "디자인 시안 합성 이동 시간", synthetic = true)
    }

    /** 하원: S=햇살어린이집, A=푸른마을 3단지 정문, B=새싹도서관 앞, C=별빛초 후문, D=하늘공원 입구 */
    private val afternoon = symmetric(mapOf(
        ("S" to "A") to 12.0, ("S" to "B") to 10.0, ("S" to "C") to 8.0, ("S" to "D") to 9.0,
        ("A" to "B") to 6.0, ("A" to "C") to 9.0, ("A" to "D") to 11.0,
        ("B" to "C") to 4.0, ("B" to "D") to 8.0, ("C" to "D") to 5.0,
    ))

    /** 등원: 같은 지점, 아침 합성 이동 시간 */
    private val morning = symmetric(mapOf(
        ("S" to "A") to 9.0, ("S" to "B") to 9.0, ("S" to "C") to 7.0, ("S" to "D") to 4.0,
        ("A" to "B") to 5.0, ("A" to "C") to 8.0, ("A" to "D") to 10.0,
        ("B" to "C") to 4.0, ("B" to "D") to 7.0, ("C" to "D") to 4.0,
    ))

    private val afternoonRules = listOf(
        StopRule("D", earliestArrivalMinute = 16 * 60, earliestLabel = "서아 할머니와의 약속(16:00 이후)"),
        StopRule("A", allowedPreviousIds = setOf("S"), approachMessage = "단지 반대쪽 차로로 들어가 길을 건너 내릴 수 있어요"),
    )

    private fun clocks(timeline: RouteTimeline) = timeline.stops.map { RouteTimelines.formatClock(it.arrivalMinute) }

    @Test fun `제안 순서는 시안의 도착 시각과 복귀 시각을 만든다`() {
        val timeline = RouteTimelines.build("S", listOf("A", "B", "C", "D"), afternoon, RouteTimelines.parseClock("15:30"), dwellMinutes = 3)
        assertEquals(listOf("15:42", "15:51", "15:58", "16:06"), clocks(timeline))
        assertEquals("16:18", RouteTimelines.formatClock(timeline.endMinute))
        assertEquals(48, timeline.totalMinutes)
        assertEquals(9, timeline.returnLegMinutes)
        assertTrue(timeline.synthetic)
        assertTrue(RouteTimelines.warnings(timeline, afternoonRules).isEmpty())
    }

    @Test fun `하늘공원을 두 칸 올리면 도착 시각을 다시 계산하고 약속 시간 경고를 낸다`() {
        val suggested = listOf("A", "B", "C", "D")
        val moved = RouteTimelines.move(RouteTimelines.move(suggested, 3, -1), 2, -1)
        assertEquals(listOf("A", "D", "B", "C"), moved)
        val base = RouteTimelines.build("S", suggested, afternoon, 930, 3)
        val draft = RouteTimelines.build("S", moved, afternoon, 930, 3)
        assertEquals(listOf("15:42", "15:56", "16:07", "16:14"), clocks(draft))
        assertEquals("16:25", RouteTimelines.formatClock(draft.endMinute))
        assertEquals(7, draft.endMinute - base.endMinute)
        val warnings = RouteTimelines.warnings(draft, afternoonRules)
        assertEquals(1, warnings.size)
        assertEquals(RouteWarningKind.TOO_EARLY, warnings.single().kind)
        assertEquals("D", warnings.single().stopId)
        assertEquals(4, warnings.single().minutes)
        assertEquals("서아 할머니와의 약속(16:00 이후)보다 4분 일찍 도착해요", warnings.single().message)
    }

    @Test fun `허용되지 않은 직전 지점에서 들어오면 진입 방향 경고를 낸다`() {
        val draft = RouteTimelines.build("S", listOf("B", "A", "C", "D"), afternoon, 930, 3)
        val approach = RouteTimelines.warnings(draft, afternoonRules).filter { it.kind == RouteWarningKind.APPROACH_DIRECTION }
        assertEquals(listOf("A"), approach.map { it.stopId })
    }

    @Test fun `등원은 기관 도착 마감을 넘으면 늦은 분을 알려준다`() {
        val suggested = RouteTimelines.build("S", listOf("D", "C", "B", "A"), morning, RouteTimelines.parseClock("08:20"), 3)
        assertEquals(listOf("08:24", "08:31", "08:38", "08:46"), clocks(suggested))
        assertEquals("08:58", RouteTimelines.formatClock(suggested.endMinute))
        assertTrue(RouteTimelines.warnings(suggested, emptyList(), endDeadlineMinute = 9 * 60).isEmpty())
        val late = RouteTimelines.build("S", listOf("B", "D", "A", "C"), morning, 500, 3)
        val warning = RouteTimelines.warnings(late, emptyList(), endDeadlineMinute = 540).single()
        assertEquals(RouteWarningKind.END_DEADLINE, warning.kind)
        assertEquals(13, warning.minutes)
        assertEquals("09:00보다 13분 늦어요", warning.message)
    }

    @Test fun `끝에서 더 옮기면 순서를 그대로 두고 잘못된 위치는 거부한다`() {
        val order = listOf("A", "B", "C")
        assertEquals(order, RouteTimelines.move(order, 0, -1))
        assertEquals(order, RouteTimelines.move(order, 2, 1))
        assertEquals(listOf("B", "A", "C"), RouteTimelines.move(order, 0, 1))
        assertThrows(IllegalArgumentException::class.java) { RouteTimelines.move(order, 3, -1) }
    }

    @Test fun `복귀하지 않으면 마지막 정류장 출발 시각에서 끝난다`() {
        val timeline = RouteTimelines.build("S", listOf("A"), afternoon, 930, 3, returnToOrigin = false)
        assertNull(timeline.returnLegMinutes)
        assertEquals(945, timeline.endMinute)
        assertEquals(930, RouteTimelines.build("S", emptyList(), afternoon, 930, 3).endMinute)
    }

    @Test fun `시각 변환은 하루 범위를 지킨다`() {
        assertEquals(930, RouteTimelines.parseClock("15:30"))
        assertEquals("00:05", RouteTimelines.formatClock(1_445))
        assertThrows(IllegalArgumentException::class.java) { RouteTimelines.parseClock("24:00") }
    }
}
