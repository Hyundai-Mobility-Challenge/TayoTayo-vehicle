package com.hanhyo.pleosshuttle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume
/** Opt-in read-only readiness diagnostic. No credentials, names, locations or trip IDs are emitted. */
class TrackingReadinessDiagnosticTest {
 @Test fun currentReadiness() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("readinessOnly") == "true")
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val t=TripStore(c).load()
  val i=InstitutionConnection(c)
  val enabled=c.getSharedPreferences("simulation-settings",0).getBoolean("trackingEnabled",false)
  val recoverable=t!=null && canArchiveLocalTrackingDemo(BuildConfig.DEBUG,i.connected,t.tripId==i.boundTrip,t.integrationMode,t.tripId,true,false)
  val status="TRACKING_READINESS connected=${i.connected} bound=${t?.tripId==i.boundTrip} phase=${t?.phase} mode=${t?.integrationMode} onboard=${t?.onboardChildren?.size} hasStop=${t?.currentStop!=null} enabled=$enabled archiveLocalDemoAllowed=$recoverable"
  InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("readiness",status) })
 }
}
