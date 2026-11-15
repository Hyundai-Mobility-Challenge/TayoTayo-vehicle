package com.hanhyo.pleosshuttle.sdk

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Default is read-only. Each optional flag authorizes at most one corresponding trial. */
@RunWith(AndroidJUnit4::class)
class SdkRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.filesDir, "sdk-runtime-observation.json")

    @Test fun `실제_SDK_관측과_재구독_시험_결과를_상태별로_보존한다`() {
        val args = InstrumentationRegistry.getArguments()
        val navigationAllowed = args.getString("allowNavigationTrial") == "true"
        val report = JSONObject().put("startedAtMillis", System.currentTimeMillis())
            .put("identity", JSONObject().put("fingerprint", Build.FINGERPRINT).put("hardware", Build.HARDWARE).put("product", Build.PRODUCT).put("api", Build.VERSION.SDK_INT))
            .put("allowNavigationTrial", navigationAllowed)
            .put("permissionsAtStart", permissions())
            .put("harnessMeaning", "Harness completion is not proof of SDK feature success. Per-feature status and observations below are authoritative.")
            .put("navigationTrial", JSONObject().put("status", "NOT_AUTHORIZED_NOT_RUN"))
            .put("unlockTrial", JSONObject().put("status", "REMOVED_APP_HAS_NO_DOOR_CONTROL"))
        val exactAvd = Build.FINGERPRINT == VERIFIED_FINGERPRINT && Build.HARDWARE == "ranchu" && Build.PRODUCT == "crp_avd_arm64"
        if (!exactAvd) {
            report.put("harness", "SKIPPED_NOT_VERIFIED_PLEOS_AVD")
            save(report)
            assumeTrue("Exact verified Pleos AVD required; evidence saved without SDK calls", false)
        }
        val vehicleState = AtomicReference(VehicleSnapshot())
        val naviState = AtomicReference(NaviSnapshot())
        val vehicleCallbacks = AtomicInteger()
        val naviCallbacks = AtomicInteger()
        val vehicleLog = CopyOnWriteArrayList<String>()
        val naviLog = CopyOnWriteArrayList<String>()
        var vehicle: RealVehicleAdapter? = null
        var navi: RealNaviAdapter? = null
        val sessions = JSONArray()
        try {
            onMain {
                vehicle = RealVehicleAdapter(context) { state ->
                    vehicleState.set(state); vehicleCallbacks.incrementAndGet()
                    state.diagnostics.forEach { if (!vehicleLog.contains(it)) vehicleLog.add(it) }
                }
                navi = RealNaviAdapter(context) { state ->
                    naviState.set(state); naviCallbacks.incrementAndGet()
                    state.diagnostics.forEach { if (!naviLog.contains(it)) naviLog.add(it) }
                }
            }
            repeat(2) { sessionIndex ->
                val started = System.currentTimeMillis()
                onMain {
                    vehicle!!.onStart(); vehicle!!.onStart() // Duplicate lifecycle entry must be idempotent.
                    navi!!.onStart(); navi!!.onStart()
                }
                waitUntil(10_000) {
                    val v = vehicleState.get(); val n = naviState.get()
                    v.speedMetersPerSecond.receivedAtMillis >= started && v.gear.receivedAtMillis >= started &&
                        (v.supportedBeltAreas.isNotEmpty() || v.diagnostics.any { it.contains("capability ERROR") }) &&
                        n.destination.observedAtMillis?.let { it >= started } == true && n.routeState.observedAtMillis?.let { it >= started } == true
                }
                val session = JSONObject().put("index", sessionIndex + 1).put("startedAtMillis", started)
                    .put("vehicle", vehicleState.get().json()).put("navigation", naviState.get().json())
                sessions.put(session); report.put("sessions", sessions); save(report)
                if (sessionIndex == 1) {
                    if (navigationAllowed) runNavigationTrial(navi!!, naviState, naviLog, report)
                }
                onMain { vehicle!!.onStop(); vehicle!!.onStop(); navi!!.onStop(); navi!!.onStop() }
                val stoppedVehicleCount = vehicleCallbacks.get(); val stoppedNaviCount = naviCallbacks.get()
                CountDownLatch(1).await(350, TimeUnit.MILLISECONDS)
                session.put("callbacksAfterStop", JSONObject().put("vehicle", vehicleCallbacks.get() - stoppedVehicleCount).put("navigation", naviCallbacks.get() - stoppedNaviCount))
                session.put("afterStopVehicle", vehicleState.get().json()).put("afterStopNavigation", naviState.get().json())
                assertEquals("Late old-session vehicle callback must not publish", stoppedVehicleCount, vehicleCallbacks.get())
                assertEquals("Late old-session navi callback must not publish", stoppedNaviCount, naviCallbacks.get())
            }
            val registrationPattern = Regex("(?<!UN)SUBSCRIBE epoch=(\\d+) key=(\\S+)")
            val registrations = (vehicleLog + naviLog).mapNotNull { line -> registrationPattern.find(line)?.groupValues?.let { "${it[1]}:${it[2]}" } }
            val duplicates = registrations.groupingBy { it }.eachCount().filterValues { it > 1 }
            val cleanupErrors = (vehicleLog + naviLog).filter { it.contains("cleanup ERROR") || it.contains("release ERROR") || it.contains("disconnect ERROR") || it.contains("app call failed=true") || (it.contains("UNSUBSCRIBE") && Regex("failures=[1-9]").containsMatchIn(it)) }
            report.put("listenerAssessment", JSONObject().put("registrationKeys", JSONArray(registrations)).put("duplicates", JSONObject(duplicates)).put("cleanupErrors", JSONArray(cleanupErrors))
                .put("boundary", "App-side registration/cleanup calls and stale-callback fencing only; remote service subscriber count is not observable."))
            assertTrue("Duplicate app-side registration key found: $duplicates", duplicates.isEmpty())
            assertTrue("Listener cleanup error observed: $cleanupErrors", cleanupErrors.isEmpty())
            report.put("harness", "COMPLETED_OBSERVATIONS_SAVED")
        } catch (failure: Throwable) {
            report.put("harness", "FAILED").put("harnessError", failure.toString())
            throw failure
        } finally {
            onMain { vehicle?.close(); navi?.close() }
            report.put("finishedAtMillis", System.currentTimeMillis()).put("sessions", sessions)
                .put("permissionsAtFinish", permissions())
                .put("vehicleLog", JSONArray(vehicleLog)).put("navigationLog", JSONArray(naviLog))
                .put("vehicleCallbackCount", vehicleCallbacks.get()).put("navigationCallbackCount", naviCallbacks.get())
            save(report)
        }
    }

    private fun runNavigationTrial(adapter: RealNaviAdapter, state: AtomicReference<NaviSnapshot>, log: List<String>, report: JSONObject) {
        val trial = JSONObject().put("before", state.get().json()).put("nativeScreenVerified", false)
        report.put("navigationTrial", trial); save(report)
        val before = state.get()
        if (!routeEmpty(before) || before.termsAccepted != true) {
            trial.put("status", "BLOCKED_INITIAL_ROUTE_NOT_CONFIRMED_EMPTY_OR_TERMS_UNKNOWN")
            save(report); return
        }
        val name = "서울시청 통학 PoC 시험"
        val started = System.currentTimeMillis()
        try {
            onMain { adapter.requestNextStop(name, 37.5665, 126.9780) }
            trial.put("requestCount", 1).put("requestedAtMillis", started).put("name", name).put("latitude", 37.5665).put("longitude", 126.9780)
            waitUntil(16_000) { !state.get().busy }
            onMain { adapter.refresh() }
            waitUntil(3_000) { state.get().destination.observedAtMillis?.let { it >= started } == true && state.get().routeState.observedAtMillis?.let { it >= started } == true }
            val after = state.get()
            trial.put("after", after.json())
            val actual = after.destination.value
            val matches = actual != null && actual.name == name && abs(actual.latitude - 37.5665) < 0.00001 && abs(actual.longitude - 126.9780) < 0.00001 && after.routeMode in setOf("ROUTE_GUIDING", "SIMULATION_GUIDING")
            if (after.outcome == SdkOutcome.TARGET_OBSERVED) {
                assertTrue("TARGET_OBSERVED requires exact requested name, coordinates, and guidance mode", matches)
                trial.put("status", "SDK_TARGET_OBSERVED_NATIVE_SCREEN_UNVERIFIED")
            } else trial.put("status", "PARTIAL_OR_UNVERIFIED").put("sdkStatus", after.outcome.name)
            onMain { adapter.showNavigation() }
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stage", "NATIVE_NAV_SCREEN_READY") })
            // Bounded window for an external adb screenshot, independent of UiAutomation ownership.
            CountDownLatch(1).await(8, TimeUnit.SECONDS)
            trial.put("screenBoundary", "Native pixels are captured by the host through adb; this instrumentation does not claim UiAutomation.")
        } finally {
            val cancelAt = System.currentTimeMillis()
            onMain { adapter.cancelOwnRoute(); adapter.refresh() }
            val restoredEmpty = waitUntil(8_000) { routeEmpty(state.get()) && state.get().destination.observedAtMillis?.let { it >= cancelAt } == true && state.get().routeState.observedAtMillis?.let { it >= cancelAt } == true }
            trial.put("cancel", JSONObject().put("sentAtMillis", cancelAt).put("emptyRouteObserved", restoredEmpty).put("routeCancelledEventObserved", log.any { it.contains("RouteCancelled callback observed") }).put("after", state.get().json())
                .put("status", if (restoredEmpty) "EMPTY_ROUTE_STATE_OBSERVED" else "CANCELLATION_UNCONFIRMED_NO_RETRY"))
            save(report)
        }
    }

    private fun routeEmpty(s: NaviSnapshot): Boolean = s.destination.isFresh(maxAgeMillis = 6_000) && s.routeState.isFresh(maxAgeMillis = 6_000) && s.destination.value?.name?.isBlank() == true && s.routeMode == "SAFE_GUIDING" && s.routeIsRouting == false
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
        }
        return condition()
    }
    private fun save(report: JSONObject) { evidence.writeText(report.toString(2)) }
    private fun permissions() = JSONObject().also { result ->
        listOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN", "pleos.car.permission.CAR_DOORS", "pleos.car.permission.CAR_SEATS", "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH", "pleos.car.permission.NAVI_CUSTOM_MAP").forEach { permission ->
            result.put(permission, if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED")
        }
    }
    private fun SdkObservation<*>.json(): JSONObject = JSONObject().put("value", when (val v = value) {
        null -> JSONObject.NULL
        is NaviDestination -> JSONObject().put("name", v.name).put("latitude", if (v.latitude.isFinite()) v.latitude else v.latitude.toString()).put("longitude", if (v.longitude.isFinite()) v.longitude else v.longitude.toString())
        is Float -> if (v.isFinite()) v else v.toString()
        is Double -> if (v.isFinite()) v else v.toString()
        else -> v
    }).put("source", source).put("outcome", outcome.name).put("observedAtMillis", observedAtMillis ?: JSONObject.NULL).put("receivedAtMillis", receivedAtMillis).put("sourceTimestampElapsedNanos", sourceTimestampElapsedNanos ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)
    private fun VehicleSnapshot.json() = JSONObject().put("versions", JSONObject(versions)).put("connected", connected)
        .put("speed", speedMetersPerSecond.json()).put("gear", gear.json()).put("parkingBrake", parkingBrake.json()).put("parkingBrakeSupported", parkingBrakeSupported).put("doorLocked", doorLocked.json())
        .put("uxRestricted", uxRestricted.json()).put("activeUxRestrictions", activeUxRestrictions ?: JSONObject.NULL)
        .put("supportedDoorAreas", JSONArray(supportedDoorAreas)).put("supportedBeltAreas", JSONArray(supportedBeltAreas))
        .put("selectedDoorArea", selectedDoorArea ?: JSONObject.NULL)
        .put("seatBelts", JSONObject().also { json -> seatBelts.forEach { (area, observation) -> json.put(area, observation.json()) } })
    private fun NaviSnapshot.json() = JSONObject().put("versions", JSONObject(versions)).put("destination", destination.json()).put("routeState", routeState.json()).put("routeMode", routeMode ?: JSONObject.NULL).put("routeIsRouting", routeIsRouting ?: JSONObject.NULL).put("termsAccepted", termsAccepted ?: JSONObject.NULL)
        .put("navigationStatus", navigationStatus).put("outcome", outcome.name).put("message", message).put("busy", busy).put("initialSnapshot", initialSnapshot ?: JSONObject.NULL)
    companion object {
        private const val VERIFIED_FINGERPRINT = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
    }
}
