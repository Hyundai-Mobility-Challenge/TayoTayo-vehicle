package com.hanhyo.pleosshuttle

import org.junit.Assert.*
import org.junit.Test

class MovingTrackingDemoTest {
    @Test fun coordinatesMoveAndReachOnlyTheSyntheticDestination() {
        var clock=0L; val d=MovingTrackingDemo { clock };d.start("trip/0/0",37.54,127.03)
        val start=d.sample()!!;clock=45_000;val middle=d.sample()!!;clock=90_000;val end=d.sample()!!
        assertTrue(middle.longitude>start.longitude);assertTrue(end.latitude>middle.latitude)
        assertEquals(37.54,end.latitude,1e-9);assertEquals(127.03,end.longitude,1e-9)
        assertEquals(90,start.seconds);assertEquals(45,middle.seconds);assertEquals(0,end.seconds)
        assertEquals(100,end.progress);assertEquals(MovingDemoPhase.ARRIVED,d.phase)
        clock=200_000;assertEquals(end,d.sample())
    }
    @Test fun pauseFreezesAndResumeDoesNotJump() {
        var clock=0L;val d=MovingTrackingDemo { clock };d.start("a",37.54,127.03)
        clock=20_000;d.pause();val paused=d.sample();clock=120_000;assertEquals(paused,d.sample())
        d.resume();assertEquals(paused,d.sample());clock+=10_000;assertEquals(60,d.sample()!!.seconds)
    }
    @Test fun stopRemovesFixAndRestartGetsNewRouteIdentity() {
        val d=MovingTrackingDemo { 0L };d.start("a",37.54,127.03);val id=d.routeId
        d.stop();assertNull(d.sample());assertNull(d.routeId);assertNull(d.key)
        d.start("b",37.55,127.04);assertNotEquals(id,d.routeId);assertEquals(0,d.sample()!!.progress)
    }
    @Test fun backwardsClockCannotMoveBehindStartingPoint() {
        var clock=100L;val d=MovingTrackingDemo { clock };d.start("a",37.54,127.03)
        val first=d.sample();clock=0;assertEquals(first,d.sample())
    }
    @Test fun routeChangeOrLossOfEligibilityDiscardsObservation() {
        val d=MovingTrackingDemo { 0L };d.start("trip/route/stop",37.54,127.03)
        assertTrue(d.retain(true,"trip/route/stop"))
        assertFalse(d.retain(true,"trip/route/next-stop"));assertNull(d.sample())
        // UI background, sharing disabled, SDK mode, no onboard children, and completion all remove eligibility.
        repeat(5) { d.start("trip",37.54,127.03);assertFalse(d.retain(false,"trip"));assertNull(d.sample()) }
    }
    @Test fun invalidTargetsNeverStart() {
        val d=MovingTrackingDemo()
        for(p in listOf(Double.NaN to 127.0, 90.0 to 127.0, 37.0 to 181.0, 0.0 to 0.0)) {
            try { d.start("a",p.first,p.second);fail("Invalid target accepted") } catch(_:IllegalArgumentException) {}
            assertNull(d.sample())
        }
    }
}
