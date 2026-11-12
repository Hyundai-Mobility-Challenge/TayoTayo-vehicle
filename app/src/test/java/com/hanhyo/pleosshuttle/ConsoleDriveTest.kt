package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

class ConsoleDriveTest {
    private val from = GeoPoint(37.5665, 126.9780)
    private val to = GeoPoint(37.5665, 126.9880) // 약 880m 동쪽

    @Test fun parkGearDoesNotMove() {
        val step = ConsoleDriveMath.advance(from, to, 10.0, 1.0, gearDrive = false)
        assertEquals(from, step.position)
        assertEquals(0.0, step.movedMeters, 0.0)
        assertEquals(ConsoleDriveMath.distanceMeters(from, to), step.remainingMeters, 0.01)
        assertFalse(step.arrivedNearTarget)
    }

    @Test fun zeroOrMissingSpeedDoesNotMoveInDrive() {
        assertEquals(from, ConsoleDriveMath.advance(from, to, 0.0, 1.0, true).position)
        assertEquals(from, ConsoleDriveMath.advance(from, to, null, 1.0, true).position)
        assertEquals(from, ConsoleDriveMath.advance(from, to, -5.0, 1.0, true).position)
    }

    @Test fun driveMovesProportionallyToSpeedTimesTime() {
        val total = ConsoleDriveMath.distanceMeters(from, to)
        val a = ConsoleDriveMath.advance(from, to, 10.0, 1.0, true)
        val b = ConsoleDriveMath.advance(from, to, 10.0, 2.0, true)
        assertEquals(10.0, ConsoleDriveMath.distanceMeters(from, a.position), 0.1)
        assertEquals(20.0, ConsoleDriveMath.distanceMeters(from, b.position), 0.1)
        assertEquals(total - 20.0, b.remainingMeters, 0.1)
    }

    @Test fun clampsAtTargetWithoutOvershoot() {
        val step = ConsoleDriveMath.advance(GeoPoint(37.5665, 126.98795), to, 30.0, 3.0, true)
        assertEquals(to, step.position)
        assertEquals(0.0, step.remainingMeters, 0.0)
        assertTrue(step.arrivedNearTarget)
    }

    @Test fun longPausesAreCappedPerTick() {
        val step = ConsoleDriveMath.advance(from, to, 10.0, 60.0, true)
        assertEquals(10.0 * ConsoleDriveMath.MAX_DT_SECONDS, ConsoleDriveMath.distanceMeters(from, step.position), 0.1)
    }

    @Test fun nearTargetFlagUsesRadius() {
        val near = GeoPoint(37.5665, 126.9880 - 0.0004) // 약 35m
        assertTrue(ConsoleDriveMath.advance(near, to, 0.0, 1.0, false).arrivedNearTarget)
        val far = GeoPoint(37.5665, 126.9880 - 0.0008) // 약 70m
        assertFalse(ConsoleDriveMath.advance(far, to, 0.0, 1.0, false).arrivedNearTarget)
    }

    @Test fun etaUsesSaneDefaultWhenStopped() {
        assertEquals(60, ConsoleDriveMath.etaSeconds(500.0, 0.0)) // 30km/h 기본
        assertEquals(25, ConsoleDriveMath.etaSeconds(500.0, 20.0))
    }

    private fun driving(trip: TripState) = trip.copy(phase = TripPhase.DRIVING, integrationMode = IntegrationMode.SDK)

    @Test fun startsFromOriginTowardFirstStopThenFromCompletedStop() {
        var clock = 0L
        val drive = ConsoleDrive { clock }
        val trip = driving(SampleTrip.create())
        assertTrue(drive.start(trip))
        var frame = drive.tick(trip, 0.0, true)!!
        assertEquals(GeoPoint(trip.origin.latitude, trip.origin.longitude), frame.position)
        assertEquals(trip.stops.first { it.id == trip.routeOrder[0] }.name, frame.target.name)

        clock = 1000
        frame = drive.tick(trip, 20.0, true)!!
        assertEquals(20.0, ConsoleDriveMath.distanceMeters(GeoPoint(trip.origin.latitude, trip.origin.longitude), frame.position), 0.1)

        // 정차(ALIGHTING) 중에는 움직이지 않는다.
        val alighting = trip.copy(phase = TripPhase.ALIGHTING)
        clock = 2000
        val held = drive.tick(alighting, 20.0, true)!!
        assertEquals(frame.position, held.position)

        // 다음 지점으로 출발하면 방금 마친 정류장에서 시작해 다음 정류장을 향한다.
        val next = trip.copy(currentStopIndex = 1)
        clock = 3000
        val leg2 = drive.tick(next, 0.0, true)!!
        val done = trip.stops.first { it.id == trip.routeOrder[0] }
        assertEquals(GeoPoint(done.latitude, done.longitude), leg2.position)
        assertEquals(trip.stops.first { it.id == trip.routeOrder[1] }.name, leg2.target.name)
    }

