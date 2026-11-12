package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import kotlin.math.*

data class DriveObservation(val speedMps: Double? = null, val gear: Gear = Gear.UNKNOWN,
    val uxRestricted: Boolean? = null, val receivedAt: Long = 0) {
    fun fresh(now:Long) = speedMps?.isFinite()==true && now-receivedAt in 0..5000 && gear!=Gear.UNKNOWN
    fun parked(now:Long) = fresh(now) && abs(speedMps!!) <= 0.01 && gear==Gear.PARK && uxRestricted==false
}

data class NavigationDecision(val showNavigation: Boolean=false, val arrivalReady: Boolean=false)

/** Never changes a trip or operates a door. Location alone cannot authorize a record. */
class TripNavigationPolicy {
    private var key: String? = null
    private var shownLeg: String? = null
    private var nearSince: Long? = null
    fun evaluate(trip:TripState, fix:TrackingLocation?, nav:TrackingNavigation?, motion:DriveObservation, now:Long):NavigationDecision {
        val currentKey="${trip.tripId}/${trip.routeVersion}/${trip.currentStopIndex}/${nav?.routeId}/${nav?.epoch}"
        if (key!=currentKey) { key=currentKey;nearSince=null }
        val stop=trip.currentStop
        val active = trip.integrationMode==IntegrationMode.SDK && trip.phase==TripPhase.DRIVING && stop!=null &&
            trip.currentStopChildren.any {it.status==Attendance.ONBOARD}
        val leg="${trip.tripId}/${trip.routeVersion}/${trip.currentStopIndex}"
        // Opening the installed navigator does not need a GPS fix. Arrival confirmation does.
        val show = active && shownLeg!=leg && motion.fresh(now) && motion.gear==Gear.DRIVE
        if (show) shownLeg=leg
        if (!active) shownLeg=null
        val target = nav?.stops?.firstOrNull() ?: nav?.let { n ->
            if (n.latitude!=null && n.longitude!=null && n.remainSeconds!=null && n.remainMeters!=null && n.receivedAt!=null)
                NavigationStopEta(n.latitude,n.longitude,n.remainSeconds,n.remainMeters,n.receivedAt) else null
        }
        val valid = active &&
            fix!=null && !fix.simulated && fix.valid(now) && nav?.state=="READY" &&
            !nav.routeId.isNullOrBlank() && nav.routeId==fix.routeId &&
            target!=null && now-target.receivedAt in 0..30_000 &&
            abs(target.latitude-stop!!.latitude)<0.00001 && abs(target.longitude-stop.longitude)<0.00001
        if (!valid) { nearSince=null;return NavigationDecision(showNavigation=show) }
        val near = distanceMeters(fix!!.latitude,fix.longitude,stop!!.latitude,stop.longitude)<=150 &&
            target!!.meters in 0..250
        if (!near) nearSince=null else if (nearSince==null) nearSince=now
        val ready=nearSince?.let {now-it>=3000}==true && motion.parked(now)
        return NavigationDecision(show,ready)
    }
    private fun distanceMeters(a:Double,b:Double,c:Double,d:Double):Double {
        val lat=Math.toRadians(c-a);val lon=Math.toRadians(d-b)
        val h=sin(lat/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(lon/2).pow(2)
        return 6371000*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
