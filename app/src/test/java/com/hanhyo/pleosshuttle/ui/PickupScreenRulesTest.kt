package com.hanhyo.pleosshuttle.ui

import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.PICKUP_INSTITUTION_TAG
import com.hanhyo.pleosshuttle.PICKUP_PREPARE_TAG
import com.hanhyo.pleosshuttle.PICKUP_STOP_TAG
import com.hanhyo.pleosshuttle.TrackingTransition
import com.hanhyo.pleosshuttle.automaticTrackingTransition
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.pleosshuttle.domain.PickupFlow.StopAction
import com.hanhyo.pleosshuttle.nextFlowGuide
import org.junit.Assert.*
import org.junit.Test

/** 등원 화면 규칙(VS-02 방향 기본값, VS-17 운행 준비, VS-18 정류장, VS-04 (b) 시트, VS-14 정류장 정정, VS-20 최소). */
class PickupScreenRulesTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SIMULATED, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)

    /** 모의 등원: 서울숲 가·나(나 결석 알림), 박물관 다, 올림픽공원 라. 고정 순서 forest → museum → park. */
    private fun pickupTrip() = SampleTrip.createPickup().copy(routeIsExample = false)
    private fun engine(trip: TripState = pickupTrip()) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }
    private fun ShuttleEngine.child(id: String) = state.children.first { it.id == id }
    private fun atForest() = engine().also { assertTrue(it.startDriving(now)); assertTrue(it.arriveAtPickupStop(now)) }
    private fun board(e: ShuttleEngine, id: String) {
        assertTrue(e.recordReceived(id, PickupReceivers.GUARDIAN, now)); assertTrue(e.board(id, now))
    }

    @Test fun `모의 등원 운행은 등원 방향이고 나만 결석 알림이다`() {
        val trip = SampleTrip.createPickup()
        assertTrue(trip.isPickup)
        assertEquals(listOf("child-b"), trip.children.filter { !it.expectedToBoard }.map { it.id })
        assertFalse(SampleTrip.create().isPickup) // 하원 체험은 그대로
    }

    @Test fun `VS-02 방향은 고른 적 없으면 시간대 기본값, 고른 값은 유지한다`() {
        assertEquals(TripDirections.PICK_UP, InstitutionTrips.effectiveDirection(null, 7))
        assertEquals(TripDirections.DROP_OFF, InstitutionTrips.effectiveDirection(null, 15))
        assertEquals(TripDirections.PICK_UP, InstitutionTrips.effectiveDirection("", 7)) // 빈 값 = 고른 적 없음
        assertEquals(TripDirections.DROP_OFF, InstitutionTrips.effectiveDirection(TripDirections.DROP_OFF, 7))
        assertEquals(TripDirections.PICK_UP, InstitutionTrips.effectiveDirection(TripDirections.PICK_UP, 16))
        assertEquals("등원", InstitutionTrips.directionLabel(TripDirections.PICK_UP))
    }

    @Test fun `VS-17 출발 전에는 미탑승 예정 아이만 미탑승 확인, 확인한 아이는 취소만`() {
        val e = engine()
        val board = riderBoard(e.state, stationary = true, uxRestricted = false)
        val actions = board.rows.associate { it.id to it.actions }
        assertEquals(listOf(RiderAction.CONFIRM_NOT_BOARDED), actions["child-b"])
        listOf("child-a", "child-c", "child-d").forEach { assertEquals(it, emptyList<RiderAction>(), actions[it]) } // 탑승 버튼 없음, 알림 없는 아이 확정 없음
        assertTrue(board.rows.all { it.statusLabel == "탑승 전" && it.stopName != null })
        assertEquals("미탑승 예정 · 결석 알림", board.rows.first { it.id == "child-b" }.badge)
        assertNull(board.rows.first { it.id == "child-a" }.badge)
        assertEquals(PICKUP_PREPARE_NOTE, board.note)

        assertTrue(e.confirmNotBoarded("child-b", now))
        val after = riderBoard(e.state, stationary = true, uxRestricted = false).rows.first { it.id == "child-b" }
        assertEquals(listOf(RiderAction.UNDO_NOT_BOARDED), after.actions)
        assertEquals("미탑승 확인", after.statusLabel)
        assertTrue(e.undoNotBoarded("child-b", now))
        assertEquals(Attendance.NOT_BOARDED, e.child("child-b").status)
        // 정차 확인이 없으면 버튼 없음(V-11)
        assertTrue(riderBoard(e.state, stationary = false, uxRestricted = false).rows.all { it.actions.isEmpty() })
    }

    @Test fun `VS-17 출발 전 확인은 벨트 줄 없이 탈 어린이와 첫 정류장, 다음 기관을 보인다`() {
        val e = engine()
        assertTrue(e.confirmNotBoarded("child-b", now))
        assertTrue(e.state.awaitingDeparture) // 등원은 탑승 기록 없이 출발 단계
        val summary = departureSummary(e.state)
        val chips = departureChips(summary)
        assertEquals(listOf("탈 어린이 3명", "미탑승 확인 1명"), chips.map { it.text })
        assertTrue(chips.none { it.warn || it.text.contains("벨트") })
        assertEquals("첫 정류장 서울숲 · 경로 서울숲 → 국립중앙박물관 → 올림픽공원 → 기관", departureRouteText(summary))
        assertEquals("출발 전 확인입니다. 탈 어린이 3명, 첫 정류장 서울숲, 다음 기관. 기사님, 확인해 주세요.", departureSpeech(summary))
        // 하원 요약은 그대로
        val dropoff = departureSummary(SampleTrip.create())
        assertTrue(departureChips(dropoff).any { it.text.startsWith("벨트 미확인") })
        assertFalse(departureRouteText(dropoff).contains("기관"))
    }

    @Test fun `VS-17 흐름 단계는 준비 마침 전 등원 준비, 뒤에 순서 확인`() {
        val trip = pickupTrip()
        assertEquals(PICKUP_PREPARE_TAG, nextFlowGuide(trip).targetTag)
        assertEquals("start-trip", nextFlowGuide(trip, boardingReviewed = true).targetTag)
        // 하원은 기존 단계 그대로(첫 아이 탑승 기록)
        assertEquals("board-child-a", nextFlowGuide(SampleTrip.create()).targetTag)
    }

    @Test fun `VS-18 행 동작은 인계받음 다음 탑승 다음 벨트, 안 나오면 도착 알림과 미탑승 확인`() {
        val e = atForest()
        assertEquals(PICKUP_STOP_TAG, nextFlowGuide(e.state).targetTag)
        fun actions(id: String) = PickupFlow.stopActions(e.state, e.child(id))
        assertEquals(listOf(StopAction.RECEIVE, StopAction.CALL_GUARDIAN, StopAction.CONFIRM_NOT_BOARDED), actions("child-a"))
        assertEquals(emptyList<StopAction>(), actions("child-c")) // 다른 정류장 아이
        assertEquals("이번 탑승", PickupFlow.statusLabel(e.state, e.child("child-a")))
        assertTrue(e.callGuardian("child-a", now))
        assertEquals(listOf(StopAction.RECEIVE, StopAction.CONFIRM_NOT_BOARDED), actions("child-a")) // 재호출 없음(D30)
        assertTrue(e.recordReceived("child-a", PickupReceivers.PROXY, now))
        assertEquals(listOf(StopAction.BOARD), actions("child-a"))
        assertEquals("인계받음 · 탑승 기록 남음", PickupFlow.statusLabel(e.state, e.child("child-a")))
        assertTrue(e.board("child-a", now))
        assertEquals(listOf(StopAction.BELT), actions("child-a"))
        assertTrue(e.confirmBelt("child-a", now))
        assertEquals(emptyList<StopAction>(), actions("child-a"))
        assertEquals("탑승", PickupFlow.statusLabel(e.state, e.child("child-a")))
        assertFalse(PickupFlow.receiverChosen(null)); assertFalse(PickupFlow.receiverChosen("이름"))
        assertTrue(PickupFlow.receiverChosen(PickupReceivers.OTHER))
    }

    @Test fun `VS-18 다음 정류장으로는 이 정류장 아이가 모두 탑승 또는 미탑승 확인일 때만`() {
        val e = atForest()
        assertEquals("다음 정류장으로", PickupFlow.leaveStopLabel(e.state))
        assertFalse(PickupFlow.canLeaveStop(e.state))
        board(e, "child-a")
        assertFalse(PickupFlow.canLeaveStop(e.state))
        assertEquals("이 정류장 어린이 1명이 아직 탑승·미탑승 확인 전이에요", PickupFlow.leaveStopBlocker(e.state))
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertTrue(PickupFlow.canLeaveStop(e.state))
        assertTrue(e.completeCurrentStop(now))
    }

    @Test fun `마지막 정류장은 기관으로 출발이고 운행의 모든 아이가 확인돼야 한다, 앞 정류장 아이는 맨 위에서 미탑승 확인`() {
        val e = atForest()
        board(e, "child-a")
        assertTrue(e.resumeDriving("나 확인 미룸", now)) // 나를 처리하지 않고 떠남
        assertTrue(e.arriveAtPickupStop(now)) // 박물관
        assertEquals(listOf("child-b"), PickupFlow.earlierUnresolvedChildren(e.state).map { it.id })
        board(e, "child-c")
        assertTrue(e.completeCurrentStop(now)) // 마지막이 아니면 이 정류장 아이만 본다
        assertTrue(e.arriveAtPickupStop(now)) // 올림픽공원(마지막)
        assertTrue(PickupFlow.isLastStop(e.state))
        assertEquals("기관으로 출발", PickupFlow.leaveStopLabel(e.state))
        board(e, "child-d")
        assertFalse(PickupFlow.canLeaveStop(e.state))
        assertTrue(PickupFlow.leaveStopBlocker(e.state)!!.contains("기관으로 출발"))
        assertFalse(e.completeCurrentStop(now)) // 엔진도 같은 조건
        assertEquals(ShuttleEngine.PICKUP_INSTITUTION_PENDING, e.lastMessage)
        // 앞 정류장 아이 시트: 지금 정류장 아이가 아니므로 도착 알림 제안 없음
        val sheet = PickupFlow.notBoardedSheet(e.state, e.child("child-b"))
        assertFalse(sheet.suggestGuardianCall)
        assertEquals("미탑승 예정 · 결석 알림", sheet.evidence)
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        assertTrue(PickupFlow.earlierUnresolvedChildren(e.state).isEmpty())
        assertTrue(PickupFlow.canLeaveStop(e.state))
        assertTrue(e.completeCurrentStop(now))
        assertNull(e.state.currentStop)
        assertEquals("arrive-origin", nextFlowGuide(e.state).targetTag) // → [기관 도착 확인]
    }

    @Test fun `VS-04 (b) 시트는 호출 전이면 도착 알림 제안을 보이고 막지 않는다, 안내 보내기는 NOTIFY와 SILENT로 기록`() {
        val e = atForest()
        val first = PickupFlow.notBoardedSheet(e.state, e.child("child-a"))
        assertTrue(first.suggestGuardianCall)
        assertEquals("미탑승 알림 없음", first.evidence)
        assertNull(first.callNote)
        assertTrue(e.callGuardian("child-b", now))
        val called = PickupFlow.notBoardedSheet(e.state, e.child("child-b"))
        assertFalse(called.suggestGuardianCall)
        assertEquals("보호자에게 도착 알림 보냄", called.callNote)
        assertEquals(PickupActions.NOT_BOARDED_NOTIFY, PickupFlow.notBoardedAction(true))
        assertEquals(PickupActions.NOT_BOARDED_SILENT, PickupFlow.notBoardedAction(false))
        // 호출 없이도 확인 가능(경고만), 시트 선택이 원장 기록 종류와 같다
        assertTrue(e.confirmNotBoardedAtStop("child-a", notifyGuardian = false, now))
        assertEquals(PickupFlow.notBoardedAction(false), e.state.ledger.last().pickupAction)
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        assertEquals(PickupFlow.notBoardedAction(true), e.state.ledger.last().pickupAction)
    }

    @Test fun `VS-14 정류장 정정은 떠나기 전 이 정류장 미탑승 확인 아이만, 뺀 정류장은 자동 재포함`() {
        val e = atForest()
        assertTrue(e.confirmNotBoardedAtStop("child-a", notifyGuardian = false, now))
        assertEquals(listOf(StopAction.CORRECT_TO_BOARDED), PickupFlow.stopActions(e.state, e.child("child-a")))
        assertTrue(e.correctNotBoardedToBoarded("child-a", PickupReceivers.GUARDIAN, now))
        assertEquals(Attendance.ONBOARD, e.child("child-a").status)
        assertEquals(PickupReceivers.GUARDIAN, e.child("child-a").receivedFromType)
        // 떠난 뒤(운행 중)에는 정정 동작이 없다(D33)
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertTrue(e.completeCurrentStop(now))
        assertEquals(emptyList<StopAction>(), PickupFlow.stopActions(e.state, e.child("child-b")))

        // 출발 전 미탑승 확인으로 뺀 정류장이 정정 때 다시 들어간다(V-09 기존 로직).
        val prep = engine(pickupTrip().copy(children = pickupTrip().children.map { if (it.id == "child-d") it.copy(notExpectedToBoard = true) else it }))
        assertTrue(prep.confirmNotBoarded("child-d", now))
        assertTrue(prep.excludeStop("park", now))
        assertTrue(prep.undoNotBoarded("child-d", now))
        assertTrue("park" in prep.state.routeOrder)
    }

    @Test fun `VS-20 처리 안 된 아이 미탑승 확인, 아이마다 기관 인계, 실내 확인이 모두 있어야 운행 종료`() {
        val e = atForest()
        board(e, "child-a")
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertTrue(e.completeCurrentStop(now))
        // 박물관·올림픽공원 정차 없이 지나쳤다고 보고 기관까지 간다.
        repeat(2) { assertTrue(e.arriveAtPickupStop(now)); assertTrue(e.resumeDriving("지나침", now)) }
        assertEquals("기관 도착 확인 뒤 종료해요", PickupFlow.institutionFinishBlocker(e.state, cabinChecked = true))
        assertTrue(e.beginEndReview(now))
        assertEquals(PICKUP_INSTITUTION_TAG, nextFlowGuide(e.state).targetTag)
        assertEquals(listOf("child-c", "child-d"), PickupFlow.unresolvedAtInstitution(e.state).map { it.id }) // 지나친 정류장 아이 포함
        assertEquals("처리 안 된 어린이 2명 · 미탑승 확인해 주세요", PickupFlow.institutionFinishBlocker(e.state, cabinChecked = true))
        assertTrue(e.confirmNotBoardedAtStop("child-c", notifyGuardian = false, now))
        assertTrue(e.confirmNotBoardedAtStop("child-d", notifyGuardian = true, now))
        assertEquals("기관 인계 전 어린이 1명 · 아이마다 인계해 주세요", PickupFlow.institutionFinishBlocker(e.state, cabinChecked = true))
        assertTrue(e.handoverToInstitution("child-a", now))
        assertEquals("차 안에 남은 아이가 없는지 직접 확인해 주세요", PickupFlow.institutionFinishBlocker(e.state, cabinChecked = false))
        assertNull(PickupFlow.institutionFinishBlocker(e.state, cabinChecked = true))
        assertEquals(PICKUP_INSTITUTION_TAG, nextFlowGuide(e.state).targetTag)
        assertTrue(e.inspectAndFinish(now))
        assertEquals(TripPhase.ENDED, e.state.phase)
        assertEquals("view-history", nextFlowGuide(e.state).targetTag)
        assertEquals("기관 인계 완료", PickupFlow.statusLabel(e.state, e.child("child-a")))
    }

    @Test fun `VS-19 호출 뒤 지난 시간은 분 단위로 한도 없이 보이고 응답 자리는 비어 있다`() {
        val e = atForest()
        assertNull(PickupFlow.callElapsedLabel(e.child("child-a"), now))
        assertTrue(e.callGuardian("child-a", now))
        val called = e.child("child-a")
        assertEquals("방금 호출", PickupFlow.callElapsedLabel(called, now))
        assertEquals("방금 호출", PickupFlow.callElapsedLabel(called, now + 59_999))
        assertEquals("호출 1분 전", PickupFlow.callElapsedLabel(called, now + 60_000))
        assertEquals("호출 3분 전", PickupFlow.callElapsedLabel(called, now + 3 * 60_000 + 30_000))
        assertEquals("호출 125분 전", PickupFlow.callElapsedLabel(called, now + 125 * 60_000))
        assertEquals("방금 호출", PickupFlow.callElapsedLabel(called, now - 5_000)) // 시계 역행
        assertNull(PickupFlow.guardianResponseLabel(called)) // 서버 응답 미제공
        assertFalse(e.callGuardian("child-a", now + 60_000)) // 재호출 없음(D30)
    }

    @Test fun `등원 단계 칩과 출발지 행`() {
        val e = atForest()
        assertEquals("정류장 탑승", phaseLabel(e.state))
        assertEquals("하차", phaseLabel(SampleTrip.create().copy(phase = TripPhase.ALIGHTING))) // 하원 그대로
        assertEquals("기관 인계", phaseLabel(e.state.copy(phase = TripPhase.END_REVIEW)))
        assertEquals("운행", phaseLabel(e.state.copy(phase = TripPhase.DRIVING)))
        board(e, "child-a")
        assertEquals("출발함", timelineStops(e.state).first().detail) // 출발지에서 탄 아이로 세지 않음
    }

    @Test fun `등원 표시 - 타임라인, 경로 인원, 위치 공유 출발`() {
        val e = engine()
        val stops = timelineStops(e.state)
        assertEquals("기관 도착", stops.last().name)
        assertEquals("탑승 1명", stops[1].detail) // 서울숲: 가(나는 결석 알림이라 탈 아이에서 빠짐)
        assertEquals(listOf("어린이 가"), ridersAt(e.state, "forest"))
        val before = e.state
        assertTrue(e.startDriving(now))
        assertEquals(listOf("어린이 가", "어린이 나"), ridersAt(e.state, "forest")) // 출발 뒤엔 정류장에서 확인할 아이 모두
        assertEquals(TrackingTransition.DEPART, automaticTrackingTransition(before, e.state))
        assertEquals("등원 완료", flowTitle(e.state.copy(phase = TripPhase.ENDED)))
        assertEquals("하원 완료", flowTitle(SampleTrip.create().copy(phase = TripPhase.ENDED)))
    }
}
