package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

/** D34: 탈 아이(expectedToBoard)가 0명이면 운행하지 않음("오늘 운행 없음"). */
class NoTripTodayTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SIMULATED, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun allAbsent() = SampleTrip.create().let { trip ->
        trip.copy(children = trip.children.map { it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") })
    }
    private fun engine(trip: TripState) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }

    @Test fun `빈 명단이면 오늘 운행 없음`() {
        val trip = SampleTrip.create().copy(children = emptyList())
        assertEquals(0, trip.expectedRiderCount)
        assertTrue(trip.noTripToday)
    }

    @Test fun `전원 미탑승 예정이면 오늘 운행 없음`() {
        assertEquals(0, allAbsent().expectedRiderCount)
        assertTrue(allAbsent().noTripToday)
    }

    @Test fun `탑승 예정이 한 명이라도 있으면 평소 운행`() {
        val mixed = SampleTrip.create().let { trip ->
            trip.copy(children = trip.children.map { if (it.id == "child-a") it else it.copy(notExpectedToBoard = true) })
        }
        assertEquals(1, mixed.expectedRiderCount)
        assertFalse(mixed.noTripToday)
        assertFalse(SampleTrip.create().noTripToday)
    }

    @Test fun `운행 준비가 아니거나 탑승 기록이 있으면 기존 흐름을 유지한다`() {
        assertFalse(allAbsent().copy(phase = TripPhase.DRIVING).noTripToday)
        val boarded = allAbsent().let { t -> t.copy(children = t.children.map { if (it.id == "child-a") it.copy(status = Attendance.ONBOARD) else it }) }
        assertFalse(boarded.noTripToday)
    }

    @Test fun `오늘 운행 없음이면 탑승 기록과 운행 시작을 막는다`() {
        val engine = engine(allAbsent())
        assertFalse(engine.board("child-a", now))
        assertEquals(ShuttleEngine.NO_TRIP_TODAY, engine.lastMessage)
        assertFalse(engine.startDriving(now))
        assertEquals(ShuttleEngine.NO_TRIP_TODAY, engine.lastMessage)
        assertFalse(engine.startOperationalTrip(now))
        assertEquals(TripPhase.PREPARING, engine.state.phase)
        assertTrue(engine.state.ledger.isEmpty())

        val empty = engine(SampleTrip.create().copy(children = emptyList()))
        assertFalse(empty.startDriving(now))
        assertEquals(ShuttleEngine.NO_TRIP_TODAY, empty.lastMessage)
    }

    @Test fun `탑승 예정 어린이가 있으면 기존대로 탑승 후 운행을 시작한다`() {
        val mixed = SampleTrip.create().let { trip ->
            trip.copy(children = trip.children.map { if (it.id == "child-a") it else it.copy(notExpectedToBoard = true) })
        }
        val engine = engine(mixed)
        assertTrue(engine.board("child-a", now))
        assertTrue(engine.startDriving(now))
        assertEquals(TripPhase.DRIVING, engine.state.phase)
    }
}
