package com.hanhyo.pleosshuttle

import org.junit.Assert.*
import org.junit.Test

class NavigationTrackingTest {
    private var now=100_000L
    private fun ready()=NavigationTracking { now }.apply {
        guidance(true,false);position(37.5,127.0,"route-a",4);destination(37.54,127.03,125,500)
    }
    @Test fun nativeFieldsRemainUnmodified() {
        val state=ready();assertEquals("READY",state.snapshot().state)
        assertEquals(125,state.snapshot().remainSeconds);assertEquals(500,state.snapshot().remainMeters)
        assertEquals("route-a",state.location!!.routeId);assertEquals(4,state.location!!.routeIndex)
    }
    @Test fun rerouteDiscardsOldTargetUntilCompletionAndFreshObservations() {
        val state=ready();state.invalidate();state.destination(37.54,127.03,120,450)
        state.position(37.51,127.01,"route-a",5);assertEquals("REROUTING",state.snapshot().state)
        state.completed();assertEquals("UNAVAILABLE",state.snapshot().state)
        state.position(37.51,127.01,"route-b",0);state.destination(37.54,127.03,140,600)
        assertEquals("READY",state.snapshot().state);assertEquals("route-b",state.snapshot().routeId)
    }
    @Test fun routeIdentityChangeRequiresNewTargetObservation() {
        val state=ready();state.position(37.51,127.01,"route-b",0)
        assertEquals("UNAVAILABLE",state.snapshot().state)
        state.destination(37.54,127.03,200,800);assertEquals("READY",state.snapshot().state)
    }
    @Test fun newDestinationCannotReusePriorFix() {
        val state=ready();state.destination(37.56,127.04,200,800)
        assertEquals("UNAVAILABLE",state.snapshot().state)
        state.position(37.51,127.01,"route-b",0);assertEquals("READY",state.snapshot().state)
    }
    @Test fun freshLocationDoesNotRefreshStaleEta() {
        val state=ready();now+=31_000;state.position(37.51,127.01,"route-a",5)
        assertEquals("UNAVAILABLE",state.snapshot().state)
    }
    @Test fun unknownIdentityAndModeNeverClaimNativeEta() {
        val state=ready();state.position(37.51,127.01,null,null);assertEquals("UNAVAILABLE",state.snapshot().state)
        state.guidance(false,false);assertNull(state.location)
        state.position(37.51,127.01,"route-a",3);assertNull(state.location)
    }
    @Test fun pendingRouteCannotBecomeReadyFromModeChangeAlone() {
        val state=NavigationTracking { now };state.invalidate();state.guidance(true,false)
        state.position(37.5,127.0,"route-a",4);state.destination(37.54,127.03,125,500)
        assertEquals("REROUTING",state.snapshot().state)
    }
    @Test fun backgroundDoesNotEndOptedInTripButCannotStartService() {
        val desired=TrackingLifecycle.desired(true,true,true,true,true)
        assertTrue(desired);assertTrue(TrackingLifecycle.mayStart(desired,true,true))
        assertFalse(TrackingLifecycle.mayStart(desired,false,true))
        assertFalse(TrackingLifecycle.mayStart(desired,true,false))
        assertFalse(TrackingLifecycle.desired(false,true,true,true,true))
        assertFalse(TrackingLifecycle.desired(true,true,true,true,false))
        assertFalse(TrackingLifecycle.desired(true,true,false,true,true))
    }

    private fun multi():NavigationTracking = NavigationTracking { now }.apply {
        expectStops(listOf(37.51 to 127.01,37.52 to 127.02,37.54 to 127.03))
        guidance(true,false);position(37.5,127.0,"multi-route",1)
        destination(37.54,127.03,600,5000)
        waypoints(listOf(NavigationStopEta(37.51,127.01,60,500,now),NavigationStopEta(37.52,127.02,300,2000,now)))
    }
    @Test fun cumulativeNativeWaypointsAreBoundToApprovedOrder() {
        val s=multi();val n=s.snapshot()
        assertEquals("READY",n.state);assertEquals(listOf(60,300,600),n.stops.map { it.seconds })
        assertEquals(60,n.remainSeconds)
        s.waypoints(listOf(NavigationStopEta(37.52,127.02,60,500,now),NavigationStopEta(37.51,127.01,300,2000,now)))
        assertEquals("UNAVAILABLE",s.snapshot().state)
    }
    @Test fun staleWaypointCannotBeRenewedByFreshDestination() {
        val s=multi();now+=31_000;s.position(37.5,127.0,"multi-route",1);s.destination(37.54,127.03,500,4000)
        assertEquals("UNAVAILABLE",s.snapshot().state)
    }
    @Test fun approvedOrderChangeAndRerouteRequireNewWaypointObservations() {
        val s=multi();s.expectStops(listOf(37.52 to 127.02,37.54 to 127.03));assertEquals("UNAVAILABLE",s.snapshot().state)
        s.position(37.5,127.0,"new-route",1);s.destination(37.54,127.03,500,4000)
        s.waypoints(listOf(NavigationStopEta(37.52,127.02,200,1500,now)));assertEquals("READY",s.snapshot().state)
        s.invalidate();assertEquals("REROUTING",s.snapshot().state)
        s.completed();s.position(37.5,127.0,"reroute",1);s.destination(37.54,127.03,400,3000)
        assertEquals("UNAVAILABLE",s.snapshot().state)
    }
    @Test fun singleApprovedDestinationRejectsUnexpectedWaypoints() {
        val s=NavigationTracking { now };s.expectStops(listOf(37.54 to 127.03));s.guidance(true,false)
        s.position(37.5,127.0,"single",1);s.destination(37.54,127.03,600,5000)
        s.waypoints(listOf(NavigationStopEta(37.51,127.01,60,500,now)));assertEquals("UNAVAILABLE",s.snapshot().state)
        s.waypoints(emptyList());assertEquals("READY",s.snapshot().state)
    }