    @Test fun parkKeepsPositionAcrossTicks() {
        var clock = 0L
        val drive = ConsoleDrive { clock }
        val trip = driving(SampleTrip.create())
        drive.start(trip); drive.tick(trip, 0.0, true)
        clock = 5000
        val frame = drive.tick(trip, 15.0, false)!!
        assertEquals(GeoPoint(trip.origin.latitude, trip.origin.longitude), frame.position)
    }

    @Test fun afterLastStopTargetIsOriginForDropOff() {
        val trip = driving(SampleTrip.create())
        val returning = trip.copy(currentStopIndex = trip.routeOrder.size)
        val target = ConsoleDriveMath.target(returning)!!
        assertTrue(target.toOrigin)
        assertEquals(trip.origin.name, target.name)
        val last = trip.stops.first { it.id == trip.routeOrder.last() }
        assertEquals(GeoPoint(last.latitude, last.longitude), ConsoleDriveMath.legStart(returning))
    }

    @Test fun pickupLastTargetIsInstitution() {
        val trip = driving(SampleTrip.createPickup())
        assertTrue(trip.isPickup)
        val toInstitution = trip.copy(currentStopIndex = trip.routeOrder.size)
        val target = ConsoleDriveMath.target(toInstitution)!!
        assertTrue(target.toOrigin)
        assertEquals(GeoPoint(trip.origin.latitude, trip.origin.longitude), target.point)
    }

    @Test fun stopsWhenTripEndsOrChanges() {
        var clock = 0L
        val drive = ConsoleDrive { clock }
        val trip = driving(SampleTrip.create())
        drive.start(trip)
        assertNull(drive.tick(trip.copy(phase = TripPhase.END_REVIEW), 10.0, true))
        assertFalse(drive.running)
        drive.start(trip)
        assertFalse(drive.retain(true, "other-trip"))
        drive.start(trip)
        assertFalse(drive.retain(false, trip.tripId))
        assertNull(ConsoleDriveMath.target(trip.copy(phase = TripPhase.PREPARING)))
    }

    @Test fun statusTextShowsSpeedGearDistanceAndNear() {
        val trip = driving(SampleTrip.create())
        val frame = ConsoleDriveFrame(GeoPoint(0.1, 0.1), ConsoleDriveMath.target(trip)!!, 30, 4, true, 8.0, true)
        val status = ConsoleDriveText.status(frame, 28.8, ConsoleDriveText.gearLabel(8))
        assertTrue(status, status.contains("29km/h") && status.contains("기어 D") && status.contains("30m") && status.contains("도착 지점 근처"))
        assertTrue(ConsoleDriveText.cardLine(frame).contains("30m"))
        assertEquals("P", ConsoleDriveText.gearLabel(4))
        assertEquals("1.2km", ConsoleDriveText.distance(1234))
    }
}

class ConsoleDrivePolicyTest {
    private fun ctx(phase: TripPhase = TripPhase.DRIVING, foreground: Boolean = true, ux: Boolean = false,
                    sdk: Boolean = true, bound: Boolean = true, debug: Boolean = true, storageOk: Boolean = true) =
        ConsoleDriveContext(debug, sdk, bound, phase, storageOk, foreground, ux)

    @Test fun drivingRestrictionOrBackgroundDoesNotStop() {
        assertTrue(ConsoleDrivePolicy.keep(ctx(foreground = false)))
        assertTrue(ConsoleDrivePolicy.keep(ctx(ux = true)))
        assertTrue(ConsoleDrivePolicy.keep(ctx(foreground = false, ux = true)))
        assertTrue(ConsoleDrivePolicy.keep(ctx(phase = TripPhase.ALIGHTING, foreground = false)))
    }

    @Test fun stopsOnTripEndModeChangeUnbindOrStorageError() {
        assertFalse(ConsoleDrivePolicy.keep(ctx(phase = TripPhase.END_REVIEW)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(phase = TripPhase.ENDED)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(phase = TripPhase.PREPARING)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(sdk = false)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(bound = false)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(debug = false)))
        assertFalse(ConsoleDrivePolicy.keep(ctx(storageOk = false)))
    }

    @Test fun positionKeepsAdvancingWhileAppHiddenAndIsReadableAfter() {
        var clock = 0L
        val drive = ConsoleDrive { clock }
        val trip = SampleTrip.create().copy(phase = TripPhase.DRIVING, integrationMode = IntegrationMode.SDK)
        drive.start(trip); drive.tick(trip, 0.0, true)
        // 앱이 가려진 동안(foreground=false, UX 제한)에도 유지 판단이 true이고 틱이 계속된다.
        repeat(25) {
            clock += 1000
            assertTrue(drive.retain(ConsoleDrivePolicy.keep(ConsoleDriveContext(true, true, true, trip.phase, true, false, true)), trip.tripId))
            drive.tick(trip, 10.0, true)
        }
        val origin = GeoPoint(trip.origin.latitude, trip.origin.longitude)
        assertEquals(250.0, ConsoleDriveMath.distanceMeters(origin, drive.frame!!.position), 0.5)
    }
}
