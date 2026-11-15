package com.hanhyo.pleosshuttle.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test

/** 저장된 운행이 없거나 이전 빌드의 모의 운행이면 "운행 없음"으로 시작한다(출시 앱에는 모의 운행이 없다). */
class StoredTripsTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun serverTrip() = SampleTrip.create().copy(tripId = "42", routeIsExample = false)

    @Test fun `기본 엔진은 운행 없음 자리 표시 상태로 시작한다`() {
        val state = ShuttleEngine().state
        assertFalse(state.hasTrip)
        assertEquals(NoTrip.ID, state.tripId)
        assertEquals(IntegrationMode.SDK, state.integrationMode)
        assertTrue(state.children.isEmpty())
        assertTrue(state.stops.isEmpty())
        assertNull(state.currentStop)
        assertTrue(serverTrip().hasTrip)
    }

    @Test fun `운행 없음 상태에서는 운행 조작이 받아들여지지 않는다`() {
        val engine = ShuttleEngine().also { it.updateVehicle(parked(), now) }
        assertFalse(engine.startDriving(now))
        assertFalse(engine.startOperationalTrip(now))
        assertFalse(engine.setDriverChecked(true, now))
        assertFalse(engine.board("child-a", now))
        assertEquals(TripPhase.PREPARING, engine.state.phase)
        assertTrue(engine.state.ledger.isEmpty())
    }

    @Test fun `이전 빌드의 모의 운행과 자리 표시 상태는 복원하지 않는다`() {
        assertNull(StoredTrips.restorable(null))
        assertNull(StoredTrips.restorable(NoTrip.state()))
        assertNull(StoredTrips.restorable(serverTrip().copy(integrationMode = IntegrationMode.SIMULATION)))
        assertNull(StoredTrips.restorable(serverTrip().copy(tripId = "fictional-demo-1")))
        assertTrue(StoredTrips.isLegacySimulation(SampleTrip.create()))
        val real = serverTrip()
        assertEquals(real, StoredTrips.restorable(real))
    }

    @Test fun `기관에 묶인 운행을 모의로 바꿔 저장했고 미전송 원장이 있으면 SDK로 바꿔 복원한다`() {
        val entry = LedgerEntry("ledger-1", "child-a", Attendance.NOT_BOARDED, Attendance.ONBOARD, now, "선생님 탑승 확인")
        val switched = serverTrip().copy(integrationMode = IntegrationMode.SIMULATION, ledger = listOf(entry),
            children = serverTrip().children.map { if (it.id == "child-a") it.copy(status = Attendance.ONBOARD) else it })
        val restored = StoredTrips.restorable(switched, boundTripId = "42", unsentRecords = 1)
        assertNotNull(restored)
        assertEquals(IntegrationMode.SDK, restored!!.integrationMode)
        assertEquals(switched.ledger, restored.ledger)
        assertEquals(switched.children, restored.children)
        assertEquals(switched.copy(integrationMode = IntegrationMode.SDK), restored)
        // 미전송이 없거나, 다른 운행에 묶였거나, 묶인 운행이 없으면 예전처럼 버린다.
        assertNull(StoredTrips.restorable(switched, boundTripId = "42", unsentRecords = 0))
        assertNull(StoredTrips.restorable(switched, boundTripId = "43", unsentRecords = 1))
        assertNull(StoredTrips.restorable(switched, boundTripId = "", unsentRecords = 1))
        assertNull(StoredTrips.restorable(switched, boundTripId = null, unsentRecords = 1))
        // 기관 운행(SDK)은 묶임·미전송과 관계없이 그대로 복원한다.
        assertEquals(serverTrip(), StoredTrips.restorable(serverTrip(), boundTripId = null, unsentRecords = 0))
    }

    @Test fun `기관 운행은 복원할 때 서버 정류장 순서로 고정되어 출발 때 순서를 다시 계산하지 않는다`() {
        val example = serverTrip().copy(routeIsExample = true)
        val restored = StoredTrips.restorable(example)!!
        assertFalse(restored.routeIsExample)
        assertEquals(example.routeOrder, restored.routeOrder)
        val engine = ShuttleEngine(restored).also { it.updateVehicle(parked(), now) }
        assertTrue(engine.board("child-a", now))
        assertTrue(engine.startOperationalTrip(now))
        assertEquals(example.routeOrder, engine.state.routeOrder) // RoutePlanner 자동 계산(forest → park → museum)이 적용되지 않음
    }

    @Test fun `SIMULATION 상수로 저장된 운행도 역직렬화는 실패하지 않는다`() {
        val legacy = serverTrip().copy(integrationMode = IntegrationMode.SIMULATION)
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(legacy) } }.toByteArray()
        val read = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(IntegrationMode.SIMULATION, read.integrationMode)
        assertNull(StoredTrips.restorable(read))
    }
}
