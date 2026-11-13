package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveFocusPolicyTest {
    private val now = 100_000L
    private val trip = SampleTrip.create().copy(phase = TripPhase.DRIVING, integrationMode = IntegrationMode.SDK)
    private fun motion(gear: Gear, speed: Double, at: Long = now) = DriveObservation(speed, gear, false, at)

    @Test fun drivingHidesOnceThenParkingShowsOnce() {
        val p = DriveFocusPolicy()
        assertEquals(DriveFocusAction.HIDE_APP, p.evaluate(trip, motion(Gear.DRIVE, 10.0), now))
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.DRIVE, 12.0), now))
        // 신호 대기: D에서 멈춤은 계속 주행
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.DRIVE, 0.0), now))
        assertEquals(DriveFocusAction.SHOW_APP, p.evaluate(trip, motion(Gear.PARK, 0.0), now))
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.PARK, 0.0), now))
        assertEquals(DriveFocusAction.HIDE_APP, p.evaluate(trip, motion(Gear.DRIVE, 5.0), now))
    }

    @Test fun parkWithoutPriorHideDoesNothingAndCreepingInDriveDoesNotHide() {
        val p = DriveFocusPolicy()
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.PARK, 0.0), now))
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.DRIVE, 0.5), now))
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.REVERSE, 3.0), now))
    }

    @Test fun simulationPreparingEndedAndStaleObservationAreIgnored() {
        for (t in listOf(trip.copy(integrationMode = IntegrationMode.SIMULATION), trip.copy(phase = TripPhase.PREPARING),
            trip.copy(phase = TripPhase.END_REVIEW), trip.copy(phase = TripPhase.ENDED), null))
            assertEquals(DriveFocusAction.NONE, DriveFocusPolicy().evaluate(t, motion(Gear.DRIVE, 10.0), now))
        assertEquals(DriveFocusAction.NONE, DriveFocusPolicy().evaluate(trip, motion(Gear.DRIVE, 10.0, now - 6_000), now))
        assertEquals(DriveFocusAction.NONE, DriveFocusPolicy().evaluate(trip, DriveObservation(10.0, Gear.UNKNOWN, false, now), now))
    }

    @Test fun leavingTheTripResetsSoParkingAfterEndDoesNotReopen() {
        val p = DriveFocusPolicy()
        p.evaluate(trip, motion(Gear.DRIVE, 10.0), now)
        p.evaluate(trip.copy(phase = TripPhase.END_REVIEW), motion(Gear.DRIVE, 10.0), now)
        assertEquals(DriveFocusAction.NONE, p.evaluate(trip, motion(Gear.PARK, 0.0), now))
    }
}
