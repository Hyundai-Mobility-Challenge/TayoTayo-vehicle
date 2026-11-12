package com.hanhyo.pleosshuttle.sdk

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** No trip/account changes. One public-place native route trial only if initial route is idle. */
class WaypointEtaDeviceTest {
 @Test fun readSavedObservation() {
  val inst=InstrumentationRegistry.getInstrumentation()
  val saved=File(inst.targetContext.getExternalFilesDir(null),"waypoint-eta-observation.json")
  inst.sendStatus(2,android.os.Bundle().apply {putString("observation",saved.readText());putString("observationBase64",android.util.Base64.encodeToString(saved.readBytes(),android.util.Base64.NO_WRAP))})
 }

 @Test fun observeInstalledNativeWaypointEta() {
  val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
  assumeTrue(Build.FINGERPRINT=="Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys" && Build.HARDWARE=="ranchu")
  val report=JSONObject().put("testMeaning","Harness completion is not proof of native ETA; inspect status.")
  val state=AtomicReference(NaviSnapshot());var navi:RealNaviAdapter?=null
  fun main(block:()->Unit)=inst.runOnMainSync(block)
  fun await(ms:Long,test:()->Boolean):Boolean { val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end) { if(test())return true;SystemClock.sleep(100) };return test() }
  fun idle():Boolean { val n=state.get();val d=n.destination.value;return n.routeMode=="SAFE_GUIDING" && n.routeIsRouting==false && d!=null && d.name.isBlank() && d.latitude==0.0 && d.longitude==0.0 }
  var requested=false
  try {
   main { navi=RealNaviAdapter(context,diagnosticsEnabled=false) {state.set(it)};navi!!.trackLocations=true;navi!!.onStart() }
   await(8000) {state.get().routeMode!=null}
   report.put("initialMode",state.get().routeMode).put("initialIdle",idle()).put("terms",state.get().termsAccepted)
   if(InstrumentationRegistry.getArguments().getString("allowWaypointTrial")=="true" && idle() && state.get().termsAccepted==true) {
    requested=true
    val distant=InstrumentationRegistry.getArguments().getString("waypointTrialVariant")=="distant"
    report.put("variant",if(distant)"SEOUL_VIA_POINTS_TO_BUSAN_CITY_HALL" else "SEOUL_VIA_POINTS_TO_SEOUL_FOREST")
    val final=if(distant)NaviDestination("공개 장소 ETA 비교 · 부산시청",35.1796,129.0756) else NaviDestination("가상 민수 · 서울숲",37.5445,127.0374)
    main { navi!!.requestStops(listOf(NaviDestination("가상 지우 · 덕수궁",37.5658,126.9751),NaviDestination("가상 서연 · DDP",37.5665,127.0092),final)) }
    var ready=false
    await(18000) { main { ready=navi!!.trackingNavigation.stops.size==3 };ready }
    main { val nav=navi!!.trackingNavigation
     report.put("diagnostic",JSONObject(navi!!.trackingDiagnostic))
     report.put("nativeWaypoints",JSONArray(navi!!.observedWaypointInfo.map {p -> JSONObject()
      .put("latitude",p.latitude).put("longitude",p.longitude).put("displayLatitude",p.displayLatitude ?: JSONObject.NULL)
      .put("displayLongitude",p.displayLongitude ?: JSONObject.NULL).put("name",p.name).put("seconds",p.remainTime) }))
     report.put("telemetryState",nav.state).put("cumulativeStops",nav.stops.size)
     report.put("seconds",JSONArray(nav.stops.map {it.seconds})).put("adapterOutcome",state.get().outcome.name)
     report.put("message",state.get().message).put("mode",state.get().routeMode)
     report.put("status",if(nav.stops.size==3) "NATIVE_THREE_STOP_ETA_OBSERVED" else "NATIVE_ETA_UNAVAILABLE")
    }
   } else report.put("status","TRIAL_NOT_RUN_EXISTING_ROUTE_OR_TERMS_OR_FLAG")
  } finally {
   if(requested) { main { navi?.cancelOwnRoute();navi?.refresh() };report.put("cancelIdleObserved",await(10000){idle()}) }
   main {navi?.close()}
   File(context.getExternalFilesDir(null),"waypoint-eta-observation.json").writeText(report.toString(2))
  }
 }

 @Test fun nativeEtaReachesIsolatedGuardian() {
  val inst=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
  val base=args.getString("fixtureBase");val driver=args.getString("fixtureVehicle");val guardian=args.getString("fixtureGuardian");val childId=args.getString("fixtureChild")
  assumeTrue(listOf(base,driver,guardian,childId).all {!it.isNullOrBlank()})
  assumeTrue(Build.FINGERPRINT=="Pleos/crp_avd/crp_avd_x86_64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys" && Build.HARDWARE=="ranchu")
  val target=inst.targetContext;val prefix="native-eta-test-${java.util.UUID.randomUUID()}-"
  val context=object:android.content.ContextWrapper(target) {
   override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(prefix+name,mode)
  }
  val state=AtomicReference(NaviSnapshot());var adapter:RealNaviAdapter?=null;var ownsTrial=false
  val evidence=JSONObject().put("source","ACTUAL_PLEOS_AVD_CALLBACKS_WITH_SYNTHETIC_STOPS")
  fun main(block:()->Unit)=inst.runOnMainSync(block)
  fun await(ms:Long,p:()->Boolean):Boolean {val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(p())return true;SystemClock.sleep(100)};return p()}
  fun idle():Boolean {val n=state.get();val d=n.destination.value;return n.routeMode=="SAFE_GUIDING"&&n.routeIsRouting==false&&d!=null&&d.name.isBlank()&&d.latitude==0.0&&d.longitude==0.0}
  try {
   main {adapter=RealNaviAdapter(target,diagnosticsEnabled=false){state.set(it)};adapter!!.trackLocations=true;adapter!!.onStart()}
   await(8000){state.get().routeMode!=null}
   // Recover only this isolated test's known final public-place destination after a prior cleanup timeout.
   val leftover=state.get().destination.value
   if(args.getString("cleanupOwnedFixture")=="true" && leftover?.name=="가상 픽업 · 서울숲 입구" &&
      leftover.latitude==37.5445 && leftover.longitude==127.0374 && adapter!!.observedWaypointInfo.isEmpty()) {
    main { val helper=ai.pleos.playground.navi.helper.NaviHelper(target);helper.initialize();helper.cancelRoute();helper.release();adapter!!.refresh() }
    evidence.put("recoveredPreviousOwnedFixture",await(10000){idle()})
   }
   assumeTrue("Preserve existing navigation or missing terms",idle()&&state.get().termsAccepted==true)
   val prefs=context.getSharedPreferences("institution",0)
   prefs.edit().putString("base",base).putString("token",driver).commit()
   val connection=com.hanhyo.pleosshuttle.InstitutionConnection(context)
   val prepared=connection.createTrip();connection.bind(prepared)
   val own=prepared.children.first {it.id==childId}.stopId
   val order=prepared.stops.filter {it.id!=own}+prepared.stops.first {it.id==own}
   org.junit.Assert.assertEquals(3,order.size)
   val trip=prepared.copy(phase=com.hanhyo.pleosshuttle.domain.TripPhase.DRIVING,routeOrder=order.map {it.id},
    children=prepared.children.map {it.copy(status=com.hanhyo.pleosshuttle.domain.Attendance.ONBOARD)},
    ledger=prepared.children.map {com.hanhyo.pleosshuttle.domain.LedgerEntry(java.util.UUID.randomUUID().toString(),it.id,
     com.hanhyo.pleosshuttle.domain.Attendance.NOT_BOARDED,com.hanhyo.pleosshuttle.domain.Attendance.ONBOARD,System.currentTimeMillis(),"Isolated native ETA proof")})
   connection.sync(trip)
   ownsTrial=true;main {adapter!!.requestStops(order.map {NaviDestination(it.name,it.latitude,it.longitude)})}
   var native:com.hanhyo.pleosshuttle.TrackingNavigation?=null
   org.junit.Assert.assertTrue("Actual native named ETA must arrive",await(18000){main{native=adapter!!.trackingNavigation};native!!.state=="READY"&&native!!.stops.size==3})
   var fix:com.hanhyo.pleosshuttle.TrackingLocation?=null
   main {native=adapter!!.trackingNavigation;fix=adapter!!.trackingLocation}
   org.junit.Assert.assertNotNull(fix)
   connection.reportTracking(trip,true,fix,native)
   val view=com.hanhyo.shuttle.net.Api.request(base!!,"/api/guardian/tracking",guardian!!,JSONObject().put("childId",childId))
   val eta=view.getJSONObject("eta")
   org.junit.Assert.assertEquals("PLEOS_REGISTERED_STOP_ESTIMATE",eta.getString("kind"))
   val expected=(native!!.stops.last().seconds+59)/60
   org.junit.Assert.assertEquals(expected,eta.getInt("minutes"))
   org.junit.Assert.assertEquals(3,view.getJSONArray("progress").length())
   org.junit.Assert.assertFalse(view.toString().contains("observedName"))
   evidence.put("nativeSeconds",JSONArray(native!!.stops.map {it.seconds}))
    .put("coordinateSources",JSONArray(native!!.stops.map {it.coordinateSource}))
    .put("guardianMinutes",eta.getInt("minutes")).put("guardianKind",eta.getString("kind"))
    .put("nativeMode",state.get().routeMode).put("status","PASSED_NATIVE_TO_GUARDIAN")
   val transitions=JSONArray()
   for(index in 1..2) {
    main {adapter!!.requestStops(order.drop(index).map {NaviDestination(it.name,it.latitude,it.longitude)})}
    org.junit.Assert.assertTrue("Remaining native stops must refresh",await(18000){main{native=adapter!!.trackingNavigation};native!!.state=="READY"&&native!!.stops.size==3-index})
    main{native=adapter!!.trackingNavigation;fix=adapter!!.trackingLocation}
    connection.reportTracking(trip.copy(currentStopIndex=index),true,fix,native)
    val next=com.hanhyo.shuttle.net.Api.request(base,"/api/guardian/tracking",guardian,JSONObject().put("childId",childId))
    val nextEta=next.getJSONObject("eta")
    org.junit.Assert.assertEquals(2-index,next.getInt("remainingStops"))
    org.junit.Assert.assertEquals((native!!.stops.last().seconds+59)/60,nextEta.getInt("minutes"))
    org.junit.Assert.assertEquals(if(index==2)"PLEOS_WAYPOINT_ESTIMATE" else "PLEOS_REGISTERED_STOP_ESTIMATE",nextEta.getString("kind"))
    transitions.put(JSONObject().put("currentStopIndex",index).put("nativeSeconds",JSONArray(native!!.stops.map {it.seconds})).put("guardianMinutes",nextEta.getInt("minutes")))
   }
   evidence.put("remainingRouteTransitions",transitions)

  } finally {
   if(ownsTrial){
    main{adapter?.cancelOwnRoute();adapter?.refresh()}
    var cancelled=await(5000){idle()}
    val final=state.get().destination.value
    if(!cancelled && final?.name=="가상 픽업 · 서울숲 입구" && final.latitude==37.5445 && final.longitude==127.0374 && adapter!!.observedWaypointInfo.isEmpty()) {
     main { val helper=ai.pleos.playground.navi.helper.NaviHelper(target);helper.initialize();helper.cancelRoute();helper.release();adapter!!.refresh() }
     cancelled=await(10000){idle()}
    }
    evidence.put("cancelIdleObserved",cancelled)
   }
   main{adapter?.close()};target.deleteSharedPreferences(prefix+"institution")
   inst.sendStatus(2,android.os.Bundle().apply{putString("nativeProof",evidence.toString())})
  }
 }
}
