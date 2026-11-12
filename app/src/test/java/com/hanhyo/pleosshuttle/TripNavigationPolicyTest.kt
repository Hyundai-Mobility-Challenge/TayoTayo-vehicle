package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

class TripNavigationPolicyTest {
    private val now=100000L
    private val trip=SampleTrip.create().let {it.copy(phase=TripPhase.DRIVING,integrationMode=IntegrationMode.SDK,children=it.children.map {c->c.copy(status=Attendance.ONBOARD)})}
    private val stop=trip.currentStop!!
    private fun fix(at:Long=now)=TrackingLocation(stop.latitude,stop.longitude,at,false,"native-route",0)
    private fun nav(at:Long=now)=TrackingNavigation("READY",1,"native-route",stop.latitude,stop.longitude,20,50,at)
    private fun motion(gear:Gear=Gear.PARK,at:Long=now)=DriveObservation(0.0,gear,gear!=Gear.PARK,at)
    @Test fun drivingOpensNativeOncePerLegWithoutChangingTrip() {
        val p=TripNavigationPolicy()
        assertTrue(p.evaluate(trip,fix(),nav(),motion(Gear.DRIVE),now).showNavigation)
        assertFalse(p.evaluate(trip,fix(),nav(),motion(Gear.DRIVE),now+1).showNavigation)
        assertEquals(TripPhase.DRIVING,trip.phase)
    }
    @Test fun arrivalNeedsSustainedProximityAndFreshParkedState() {
        val p=TripNavigationPolicy()
        assertFalse(p.evaluate(trip,fix(),nav(),motion(),now).arrivalReady)
        assertTrue(p.evaluate(trip,fix(now+4000),nav(now+4000),motion(at=now+4000),now+4000).arrivalReady)
        assertFalse(p.evaluate(trip,fix(now+6000),nav(now+6000),motion(Gear.DRIVE,now+6000),now+6000).arrivalReady)
    }
    @Test fun wrongTargetForeignRouteSyntheticAndStaleNeverTrigger() {
        for ((f,n) in listOf(fix().copy(simulated=true) to nav(),fix().copy(routeId="foreign") to nav(),
            fix() to nav().copy(latitude=0.0),fix(now-31000) to nav(),fix() to nav(now-31000),fix() to nav().copy(state="REROUTING"))) {
            val p=TripNavigationPolicy();assertFalse(p.evaluate(trip,f,n,motion(Gear.DRIVE),now).arrivalReady)
            assertEquals(NavigationDecision(),p.evaluate(trip,f,n,motion(),now+4000))
        }
    }
    @Test fun stopChangeOrRerouteResetsArrivalDebounce() {
        val p=TripNavigationPolicy();p.evaluate(trip,fix(),nav(),motion(),now)
        assertFalse(p.evaluate(trip.copy(routeVersion=1),fix(now+4000),nav(now+4000),motion(at=now+4000),now+4000).arrivalReady)
    }
    @Test fun staleMotionOrUxRestrictionCannotEnableArrivalAction() {
        for(m in listOf(motion(at=now-6000),motion().copy(uxRestricted=true),motion().copy(uxRestricted=null),motion().copy(speedMps=1.0))) {
            val p=TripNavigationPolicy();p.evaluate(trip,fix(),nav(),m,now)
            assertFalse(p.evaluate(trip,fix(now+4000),nav(now+4000),m,now+4000).arrivalReady)
        }
    }
    @Test fun noArrivalForAlightingEndedMissingLocationOrWrongRoadDistance() {
        val p=TripNavigationPolicy()
        for(phase in listOf(TripPhase.ALIGHTING,TripPhase.ENDED,TripPhase.PREPARING))
            assertEquals(NavigationDecision(),p.evaluate(trip.copy(phase=phase),fix(),nav(),motion(Gear.DRIVE),now))
        assertFalse(p.evaluate(trip,null,nav(),motion(Gear.DRIVE),now).arrivalReady)
        p.evaluate(trip,fix(),nav().copy(remainMeters=600),motion(),now)
        assertFalse(p.evaluate(trip,fix(now+4000),nav(now+4000).copy(remainMeters=600),motion(at=now+4000),now+4000).arrivalReady)
    }
    @Test fun navigationScreenStillOpensWithoutGpsAndDoesNotReopenOnReroute() {
        val p=TripNavigationPolicy()
        assertEquals(NavigationDecision(showNavigation=true),p.evaluate(trip,null,null,motion(Gear.DRIVE),now))
        assertFalse(p.evaluate(trip,fix(),nav().copy(epoch=2),motion(Gear.DRIVE),now).showNavigation)
        assertTrue(p.evaluate(trip.copy(currentStopIndex=1),null,null,motion(Gear.DRIVE),now).showNavigation)
    }
    @Test fun arrivalUsesCurrentWaypointDistanceInsteadOfFinalDestination() {
        val p=TripNavigationPolicy()
        fun route(at:Long)=nav(at).copy(latitude=1.0,longitude=1.0,remainMeters=5000,
            stops=listOf(NavigationStopEta(stop.latitude,stop.longitude,30,50,at)))
        p.evaluate(trip,fix(),route(now),motion(),now)
        assertTrue(p.evaluate(trip,fix(now+4000),route(now+4000),motion(at=now+4000),now+4000).arrivalReady)
        assertFalse(p.evaluate(trip,fix(now+4000),route(now+4000).copy(stops=listOf(NavigationStopEta(0.0,0.0,30,50,now+4000))),motion(at=now+4000),now+4000).arrivalReady)
    }
}
