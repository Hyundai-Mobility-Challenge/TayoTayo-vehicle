package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

class OperationalSafetyTest {
    private val now=1_000_000L
    private fun <T> seen(value:T)=Observation(value,Source.SDK,now,ResultStatus.TARGET_OBSERVED)
    private fun vehicle()=VehicleSnapshot(connected=true,speedKph=seen(0.0),gear=seen(Gear.PARK),
        parkingBrakeSupported=true,parkingBrake=seen(false),doorLocked=seen(true))
    private fun engine()=ShuttleEngine(SampleTrip.create().copy(integrationMode=IntegrationMode.SDK)).also {it.updateVehicle(vehicle(),now)}
    @Test fun releasedParkingBrakeAllowsWholeOperationalFlowButNoDoorRequest() {
        val e=engine()
        assertTrue(e.board("child-a",now));assertTrue(e.applyRoute(e.state.routeOrder,now));assertTrue(e.startDriving(now))
        assertTrue(e.chooseAlighting(now));assertFalse(e.confirmSurroundings(now));assertFalse(e.prepareUnlock(now));assertNull(e.consumeUnlock(now))
        assertTrue(e.confirmHandover("child-a",now));assertTrue(e.recordAlightingAndContinue("child-a",now))
        assertTrue(e.beginEndReview(now));assertTrue(e.inspectAndFinish(now))
        assertEquals(TripPhase.ENDED,e.state.phase)
    }
    @Test fun missingOrStaleParkingBrakeOnlyBlocksDoorLab() {
        for (brake in listOf(Observation<Boolean>(),seen(true).copy(observedAtMillis=now-6000))) {
            val e=engine();e.updateVehicle(vehicle().copy(parkingBrake=brake),now)
            assertTrue(e.stationaryBlockers(now).isEmpty());assertTrue(e.doorStationaryBlockers(now).isNotEmpty())
        }
    }
    @Test fun motionWrongGearUnknownAndStaleRemainBlockedForRecords() {
        for(v in listOf(vehicle().copy(speedKph=seen(1.0)),vehicle().copy(gear=seen(Gear.DRIVE)),
            vehicle().copy(speedKph=Observation()),vehicle().copy(speedKph=seen(0.0).copy(observedAtMillis=now-6000)))) {
            val e=engine();e.updateVehicle(v,now);assertFalse(e.board("child-a",now));assertFalse(e.applyRoute(e.state.routeOrder,now))
        }
    }
    @Test fun parkingBrakeStillRequiredForDoorAfterOrdinaryArrival() {
        val e=engine();e.board("child-a",now);e.startDriving(now);e.chooseAlighting(now)
        assertFalse(e.prepareUnlock(now))
        e.updateVehicle(vehicle().copy(parkingBrake=seen(true)),now)
        assertTrue(e.confirmSurroundings(now));assertTrue(e.prepareUnlock(now))
        e.updateVehicle(vehicle(),now);assertNull(e.consumeUnlock(now))
    }
}
