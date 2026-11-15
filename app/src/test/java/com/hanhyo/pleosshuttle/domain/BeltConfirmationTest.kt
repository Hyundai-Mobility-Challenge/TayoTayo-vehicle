package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.InstitutionTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** V-05 벨트 수동 확인: 탑승 중인 아이만, 정차 중(V-11) 운행 준비·하차 단계에서만, 서버로 보내지 않음(D27). */
class BeltConfirmationTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun moving() = parked().copy(speedKph = observed(20.0), gear = observed(Gear.DRIVE))
    private fun engine(trip: TripState = SampleTrip.create()) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }
    private fun ShuttleEngine.child(id: String) = state.children.first { it.id == id }

    @Test fun `탑승 중인 아이만 벨트를 확인하고 미확인으로 되돌릴 수 있다`() {
        val e = engine()
        assertFalse(e.confirmBelt("child-a", now)) // 탑승 전
        assertEquals("탑승 기록이 있는 어린이만 벨트를 확인합니다.", e.lastMessage)
        assertFalse(e.confirmBelt("nobody", now))
        assertTrue(e.board("child-a", now))
        assertFalse(e.child("child-a").beltConfirmed)
        assertTrue(e.confirmBelt("child-a", now))
        assertTrue(e.child("child-a").beltConfirmed)
        assertFalse(e.confirmBelt("child-a", now)) // 같은 상태 반복은 기록하지 않음
        assertTrue(e.toggleBelt("child-a", now))
        assertFalse(e.child("child-a").beltConfirmed)
        assertTrue(e.toggleBelt("child-a", now))
        assertTrue(e.unconfirmBelt("child-a", now))
        assertFalse(e.child("child-a").beltConfirmed)
        // 미탑승 확인 아이도 벨트 대상이 아님
        assertTrue(e.confirmNotBoarded("child-d", now))
        assertFalse(e.toggleBelt("child-d", now))
    }

    @Test fun `벨트 기록은 원장에 남지만 서버 이벤트로 보내지 않는다`() {
        val e = engine()
        assertTrue(e.board("child-a", now))
        assertTrue(e.confirmBelt("child-a", now))
        assertTrue(e.unconfirmBelt("child-a", now))
        val belts = e.state.ledger.filter { it.isBeltRecord }
        assertEquals(listOf(true, false), belts.map { it.belt })
        assertTrue(belts.all { it.childId == "child-a" && it.before == Attendance.ONBOARD && it.after == Attendance.ONBOARD })
        assertEquals(3, e.state.ledger.size)
        val server = InstitutionTrips.serverLedger(e.state.ledger)
        assertEquals(1, server.size)
        assertNull(server.single().belt)
        assertEquals("CHILD_BOARDED", InstitutionTrips.ledgerEventType(server.single().before, server.single().after))
    }

    @Test fun `주행 중과 정차 미확인이면 벨트 기록을 막는다`() {
        val e = engine()
        assertTrue(e.board("child-a", now))
        e.updateVehicle(moving(), now)
        assertFalse(e.confirmBelt("child-a", now))
        assertFalse(e.child("child-a").beltConfirmed)
        e.updateVehicle(parked(), now)
        // 오래된 정보(정차 미확인)도 막는다
        assertFalse(e.confirmBelt("child-a", now + 60_000))
        assertTrue(e.confirmBelt("child-a", now))
    }

    @Test fun `운행 단계는 조회 전용이고 하차 단계 정차 중에는 기록한다`() {
        val e = engine()
        listOf("child-a", "child-b", "child-c", "child-d").forEach { assertTrue(e.board(it, now)) }
        assertTrue(e.confirmBelt("child-c", now))
        assertEquals(3, e.state.beltUncheckedCount)
        assertTrue(e.startDriving(now)) // 미확인이 남아도 출발은 막지 않음(경고만)
        assertFalse(e.confirmBelt("child-d", now))
        assertTrue(e.lastMessage.contains("조회만"))
        assertTrue(e.chooseAlighting(now))
        assertTrue(e.confirmBelt("child-d", now))
        assertEquals(2, e.state.beltUncheckedCount)
        // 하차해도 다른 아이 벨트 확인은 그대로, 하차한 아이는 수에서 빠진다
        assertTrue(e.confirmHandover("child-a", now))
        assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertEquals(1, e.state.beltUncheckedCount)
        assertTrue(e.confirmHandover("child-b", now))
        assertTrue(e.recordAlightingAndContinue("child-b", now))
        assertEquals(TripPhase.DRIVING, e.state.phase)
        // 재출발해도 확인 값은 유지(초기화하지 않음)
        assertTrue(e.child("child-c").beltConfirmed && e.child("child-d").beltConfirmed)
        assertEquals(0, e.state.beltUncheckedCount)
    }

    @Test fun `beltUncheckedCount는 탑승 중이고 미확인인 아이만 센다`() {
        val trip = SampleTrip.create().let { t ->
            t.copy(children = t.children.map {
                when (it.id) {
                    "child-a" -> it.copy(status = Attendance.ONBOARD, beltConfirmed = true)
                    "child-b" -> it.copy(status = Attendance.ONBOARD)
                    "child-c" -> it.copy(status = Attendance.ALIGHTED)
                    else -> it.copy(status = Attendance.CONFIRMED_NOT_BOARDED)
                }
            })
        }
        assertEquals(1, trip.beltUncheckedCount)
        assertEquals(listOf("child-b"), trip.beltUncheckedChildren.map { it.id })
        assertEquals(0, SampleTrip.create().beltUncheckedCount)
    }

    @Test fun `다시 탑승 기록되면 벨트는 미확인에서 시작한다`() {
        val e = engine()
        assertTrue(e.board("child-a", now))
        assertTrue(e.confirmBelt("child-a", now))
        assertTrue(e.correct("child-a", Attendance.NOT_BOARDED, "잘못 누름", now))
        assertTrue(e.board("child-a", now))
        assertFalse(e.child("child-a").beltConfirmed)
    }

    @Test fun `벨트 확인 상태와 벨트 원장을 저장하고 복원한다`() {
        val e = engine()
        assertTrue(e.board("child-a", now))
        assertTrue(e.confirmBelt("child-a", now))
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(e.state, restored)
        assertTrue(restored.children.first { it.id == "child-a" }.beltConfirmed)
        assertEquals(true, restored.ledger.last().belt)
        assertNull(restored.ledger.first().belt)
    }
}
