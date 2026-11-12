package com.hanhyo.pleosshuttle

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.shuttle.net.Api
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.util.UUID

/** Only run against serve-tracking-fixture.py. Isolated prefs, no real trip files or FCM. */
class TrackingTransportDeviceTest {
    @Test fun androidVehiclePayloadReachesGuardianAndCompletionEndsAccess() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("fixtureBase")
        val vehicleToken = args.getString("fixtureVehicle")
        val guardianToken = args.getString("fixtureGuardian")
        val childId = args.getString("fixtureChild")
        Assume.assumeTrue("Explicit synthetic fixture required", listOf(base, vehicleToken, guardianToken, childId).all { !it.isNullOrEmpty() })
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "tracking-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val prefs = context.getSharedPreferences("institution", Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putString("base", base).putString("token", vehicleToken).commit())
            val connection = InstitutionConnection(context)
            val prepared = connection.createTrip()
            assertEquals("Assigned rosters must default to real Pleos integration", IntegrationMode.SDK, prepared.integrationMode)
            connection.bind(prepared)
            val trip = prepared.copy(phase = TripPhase.DRIVING,
                children = prepared.children.map { it.copy(status = Attendance.ONBOARD) },
                ledger = prepared.children.map { LedgerEntry(UUID.randomUUID().toString(), it.id,
                    Attendance.NOT_BOARDED, Attendance.ONBOARD, System.currentTimeMillis(), "Synthetic transport test") })
            assertEquals(trip.children.size, connection.sync(trip))
            assertEquals(0, connection.sync(trip))
            val fix = TrackingLocation(37.55, 127.02, System.currentTimeMillis(), true, "synthetic-route", 0)
            val own = trip.stops.first { stop -> stop.id == trip.children.first { it.id == childId }.stopId }
            val native = TrackingNavigation("READY", 1, "synthetic-route", own.latitude, own.longitude, 420, 1500, System.currentTimeMillis())
            connection.reportTracking(trip, true, fix, native)
            fun guardian() = Api.request(base!!, "/api/guardian/tracking", guardianToken!!, JSONObject().put("childId", childId))
            val view = guardian()
            assertTrue(view.getBoolean("available"))
            assertEquals(trip.tripId, view.getString("tripId"))
            assertEquals("SIMULATED", view.getString("source"))
            assertEquals(fix.latitude, view.getJSONObject("location").getDouble("latitude"), .000001)
            if (trip.currentStop?.id == own.id) assertEquals("SIMULATED_NAVI_ESTIMATE", view.getJSONObject("eta").getString("kind"))
            else assertTrue(view.isNull("eta"))
            var demoClock = 0L
            val demo = MovingTrackingDemo { demoClock }
            val next = trip.currentStop!!
            demo.start("synthetic-test", next.latitude, next.longitude)
            fun transmit() {
                val frame = demo.sample()!!
                val at = System.currentTimeMillis()
                connection.reportTracking(trip, true, TrackingLocation(frame.latitude, frame.longitude, at, true, demo.routeId, 0),
                    TrackingNavigation("READY", 3, demo.routeId, next.latitude, next.longitude, frame.seconds, frame.meters, at))
            }
            transmit(); val firstPoint = guardian().getJSONObject("location")
            demoClock = 45_000; transmit(); val movingView = guardian()
            assertTrue(movingView.getJSONObject("location").getDouble("longitude") > firstPoint.getDouble("longitude"))
            if (next.id == own.id) {
                assertEquals("SIMULATED_NAVI_ESTIMATE", movingView.getJSONObject("eta").getString("kind"))
                assertTrue(movingView.getJSONObject("eta").getString("label").contains("모의"))
            } else assertTrue(movingView.isNull("eta"))
            demo.pause(); val paused = demo.sample(); demoClock += 30_000; assertEquals(paused, demo.sample())
            demo.stop(); assertNull(demo.sample())
            // Stopping removes the observation; an obsolete queued generation cannot republish it.
            connection.reportTracking(trip, true, null)
            connection.reportTracking(trip, true, fix, native, stillCurrent = { false })
            assertTrue(guardian().isNull("location")); assertTrue(guardian().isNull("eta"))
            connection.reportTracking(trip, true, fix, TrackingNavigation("REROUTING", 4))
            assertTrue(guardian().isNull("eta"))
            // Multi-stop cumulative native payload travels through the real Android serializer/server boundary.
            val ordered=trip.stops.filter { it.id!=own.id }+own
            assertTrue(ordered.size in 2..3)
            val multi=trip.copy(routeOrder=ordered.map {it.id},routeVersion=trip.routeVersion+1,currentStopIndex=0)
            val at=System.currentTimeMillis()
            val samples=ordered.mapIndexed {i,p -> NavigationStopEta(p.latitude,p.longitude,(i+1)*60,(i+1)*500,at)}
            val nav=TrackingNavigation("READY",5,"synthetic-native-cumulative",samples[0].latitude,samples[0].longitude,60,500,at,samples)
            connection.reportTracking(multi,true,TrackingLocation(37.55,127.02,at,false,"synthetic-native-cumulative",0),nav)
            val cumulative=guardian().getJSONObject("eta")
            assertEquals("PLEOS_WAYPOINT_ESTIMATE",cumulative.getString("kind"))
            assertEquals(ordered.size,cumulative.getInt("minutes"))
            assertEquals(0,cumulative.getInt("dwellSeconds"))
            assertTrue(guardian().has("startedAt"))
            val registered=samples.mapIndexed {i,p -> p.copy(observedName=ordered[i].name,coordinateSource=if(i==samples.lastIndex)"PLEOS" else "REGISTERED_STOP")}
            connection.reportTracking(multi,true,TrackingLocation(37.55,127.02,at,false,"synthetic-native-cumulative",0),nav.copy(stops=registered))
            assertEquals("PLEOS_REGISTERED_STOP_ESTIMATE",guardian().getJSONObject("eta").getString("kind"))
            val invalid=registered.mapIndexed {i,p -> if(i==0)p.copy(observedName="WRONG") else p}
            connection.reportTracking(multi,true,TrackingLocation(37.55,127.02,at,false,"synthetic-native-cumulative",0),nav.copy(stops=invalid))
            assertTrue(guardian().isNull("eta"))

            val arriving=multi.copy(phase=TripPhase.ALIGHTING,currentStopIndex=multi.routeOrder.indexOf(own.id))
            connection.reportTracking(arriving,true,fix,native)
            assertEquals("ARRIVED",guardian().getString("journeyStatus"))
            val dropped=arriving.copy(children=arriving.children.map {if(it.id==childId)it.copy(status=Attendance.ALIGHTED) else it},
                ledger=arriving.ledger+LedgerEntry(UUID.randomUUID().toString(),childId!!,Attendance.ONBOARD,Attendance.ALIGHTED,System.currentTimeMillis(),"Synthetic completion test"))
            connection.sync(dropped)
            assertEquals("COMPLETED",guardian().getString("journeyStatus"))
            assertTrue(guardian().isNull("location"))
            connection.reportTracking(dropped.copy(phase = TripPhase.ENDED), true, null)
            assertFalse(guardian().getBoolean("available"))
            assertEquals("COMPLETED",guardian().getString("journeyStatus"))
        } finally {
            // Deletes only this test's newly generated preference namespace.
            target.deleteSharedPreferences(prefix + "institution")
        }
    }
}