    @Test fun explicitSettledNativeStateRequiresNewSamplesWithoutCompletionCallback() {
        val s=multi();s.invalidate();s.routeState(true,false,true)
        s.destination(37.54,127.03,500,4000);assertEquals("REROUTING",s.snapshot().state)
        s.routeState(true,false,false);assertEquals("UNAVAILABLE",s.snapshot().state)
        s.position(37.5,127.0,"settled",1);s.destination(37.54,127.03,500,4000)
        s.waypoints(listOf(NavigationStopEta(37.51,127.01,60,500,now),NavigationStopEta(37.52,127.02,300,2000,now)))
        assertEquals("READY",s.snapshot().state)
        s.routeState(true,false,false);assertEquals("READY",s.snapshot().state)
    }

    private fun namedMissing():NavigationTracking = NavigationTracking {now}.apply {
        expectStops(listOf(37.51 to 127.01,37.52 to 127.02,37.54 to 127.03),listOf("A","B","C"))
        guidance(true,false);position(37.5,127.0,"named-route",1)
        destination(37.54,127.03,300,3000,"C")
        waypoints(listOf(NavigationStopEta(0.0,0.0,60,500,now,"A"),NavigationStopEta(0.0,0.0,180,2000,now,"B")))
    }
    @Test fun missingWaypointCoordinatesUseUniqueOrderedNamesWithNativeFinalDestination() {
        val n=namedMissing().snapshot();assertEquals("READY",n.state)
        assertEquals(listOf(60,180,300),n.stops.map {it.seconds})
        assertEquals(listOf("REGISTERED_STOP","REGISTERED_STOP","PLEOS"),n.stops.map {it.coordinateSource})
        assertEquals(37.51,n.latitude!!,.000001);assertEquals("A",n.stops[0].observedName)
    }
    @Test fun wrongNameOrderOrNonzeroWrongCoordinateNeverFallsBack() {
        val s=namedMissing()
        s.waypoints(listOf(NavigationStopEta(0.0,0.0,60,500,now,"B"),NavigationStopEta(0.0,0.0,180,2000,now,"A")))
        assertEquals("UNAVAILABLE",s.snapshot().state)
        s.waypoints(listOf(NavigationStopEta(38.0,128.0,60,500,now,"A"),NavigationStopEta(0.0,0.0,180,2000,now,"B")))
        assertEquals("UNAVAILABLE",s.snapshot().state)
    }
    @Test fun duplicateOrMissingNamesBlockRegisteredCoordinates() {
        val s=namedMissing();s.expectStops(listOf(37.51 to 127.01,37.52 to 127.02,37.54 to 127.03),listOf("A","A","C"))
        s.position(37.5,127.0,"duplicate",1);s.destination(37.54,127.03,300,3000,"C")
        s.waypoints(listOf(NavigationStopEta(0.0,0.0,60,500,now,"A"),NavigationStopEta(0.0,0.0,180,2000,now,"A")))
        assertEquals("UNAVAILABLE",s.snapshot().state)
        val missing=namedMissing();missing.waypoints(listOf(NavigationStopEta(0.0,0.0,60,500,now),NavigationStopEta(0.0,0.0,180,2000,now,"B")))
        assertEquals("UNAVAILABLE",missing.snapshot().state)
    }
    @Test fun nativeFinalDestinationCannotBeSubstitutedAndRouteChangeClearsNamedMatch() {
        val s=namedMissing();s.destination(0.0,0.0,300,3000,"C");s.position(37.5,127.0,"changed",1)
        s.waypoints(listOf(NavigationStopEta(0.0,0.0,60,500,now,"A"),NavigationStopEta(0.0,0.0,180,2000,now,"B")))
        assertEquals("UNAVAILABLE",s.snapshot().state)
        val r=namedMissing();r.position(37.5,127.0,"different-route",1);assertEquals("UNAVAILABLE",r.snapshot().state)
        val stale=namedMissing();now+=31_000;stale.position(37.5,127.0,"named-route",1);assertEquals("UNAVAILABLE",stale.snapshot().state)
    }
}
