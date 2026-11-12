package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

class FlowGuideTest {
    private val now = 1_000_000L
    private fun withOnePassenger() = SampleTrip.create().let { trip ->
        trip.copy(children = trip.children.map { if (it.id == "child-a") it.copy(status = Attendance.ONBOARD) else it })
    }
    private fun alighting() = withOnePassenger().copy(phase = TripPhase.ALIGHTING, manualSurroundingsCheckAtMillis = now)
    private fun attempt(status: ResultStatus, completed: Boolean = true, invalidated: Boolean = false) = UnlockAttempt(
        request = UnlockRequest("request-1", now, IntegrationMode.SIMULATION, "forest", 1, 1, true),
        status = status, observedLocked = if (status == ResultStatus.TARGET_OBSERVED) false else null,
        completedAtMillis = if (completed) now else null, invalidated = invalidated,
    )

    @Test fun `일부 어린이만 탑승해도 명시적으로 검토를 마치면 방문 순서를 안내한다`() {
        val trip = withOnePassenger()
        assertEquals("board-child-b", nextFlowGuide(trip).targetTag)
        assertEquals("start-trip", nextFlowGuide(trip, boardingReviewed = true).targetTag)
        assertEquals(1, trip.onboardChildren.size)
        assertEquals(TripPhase.PREPARING, trip.phase)
    }

    @Test fun `운행 시작 하나로 자동 계산과 기존 순서를 모두 사용할 수 있다`() {
        val trip = withOnePassenger()
        val proposal = RouteProposal(trip.routeOrder, 10.0, "km", "합성", RouteObjective.DISTANCE, true, 0, 6)
        assertEquals("start-trip", nextFlowGuide(trip.copy(routeProposal = proposal), boardingReviewed = true).targetTag)
        assertEquals("start-trip", nextFlowGuide(trip.copy(routeIsExample = false), boardingReviewed = true).targetTag)
    }

    @Test fun `별도 문 시험 진행 중에도 인계는 직접 확인하며 기록을 자동으로 바꾸지 않는다`() {
        val trip = alighting().copy(unlockAttempt = attempt(ResultStatus.ACCEPTED, completed = false))
        assertEquals("handover-child-a", nextFlowGuide(trip).targetTag)
        assertTrue(trip.handoverChildIds.isEmpty())
        assertEquals(Attendance.ONBOARD, trip.children.first().status)
    }

    @Test fun `유효한 해제 관측 뒤에도 인계 확인과 하차 기록을 따로 안내한다`() {
        val trip = alighting().copy(unlockAttempt = attempt(ResultStatus.TARGET_OBSERVED))
        assertEquals("handover-child-a", nextFlowGuide(trip).targetTag)
        val handedOver = trip.copy(handoverChildIds = setOf("child-a"))
        assertEquals("alight-child-a", nextFlowGuide(handedOver).targetTag)
        assertEquals(Attendance.ONBOARD, handedOver.children.first().status)
    }

    @Test fun `문 시험이 무효여도 별도 사유 없이 실제 인계를 확인할 수 있다`() {
        val trip = alighting().copy(unlockAttempt = attempt(ResultStatus.TARGET_OBSERVED, invalidated = true))
        assertEquals("handover-child-a", nextFlowGuide(trip).targetTag)
        assertNull(trip.manualDoorReason)
    }

    @Test fun `현재 지점 기록이 모두 끝나면 사유 입력 없이 다음 지점을 안내한다`() {
        val trip = alighting().let { it.copy(children = it.children.map { child -> if (child.id == "child-a") child.copy(status = Attendance.ALIGHTED) else child }) }
        assertEquals("complete-stop", nextFlowGuide(trip).targetTag)
        assertEquals(TripPhase.ALIGHTING, trip.phase)
        assertEquals(0, trip.currentStopIndex)
    }

    @Test fun `종료 시에는 실내 직접 확인과 저장을 한 동작으로 안내한다`() {
        val trip = SampleTrip.create().copy(phase = TripPhase.END_REVIEW, directCabinCheckAtMillis = now)
        assertEquals("confirm-finish", nextFlowGuide(trip).targetTag)
        assertNull(trip.endRecord)
    }

    @Test fun `진단과 관측 갱신은 자동 스크롤 키를 바꾸지 않고 문 결과는 바꾼다`() {
        val trip = alighting().copy(unlockAttempt = attempt(ResultStatus.ACCEPTED, completed = false))
        val refreshed = trip.copy(revision = trip.revision + 1, diagnostics = listOf(DiagnosticEntry(now + 500, "관측", ResultStatus.TARGET_OBSERVED, "갱신")))
        assertEquals(flowProgressKey(trip, false), flowProgressKey(refreshed, false))
        assertNotEquals(flowProgressKey(trip, false), flowProgressKey(trip.copy(unlockAttempt = attempt(ResultStatus.TARGET_OBSERVED)), false))
    }

    @Test fun `주변 위험 경고가 남으면 사유가 있어도 수동 문 처리를 안내하지 않는다`() {
        val trip = alighting()
        assertEquals("surroundings-status", nextFlowGuide(trip, surroundingsWarning = true).targetTag)
        assertEquals("surroundings-status", nextFlowGuide(trip, surroundingsWarning = true).targetTag)
        assertNull(trip.manualDoorReason)
    }

    @Test fun `정차가 미확인이거나 차량 UX 제한이면 다음 기록 조작을 안내하지 않는다`() {
        val trip = alighting().copy(unlockAttempt = attempt(ResultStatus.TARGET_OBSERVED))
        assertEquals("stationary-status", nextFlowGuide(trip, stationaryReady = false).targetTag)
        assertNull(nextFlowGuide(trip, uxRestricted = true).targetTag)
    }

    @Test fun `탈 어린이 0명이면 탑승 단계 대신 오늘 운행 없음을 안내한다`() {
        val absent = SampleTrip.create().let { t -> t.copy(children = t.children.map { it.copy(notExpectedToBoard = true) }) }
        assertEquals(NO_TRIP_TODAY_TAG, nextFlowGuide(absent).targetTag)
        assertEquals(NO_TRIP_TODAY_TAG, nextFlowGuide(absent, boardingReviewed = true, stationaryReady = false).targetTag)
        assertEquals(NO_TRIP_TODAY_TAG, nextFlowGuide(SampleTrip.create().copy(children = emptyList())).targetTag)
        val mixed = absent.copy(children = absent.children.map { if (it.id == "child-b") it.copy(notExpectedToBoard = false) else it })
        assertEquals("board-child-a", nextFlowGuide(mixed).targetTag)
    }
}
