package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.SampleTrip
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderBoardTest {
    private fun TripState.with(vararg status: Pair<String, Attendance>) =
        copy(children = children.map { c -> status.toMap()[c.id]?.let { c.copy(status = it) } ?: c })
    private fun board(trip: TripState, stationary: Boolean = true, ux: Boolean = false) =
        riderBoard(trip, trip.routeOrder, stationary = stationary, uxRestricted = ux)

    @Test fun `undecided status label is 탑승 전 and enum names stay`() {
        assertEquals("탑승 전", Attendance.NOT_BOARDED.label)
        assertEquals("NOT_BOARDED", Attendance.NOT_BOARDED.name)
        assertEquals("탑승 전", riderStatusLabel(Attendance.NOT_BOARDED))
        assertEquals("하차 완료", riderStatusLabel(Attendance.ALIGHTED))
        assertEquals("미탑승 확인", riderStatusLabel(Attendance.CONFIRMED_NOT_BOARDED))
    }

    @Test fun `each status gets its own tone`() {
        val tones = Attendance.entries.map { riderTone(it) }
        assertEquals(tones.size, tones.distinct().size)
        assertEquals(ChipTone.INFO, riderTone(Attendance.ONBOARD))
        assertEquals(ChipTone.MUTED, riderTone(Attendance.ALIGHTED))
        assertEquals(ChipTone.NEUTRAL, riderTone(Attendance.NOT_BOARDED))
        assertEquals(ChipTone.ACCENT, riderTone(Attendance.CONFIRMED_NOT_BOARDED))
    }

    @Test fun `rows follow visit order then name`() {
        val base = SampleTrip.create()
        val trip = base.copy(children = listOf(
            ChildRecord("z", "하늘", "forest", ""),
            ChildRecord("p", "다온", "park", ""),
            ChildRecord("a", "가람", "forest", ""),
            ChildRecord("m", "나래", "museum", ""),
            ChildRecord("x", "바다", "unknown-stop", ""),
        ))
        val order = listOf("park", "forest", "museum")
        val rows = riderBoard(trip, order, stationary = true, uxRestricted = false).rows
        assertEquals(listOf("p", "a", "z", "m", "x"), rows.map { it.id })
        assertEquals("다온", rows.first().name)
        // 방문 순서에서 빠진 정류장(예: 탈 아이가 없어 제외)도 행은 남는다.
        val dropped = riderBoard(trip, listOf("museum"), stationary = true, uxRestricted = false).rows
        assertEquals(listOf("m", "a", "z", "p", "x"), dropped.map { it.id })
    }

    @Test fun `preparing and stopped rows offer the same actions as the flow card`() {
        val trip = SampleTrip.create().let { t ->
            t.copy(children = t.children.map { if (it.id == "child-b") it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") else it })
        }.with("child-c" to Attendance.ONBOARD, "child-d" to Attendance.CONFIRMED_NOT_BOARDED)
        val model = board(trip)
        assertFalse(model.readOnly)
        val byId = model.rows.associateBy { it.id }
        assertEquals(listOf(RiderAction.BOARD, RiderAction.CONFIRM_NOT_BOARDED), byId.getValue("child-a").actions)
        assertEquals(listOf(RiderAction.CONFIRM_NOT_BOARDED, RiderAction.BOARD), byId.getValue("child-b").actions)
        assertEquals("오늘 미탑승 예정 · 결석", byId.getValue("child-b").badge)
        assertTrue(byId.getValue("child-c").actions.isEmpty())
        assertEquals(listOf(RiderAction.UNDO_NOT_BOARDED), byId.getValue("child-d").actions)
        assertEquals("rider-board-child-a", riderActionTag(RiderAction.BOARD, "child-a"))
        assertNull(byId.getValue("child-a").belt)
    }

    @Test fun `no trip today rows cannot board or confirm`() {
        val trip = SampleTrip.create().let { t -> t.copy(children = t.children.map { it.copy(notExpectedToBoard = true) }) }
        assertTrue(trip.noTripToday)
        assertTrue(board(trip).rows.all { it.actions.isEmpty() })
    }

    @Test fun `read only when moving, ux restricted or not stopped`() {
        val preparing = SampleTrip.create().let { t -> t.copy(children = t.children.map { if (it.id == "child-b") it.copy(notExpectedToBoard = true) else it }) }
        val notStopped = board(preparing, stationary = false)
        assertTrue(notStopped.readOnly)
        assertFalse(notStopped.namesAndStatusOnly)
        assertTrue(notStopped.rows.all { it.actions.isEmpty() })

        val ux = board(preparing, ux = true)
        assertTrue(ux.readOnly && ux.namesAndStatusOnly)
        assertTrue(ux.rows.all { it.actions.isEmpty() && it.badge == null && it.stopName == null })

        val driving = preparing.with("child-a" to Attendance.ONBOARD).copy(phase = TripPhase.DRIVING)
        val model = board(driving, stationary = true)
        assertTrue(model.readOnly && model.namesAndStatusOnly)
        assertTrue(model.rows.all { it.actions.isEmpty() && it.badge == null && it.stopName == null })
        assertEquals("운행 중에는 조회만 할 수 있어요", model.note)
    }

    @Test fun `alighting shows 하차 완료 and highlights current stop riders only`() {
        val trip = SampleTrip.create()
            .with("child-a" to Attendance.ALIGHTED, "child-b" to Attendance.ONBOARD, "child-c" to Attendance.ONBOARD, "child-d" to Attendance.CONFIRMED_NOT_BOARDED)
            .copy(phase = TripPhase.ALIGHTING, currentStopIndex = 0)
        val model = board(trip)
        assertTrue(model.readOnly)
        val byId = model.rows.associateBy { it.id }
        assertEquals("하차 완료", byId.getValue("child-a").statusLabel)
        assertEquals(RiderHighlight.CURRENT_STOP, byId.getValue("child-a").highlight)
        assertEquals(RiderHighlight.CURRENT_STOP, byId.getValue("child-b").highlight)
        assertEquals(RiderHighlight.NONE, byId.getValue("child-c").highlight)
        assertEquals(RiderHighlight.NONE, byId.getValue("child-d").highlight)
    }

    @Test fun `driving highlights riders of the next stop`() {
        val trip = SampleTrip.create()
            .with("child-a" to Attendance.ALIGHTED, "child-b" to Attendance.ALIGHTED, "child-c" to Attendance.ONBOARD, "child-d" to Attendance.ONBOARD)
            .copy(phase = TripPhase.DRIVING, currentStopIndex = 1)
        val byId = board(trip).rows.associateBy { it.id }
        assertEquals(RiderHighlight.CURRENT_STOP, byId.getValue("child-c").highlight)
        assertEquals(RiderHighlight.NONE, byId.getValue("child-d").highlight)
        assertEquals(RiderHighlight.NONE, byId.getValue("child-a").highlight)
    }

    @Test fun `end review highlights children still onboard`() {
        val trip = SampleTrip.create()
            .with("child-a" to Attendance.ALIGHTED, "child-b" to Attendance.ALIGHTED, "child-c" to Attendance.ONBOARD, "child-d" to Attendance.CONFIRMED_NOT_BOARDED)
            .copy(phase = TripPhase.END_REVIEW, currentStopIndex = 3)
        val model = board(trip)
        assertEquals(listOf("child-c"), model.rows.filter { it.highlight == RiderHighlight.REMAINING }.map { it.id })
        assertTrue(model.rows.all { it.actions.isEmpty() })
        assertEquals("차 안에 남은 기록 1명 · 직접 확인해 주세요", model.note)
        assertTrue(model.summary.contains("하차 완료 2"))

        val empty = board(trip.with("child-c" to Attendance.ALIGHTED))
        assertTrue(empty.rows.none { it.highlight == RiderHighlight.REMAINING })
    }
    // ---- V-05 벨트 수동 확인 ----
    private fun TripState.belted(vararg ids: String) = copy(children = children.map { if (it.id in ids) it.copy(beltConfirmed = true) else it })

    @Test fun `belt chip only for onboard rows and toggleable while preparing and stopped`() {
        val trip = SampleTrip.create().with("child-a" to Attendance.ONBOARD, "child-b" to Attendance.ONBOARD).belted("child-b")
        val byId = board(trip).rows.associateBy { it.id }
        assertEquals(RiderBelt(confirmed = false, toggleable = true, warn = false), byId.getValue("child-a").belt)
        assertEquals("벨트 미확인", byId.getValue("child-a").belt!!.label)
        assertEquals("벨트 확인", byId.getValue("child-b").belt!!.label)
        assertEquals(ChipTone.INFO, byId.getValue("child-b").belt!!.tone)
        assertNull(byId.getValue("child-c").belt)
        // 아직 탑승 결정 전 아이가 있으면 출발 단계가 아니므로 강조하지 않음
        assertTrue(board(trip).rows.none { it.belt?.warn == true })
        assertTrue(board(trip).summary.endsWith("벨트 미확인 1"))
        assertEquals("rider-belt-child-a", riderBeltTag("child-a"))
    }

    @Test fun `belt is read only when not stopped, ux restricted or driving`() {
        val trip = SampleTrip.create().with("child-a" to Attendance.ONBOARD)
        assertFalse(board(trip, stationary = false).rows.first { it.id == "child-a" }.belt!!.toggleable)
        assertFalse(board(trip, ux = true).rows.first { it.id == "child-a" }.belt!!.toggleable)
        val driving = board(trip.copy(phase = TripPhase.DRIVING)).rows.first { it.id == "child-a" }.belt!!
        assertFalse(driving.toggleable)
        assertFalse(driving.warn)
        val review = board(trip.copy(phase = TripPhase.END_REVIEW, currentStopIndex = 3))
        assertNull(review.rows.first { it.id == "child-a" }.belt)
        assertFalse(review.summary.contains("벨트"))
    }

    @Test fun `unchecked belts are highlighted at the start trip step`() {
        val trip = SampleTrip.create().with("child-a" to Attendance.ONBOARD, "child-b" to Attendance.ONBOARD,
            "child-c" to Attendance.ONBOARD, "child-d" to Attendance.CONFIRMED_NOT_BOARDED).belted("child-c")
        val model = board(trip)
        assertEquals(listOf("child-a", "child-b"), model.rows.filter { it.belt?.warn == true }.map { it.id })
        assertEquals(ChipTone.DANGER, model.rows.first { it.id == "child-a" }.belt!!.tone)
        assertTrue(model.beltWarning)
        assertEquals("벨트 미확인 2명 · 출발 전에 직접 확인해 주세요", model.note)
        assertTrue(model.summary.contains("벨트 미확인 2"))
        // 모두 확인하면 경고 없음
        val done = board(trip.belted("child-a", "child-b"))
        assertFalse(done.beltWarning)
        assertNull(done.note)
        assertTrue(done.summary.contains("벨트 미확인 0"))
    }

    @Test fun `at a stop riders who stay aboard are highlighted before re-departure`() {
        val trip = SampleTrip.create()
            .with("child-a" to Attendance.ALIGHTED, "child-b" to Attendance.ONBOARD, "child-c" to Attendance.ONBOARD, "child-d" to Attendance.ONBOARD)
            .belted("child-d").copy(phase = TripPhase.ALIGHTING, currentStopIndex = 0)
        val model = board(trip)
        val byId = model.rows.associateBy { it.id }
        // 이 정류장에서 내리는 아이(b)는 강조하지 않고, 계속 타고 가는 미확인 아이(c)만 강조
        assertFalse(byId.getValue("child-b").belt!!.warn)
        assertTrue(byId.getValue("child-c").belt!!.warn)
        assertFalse(byId.getValue("child-d").belt!!.warn)
        assertTrue(byId.values.mapNotNull { it.belt }.all { it.toggleable })
        assertTrue(model.readOnly) // 출결 버튼은 그대로 없음
        assertEquals(RiderHighlight.NONE, byId.getValue("child-c").highlight)
        assertEquals("벨트 미확인 1명 · 출발 전에 직접 확인해 주세요", model.note)
        assertNull(byId.getValue("child-a").belt)
    }
}
