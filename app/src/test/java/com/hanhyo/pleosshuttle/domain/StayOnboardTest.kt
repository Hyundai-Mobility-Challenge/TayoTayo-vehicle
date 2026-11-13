package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.alightingChildren
import com.hanhyo.pleosshuttle.nextFlowGuide
import com.hanhyo.pleosshuttle.stayOnboardReturnPrefix
import com.hanhyo.pleosshuttle.tripCue
import com.hanhyo.pleosshuttle.ui.RiderHighlight
import com.hanhyo.pleosshuttle.ui.riderBoard
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** V-21 보호자 미도착 · 동승 유지(하원), D35 자동 기록(GUARDIAN_NO_SHOW), V-22 복귀 후 기관 인계, V-11 정차 조건. */
class StayOnboardTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SIMULATED, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun moving() = parked().copy(speedKph = observed(30.0), gear = observed(Gear.DRIVE))

    /** 서울숲(forest): 가(child-a)·나(child-b), 박물관(museum): 다(child-c), 올림픽공원(park): 라(child-d, 미탑승 확인). */
    private fun atForest(): ShuttleEngine = ShuttleEngine(SampleTrip.create()).also {
        it.updateVehicle(parked(), now)
        assertTrue(it.board("child-a", now)); assertTrue(it.board("child-b", now)); assertTrue(it.board("child-c", now))
        assertTrue(it.confirmNotBoarded("child-d", now))
        assertTrue(it.startDriving(now))
        assertTrue(it.chooseAlighting(now))
        assertEquals("forest", it.state.currentStop?.id)
    }
    private fun ShuttleEngine.child(id: String) = state.children.first { it.id == id }

    @Test fun `동승 유지는 인계 단계에서 정차 중에만 고정 사유로 기록한다`() {
        val e = atForest()
        e.updateVehicle(moving(), now)
        assertFalse(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        e.updateVehicle(parked(), now)
        assertFalse(e.markStayOnboard("child-b", "자유 입력 사유", now))
        assertFalse(e.markStayOnboard("child-c", StayOnboardReasons.GUARDIAN_ABSENT, now)) // 다른 정류장 아이
        assertFalse(e.markStayOnboard("child-d", StayOnboardReasons.GUARDIAN_ABSENT, now)) // 탑승 기록 없음
        assertTrue(e.confirmHandover("child-a", now))
        assertFalse(e.markStayOnboard("child-a", StayOnboardReasons.GUARDIAN_ABSENT, now)) // 이미 인계 확인
        val before = e.state.ledger.size

        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.NO_CONTACT, now))
        assertEquals(Attendance.ONBOARD, e.child("child-b").status)
        assertEquals(StayOnboardReasons.NO_CONTACT, e.child("child-b").stayOnboardReason)
        assertEquals(before + 1, e.state.ledger.size)
        val entry = e.state.ledger.last()
        assertEquals(true, entry.stayOnboard)
        assertEquals(Attendance.ONBOARD, entry.before); assertEquals(Attendance.ONBOARD, entry.after)
        assertTrue(entry.reason.contains("연락 안 됨"))
        assertFalse(e.markStayOnboard("child-b", StayOnboardReasons.OTHER, now)) // 중복 없음
    }

    @Test fun `운행 중과 종료 확인 단계에서는 동승 유지를 기록하지 않는다`() {
        val e = ShuttleEngine(SampleTrip.create()).also { it.updateVehicle(parked(), now) }
        assertTrue(e.board("child-a", now)); assertTrue(e.startDriving(now))
        assertFalse(e.markStayOnboard("child-a", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertTrue(e.beginEndReview(now))
        assertFalse(e.markStayOnboard("child-a", StayOnboardReasons.GUARDIAN_ABSENT, now))
    }

    @Test fun `동승 유지 아이는 처리된 것으로 보고 지점을 완료하되 자동 완료로 취소 기회를 없애지 않는다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertEquals("handover-child-a", nextFlowGuide(e.state).targetTag)
        assertTrue(e.confirmHandover("child-a", now))
        assertTrue(e.recordAlightingAndContinue("child-a", now))
        // 동승 유지 아이가 있으면 자동 완료하지 않고 [다음 지점으로] 단계에 머문다.
        assertEquals(TripPhase.ALIGHTING, e.state.phase)
        assertEquals("complete-stop", nextFlowGuide(e.state).targetTag)
        assertTrue(e.resumeWarnings(now).none { it.contains("미하차") })
        assertTrue(e.completeCurrentStop(now))
        assertEquals(TripPhase.DRIVING, e.state.phase)
        assertEquals("museum", e.state.currentStop?.id)
        assertEquals(Attendance.ONBOARD, e.child("child-b").status)
        assertTrue(e.child("child-b").staysOnboard)
    }

    @Test fun `동승 유지가 없으면 마지막 하차 기록에서 기존처럼 자동 완료한다`() {
        val e = atForest()
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertTrue(e.confirmHandover("child-b", now)); assertTrue(e.recordAlightingAndContinue("child-b", now))
        assertEquals(TripPhase.DRIVING, e.state.phase)
    }

    @Test fun `모두 동승 유지여도 하차 없이 지점을 완료할 수 있다`() {
        val e = atForest()
        assertFalse(e.completeCurrentStop(now))
        assertTrue(e.markStayOnboard("child-a", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.OTHER, now))
        assertTrue(e.completeCurrentStop(now))
        assertEquals(2, e.state.stayOnboardChildren.size)
    }

    @Test fun `같은 정류장에서는 취소하고 인계로 돌아가며 떠난 뒤에는 취소할 수 없다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        e.updateVehicle(moving(), now)
        assertFalse(e.undoStayOnboard("child-b", now))
        e.updateVehicle(parked(), now)
        assertTrue(e.undoStayOnboard("child-b", now))
        assertNull(e.child("child-b").stayOnboardReason)
        assertEquals(false, e.state.ledger.last().stayOnboard)
        assertFalse(e.undoStayOnboard("child-b", now))
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertEquals("handover-child-b", nextFlowGuide(e.state).targetTag)

        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertTrue(e.completeCurrentStop(now))
        assertFalse(e.undoStayOnboard("child-b", now)) // 운행 중
        assertTrue(e.chooseAlighting(now))
        assertFalse(e.undoStayOnboard("child-b", now)) // 다른 정류장
        assertTrue(e.child("child-b").staysOnboard)
    }

    @Test fun `동승 유지 아이는 하차 안내에서 빠지고 복귀 안내에서 기관 인계를 말한다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertEquals(listOf("child-a"), alightingChildren(e.state, e.state.currentStop).map { it.id })
        assertFalse(tripCue(e.state)!!.text.contains("어린이 나"))
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertTrue(e.completeCurrentStop(now))
        // 다음 지점 안내: 다만 하차, 나는 이름이 나오지 않는다.
        // 재출발 벨트 앞말(V-16)은 계속 타는 아이 이름을 읽으므로 하차 안내 부분만 본다.
        val approach = tripCue(e.state)!!.text.substringAfter("다음은")
        assertTrue(approach.contains("어린이 다")); assertFalse(approach.contains("어린이 나"))
        assertTrue(e.chooseAlighting(now))
        assertFalse(tripCue(e.state)!!.text.contains("어린이 나"))
        assertTrue(e.confirmHandover("child-c", now)); assertTrue(e.recordAlightingAndContinue("child-c", now))
        assertTrue(e.chooseAlighting(now)) // 올림픽공원: 내릴 아이 없음
        assertTrue(e.completeCurrentStop(now))
        assertTrue(e.beginEndReview(now))
        assertEquals("동승 유지 어린이 나 기관에 인계해 주세요. ", stayOnboardReturnPrefix(e.state))
        assertTrue(tripCue(e.state)!!.text.startsWith("동승 유지 어린이 나 기관에 인계해 주세요."))
    }

    @Test fun `복귀 후 동승 유지 아이를 잔류로 강조하고 기관 인계 하차 뒤에만 종료한다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertFalse(e.returnStayOnboardToOrg("child-b", now)) // 정류장에서는 기관 인계 아님
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertTrue(e.completeCurrentStop(now))
        // 운행 중 보드: 상태 칩은 "동승 유지"(이름+상태만), 사유 배지는 숨김.
        val driving = riderBoard(e.state, stationary = false, uxRestricted = false).rows.first { it.id == "child-b" }
        assertEquals("동승 유지", driving.statusLabel); assertNull(driving.badge)
        assertTrue(e.beginEndReview(now))

        val board = riderBoard(e.state, stationary = true, uxRestricted = false)
        val row = board.rows.first { it.id == "child-b" }
        assertEquals(RiderHighlight.REMAINING, row.highlight)
        assertEquals("동승 유지", row.statusLabel)
        assertEquals("동승 유지 사유 · 보호자 미도착", row.badge)
        assertTrue(board.summary.contains("동승 유지 1"))
        assertTrue(board.note!!.contains("동승 유지 1명"))
        assertEquals("return-org-child-b", nextFlowGuide(e.state).targetTag)
        assertFalse(e.inspectAndFinish(now))

        e.updateVehicle(moving(), now)
        assertFalse(e.returnStayOnboardToOrg("child-b", now))
        e.updateVehicle(parked(), now)
        assertFalse(e.returnStayOnboardToOrg("child-c", now)) // 동승 유지 아님
        assertTrue(e.returnStayOnboardToOrg("child-b", now))
        assertEquals(Attendance.ALIGHTED, e.child("child-b").status)
        assertEquals(true, e.state.ledger.last().stayOnboard)
        assertEquals("기관 인계 완료", riderBoard(e.state, stationary = true, uxRestricted = false).rows.first { it.id == "child-b" }.statusLabel)
        // 박물관을 들르지 않고 복귀했으므로 다(child-c)는 일반 미하차 기록으로 남아 기존 인계 확인 흐름을 탄다.
        assertEquals("end-handover-child-c", nextFlowGuide(e.state).targetTag)
    }

    @Test fun `동승 유지 기록은 서버 GUARDIAN_NO_SHOW 취소는 CORRECTION 기관 인계는 CHILD_RETURNED_TO_ORG로 보낸다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertEquals("GUARDIAN_NO_SHOW", InstitutionTrips.ledgerEventType(e.state.ledger.last()))
        assertTrue(e.undoStayOnboard("child-b", now))
        assertEquals("CORRECTION", InstitutionTrips.ledgerEventType(e.state.ledger.last()))
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertEquals("CHILD_DROPPED_OFF", InstitutionTrips.ledgerEventType(e.state.ledger.last()))
        assertTrue(e.completeCurrentStop(now))
        assertTrue(e.beginEndReview(now))
        assertTrue(e.returnStayOnboardToOrg("child-b", now))
        assertEquals("CHILD_RETURNED_TO_ORG", InstitutionTrips.ledgerEventType(e.state.ledger.last()))

        // 출결 기록이므로 서버 전송 대상이며, 항목 id로 한 번씩만 보낸다(clientEventId = tripId:항목 id).
        val server = InstitutionTrips.serverLedger(e.state.ledger)
        val stayEntries = server.filter { it.stayOnboard != null }
        assertEquals(4, stayEntries.size)
        assertEquals(stayEntries.size, stayEntries.map { it.id }.toSet().size)
        assertEquals(server.size, InstitutionTrips.pendingCount(e.state.ledger, emptySet()))
        assertEquals(server.size - 1, InstitutionTrips.pendingCount(e.state.ledger, setOf(stayEntries.first().id)))
        // 이전 기록(stayOnboard=null)의 규칙은 그대로.
        assertEquals("CHILD_BOARDED", InstitutionTrips.ledgerEventType(LedgerEntry("x", "c", Attendance.NOT_BOARDED, Attendance.ONBOARD, now, "")))
    }

    @Test fun `서버 GUARDIAN_NO_SHOW 상태는 탑승 중 동승 유지로 읽는다`() {
        val child = InstitutionTrips.childRecord("7", "가", "1", "GUARDIAN_NO_SHOW", true, null)
        assertEquals(Attendance.ONBOARD, child.status)
        assertEquals(StayOnboardReasons.GUARDIAN_ABSENT, child.stayOnboardReason)
        assertNull(InstitutionTrips.childRecord("7", "가", "1", "BOARDED", true, null).stayOnboardReason)
    }

    @Test fun `새 필드는 직렬화 왕복되고 사유 문구는 고정 선택지다`() {
        val e = atForest()
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(e.state, restored)
        assertEquals(StayOnboardReasons.GUARDIAN_ABSENT, restored.children.first { it.id == "child-b" }.stayOnboardReason)
        assertEquals(true, restored.ledger.last().stayOnboard)
        assertEquals(listOf("보호자 미도착", "연락 안 됨", "기타"), StayOnboardReasons.ALL.map(StayOnboardReasons::label))
    }
}
