package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.NO_RIDERS_TAG
import com.hanhyo.pleosshuttle.nextFlowGuide
import com.hanhyo.pleosshuttle.ui.ridersAt
import com.hanhyo.pleosshuttle.ui.routeChanges
import com.hanhyo.pleosshuttle.ui.timelineStops
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** V-02: 오늘 미탑승 예정 배지 + 선생님 미탑승 확인/취소, V-11 정차 조건, D16 서버 상태 유지. */
class NotBoardedConfirmationTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun engine(trip: TripState = SampleTrip.create()) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }
    /** 서울숲(forest)의 어린이 나(child-b)는 결석 신청으로 오늘 미탑승 예정. */
    private fun withAbsentB() = SampleTrip.create().let { t ->
        t.copy(children = t.children.map { if (it.id == "child-b") it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") else it })
    }

    @Test fun `사유 코드를 화면 문구로 바꾸고 코드가 없으면 사유 문구를 붙이지 않는다`() {
        assertEquals("결석", notBoardingReasonLabel("ABSENT"))
        assertEquals("직접 픽업", notBoardingReasonLabel("SELF_PICKUP"))
        assertEquals("미탑승", notBoardingReasonLabel("NOT_RIDING"))
        assertEquals("기타", notBoardingReasonLabel("OTHER"))
        assertEquals("기타", notBoardingReasonLabel("SOMETHING_NEW"))
        assertNull(notBoardingReasonLabel(null))
        assertNull(notBoardingReasonLabel(" "))

        val child = ChildRecord("c", "가", "s", "미배정")
        assertNull(notBoardingBadge(child))
        assertEquals("오늘 미탑승 예정", notBoardingBadge(child.copy(notExpectedToBoard = true)))
        assertEquals("오늘 미탑승 예정 · 직접 픽업", notBoardingBadge(child.copy(notExpectedToBoard = true, notBoardingReason = "SELF_PICKUP")))
    }

    @Test fun `미탑승 예정 아이는 명단에 남고 선생님이 확인해야 확정된다`() {
        val trip = withAbsentB()
        assertEquals(4, trip.children.size)
        assertEquals(Attendance.NOT_BOARDED, trip.children.first { it.id == "child-b" }.status)
        assertTrue(trip.undecidedChildren.any { it.id == "child-b" })
    }

    @Test fun `탑승 기록 없는 아이를 미탑승 확인하고 출발 전에 취소할 수 있다`() {
        val e = engine(withAbsentB())
        assertTrue(e.confirmNotBoarded("child-b", now))
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, e.state.children.first { it.id == "child-b" }.status)
        val entry = e.state.ledger.last()
        assertEquals("child-b", entry.childId)
        assertEquals(Attendance.NOT_BOARDED, entry.before)
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, entry.after)
        assertTrue(entry.reason.contains("결석"))
        // 이미 확인한 아이는 다시 확인하거나 바로 탑승 기록하지 않는다(취소 후 탑승).
        assertFalse(e.confirmNotBoarded("child-b", now))
        assertFalse(e.board("child-b", now))

        assertTrue(e.undoNotBoarded("child-b", now))
        assertEquals(Attendance.NOT_BOARDED, e.state.children.first { it.id == "child-b" }.status)
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, e.state.ledger.last().before)
        assertEquals(Attendance.NOT_BOARDED, e.state.ledger.last().after)
        assertFalse(e.undoNotBoarded("child-b", now))
        assertTrue(e.board("child-b", now))
    }

    @Test fun `탑승 예정 아이도 미탑승 확인할 수 있고 탑승 기록이 있으면 정정을 써야 한다`() {
        val e = engine()
        assertTrue(e.confirmNotBoarded("child-c", now))
        assertEquals("선생님 미탑승 확인", e.state.ledger.last().reason)
        assertTrue(e.board("child-a", now))
        assertFalse(e.confirmNotBoarded("child-a", now))
        assertFalse(e.confirmNotBoarded("nobody", now))
        // 정정으로 미탑승 확인 상태를 만들 수 없다.
        assertFalse(e.correct("child-a", Attendance.CONFIRMED_NOT_BOARDED, "사유", now))
    }

    @Test fun `이동 중이거나 정차 미확인이면 미탑승 확인과 취소를 막는다`() {
        for (moving in listOf(parked().copy(speedKph = observed(12.0)), parked().copy(gear = observed(Gear.DRIVE)), parked().copy(speedKph = Observation()))) {
            val e = engine()
            assertTrue(e.confirmNotBoarded("child-d", now))
            e.updateVehicle(moving, now)
            assertFalse(e.confirmNotBoarded("child-c", now))
            assertFalse(e.undoNotBoarded("child-d", now))
            assertEquals(Attendance.CONFIRMED_NOT_BOARDED, e.state.children.first { it.id == "child-d" }.status)
            assertEquals(Attendance.NOT_BOARDED, e.state.children.first { it.id == "child-c" }.status)
        }
    }

    @Test fun `출발 뒤에는 미탑승 확인도 취소도 하지 않는다`() {
        val e = engine()
        assertTrue(e.confirmNotBoarded("child-d", now))
        assertTrue(e.board("child-a", now))
        assertTrue(e.startDriving(now))
        assertFalse(e.confirmNotBoarded("child-b", now))
        assertFalse(e.undoNotBoarded("child-d", now))
    }

    @Test fun `모든 아이가 탑승 또는 미탑승 확인이면 출발할 수 있다`() {
        val e = engine(withAbsentB())
        assertTrue(e.board("child-a", now))
        assertTrue(e.confirmNotBoarded("child-b", now))
        assertTrue(e.confirmNotBoarded("child-c", now))
        assertTrue(e.board("child-d", now))
        assertTrue(e.state.undecidedChildren.isEmpty())
        assertEquals("start-trip", nextFlowGuide(e.state).targetTag)
        assertTrue(e.startDriving(now))
        assertEquals(TripPhase.DRIVING, e.state.phase)
    }

    @Test fun `모두 미탑승 확인이면 탈 아이가 없어 출발하지 않는다`() {
        val e = engine()
        e.state.children.forEach { assertTrue(e.confirmNotBoarded(it.id, now)) }
        assertEquals(NO_RIDERS_TAG, nextFlowGuide(e.state).targetTag)
        assertFalse(e.startDriving(now))
        assertEquals(TripPhase.PREPARING, e.state.phase)
        // 예정 명단 기준인 "오늘 운행 없음"(D34)과는 별개: 확인만으로 운행 없음으로 바뀌지 않는다.
        assertFalse(e.state.noTripToday)
    }

    @Test fun `흐름은 미탑승 확인한 아이를 건너뛰고 미탑승 예정 아이는 미탑승 확인을 먼저 묻는다`() {
        val trip = withAbsentB().let { t -> t.copy(children = t.children.map { if (it.id == "child-a") it.copy(status = Attendance.ONBOARD) else it }) }
        val guide = nextFlowGuide(trip)
        assertEquals("not-boarded-child-b", guide.targetTag)
        assertFalse(guide.confirmingRemaining)
        val skipped = trip.copy(children = trip.children.map { if (it.id == "child-b") it.copy(status = Attendance.CONFIRMED_NOT_BOARDED) else it })
        assertEquals("board-child-c", nextFlowGuide(skipped).targetTag)
        // 탑승 마침 뒤에는 남은 아이마다 미탑승 확인을 받는다.
        val reviewed = nextFlowGuide(skipped, boardingReviewed = true)
        assertEquals("not-boarded-child-c", reviewed.targetTag)
        assertTrue(reviewed.confirmingRemaining)
    }

    @Test fun `오늘 운행 없음에서는 미탑승 확인을 받지 않고 정류장 인원은 0명이다`() {
        val absent = SampleTrip.create().let { t -> t.copy(children = t.children.map { it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") }) }
        val e = engine(absent)
        assertTrue(e.state.noTripToday)
        assertFalse(e.confirmNotBoarded("child-a", now))
        assertEquals(ShuttleEngine.NO_TRIP_TODAY, e.lastMessage)
        val rows = timelineStops(absent)
        assertEquals(listOf("하차 0명", "하차 0명", "하차 0명"), rows.drop(1).dropLast(1).map { it.detail })
        assertEquals("탑승 기록 0/0", rows.first().detail)
        assertTrue(absent.stops.all { ridersAt(absent, it.id).isEmpty() })
        // 정류장은 경로에서 빼지 않는다(V-09는 이후).
        assertEquals(5, rows.size)
    }

    @Test fun `정류장 하차 인원은 탈 아이만 세고 미탑승 확인만 남은 정류장은 0명으로 남는다`() {
        val e = engine(withAbsentB())
        // 서울숲: 가(예정) + 나(미탑승 예정) → 1명
        assertEquals("하차 1명", timelineStops(e.state)[1].detail)
        assertEquals(listOf("어린이 가"), ridersAt(e.state, "forest"))
        assertTrue(e.confirmNotBoarded("child-a", now))
        assertEquals("하차 0명", timelineStops(e.state)[1].detail)
        assertTrue(ridersAt(e.state, "forest").isEmpty())
        assertEquals("탑승 기록 0/2", timelineStops(e.state).first().detail)
        assertEquals(3, e.state.routeOrder.size)
        // 미탑승 예정 아이도 실제로 타면 인원에 들어간다.
        assertTrue(e.board("child-b", now))
        assertEquals("하차 1명", timelineStops(e.state)[1].detail)
        // 달라진 점: 미탑승 확인 표시
        assertTrue(routeChanges(e.state).any { it.text == "어린이 가 · 미탑승 확인" })
    }

    @Test fun `서버 전송은 TEACHER_CONFIRMED_NOT_BOARDED이고 취소는 상태를 바꾸지 않는 CORRECTION이다`() {
        assertEquals("TEACHER_CONFIRMED_NOT_BOARDED", InstitutionTrips.ledgerEventType(Attendance.NOT_BOARDED, Attendance.CONFIRMED_NOT_BOARDED))
        assertEquals("CORRECTION", InstitutionTrips.ledgerEventType(Attendance.CONFIRMED_NOT_BOARDED, Attendance.NOT_BOARDED))
        assertEquals("CHILD_BOARDED", InstitutionTrips.ledgerEventType(Attendance.CONFIRMED_NOT_BOARDED, Attendance.ONBOARD))
        assertEquals("CHILD_BOARDED", InstitutionTrips.ledgerEventType(Attendance.NOT_BOARDED, Attendance.ONBOARD))
        assertEquals("CHILD_NOT_BOARDED", InstitutionTrips.ledgerEventType(Attendance.ONBOARD, Attendance.NOT_BOARDED))
        assertEquals("CHILD_DROPPED_OFF", InstitutionTrips.ledgerEventType(Attendance.ONBOARD, Attendance.ALIGHTED))
    }

    @Test fun `서버의 선생님 미탑승 확인 상태는 유지하고 사전 결석 상태는 미결정으로 둔다`() {
        // 탑승 예정(=1)인데 NOT_BOARDED → TEACHER_CONFIRMED_NOT_BOARDED로만 생김 → 확인 유지(D16)
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, InstitutionTrips.childRecord("c1", "가", "s1", "NOT_BOARDED", true, null).status)
        // 운행 생성 때 승인된 결석으로 넣은 NOT_BOARDED(=0) → 선생님 확인 전
        val absent = InstitutionTrips.childRecord("c2", "나", "s1", "NOT_BOARDED", 0, "ABSENT")
        assertEquals(Attendance.NOT_BOARDED, absent.status)
        assertEquals("오늘 미탑승 예정 · 결석", notBoardingBadge(absent))
    }

    @Test fun `미탑승 확인 상태가 담긴 운행을 저장하고 복원한다`() {
        val e = engine()
        assertTrue(e.confirmNotBoarded("child-c", now))
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, restored.children.first { it.id == "child-c" }.status)
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, restored.ledger.last().after)
    }
}
