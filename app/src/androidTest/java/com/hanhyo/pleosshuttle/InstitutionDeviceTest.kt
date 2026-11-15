package com.hanhyo.pleosshuttle

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.shuttle.net.Api
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.TripPhase
import org.json.JSONObject
import org.junit.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Opt-in integration test against an explicitly supplied local demo server. */
@RunWith(AndroidJUnit4::class)
class InstitutionDeviceTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedTripRule()).around(compose)

    @Test fun paired_roster_uses_existing_handover_flow_and_reaches_guardian() {
        val base = InstrumentationRegistry.getArguments().getString("serverBase")
        Assume.assumeTrue("Pass -e serverBase for local integration testing", !base.isNullOrEmpty())
        val server = base!!
        fun post(path: String, token: String = "", data: JSONObject = JSONObject()) = Api.request(server, "/api/$path", token, data)
        val admin = post("login", data = JSONObject().put("email", "admin@demo.example").put("password", "ShuttleDemo123!")).getString("token")
        val state = Api.request(server, "/api/admin/state", admin)
        val vehicle = state.getJSONArray("vehicles").getJSONObject(0).getString("id")
        val childId = state.getJSONArray("children").getJSONObject(0).getString("id")
        val code = post("admin/pair-code", admin, JSONObject().put("vehicleId", vehicle)).getString("code")
        val parent = post("login", data = JSONObject().put("email", "parent@demo.example").put("password", "ShuttleDemo123!")).getString("token")
        val invitation = post("admin/invite", admin, JSONObject().put("childId", childId).put("email", "parent@demo.example")).getString("code")
        post("guardian/accept", parent, JSONObject().put("code", invitation))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("institution", 0)
        val backup = prefs.all.toMap()
        val archive = File(context.filesDir, "completed-trips")
        val oldArchives = archive.listFiles().orEmpty().map { it.name }.toSet()
        var activity: ActivityScenario<MainActivity>? = null
        try {
            prefs.edit().clear().commit()
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: ShuttleViewModel
            activity.onActivity { vm = it.model; vm.panel = "institution" }
            compose.onNodeWithTag("institution-server").performScrollTo().performTextReplacement(server)
            compose.onNodeWithTag("institution-code").performScrollTo().performTextReplacement(code)
            closeSoftKeyboard()
            compose.onNodeWithTag("institution-pair").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.institutionConnected && !vm.institutionBusy }
            compose.onNodeWithTag("institution-load").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.trip.tripId.startsWith("remote-") && !vm.institutionBusy }
            Assert.assertEquals(childId, vm.trip.children.single().id)
            Assert.assertEquals(com.hanhyo.pleosshuttle.domain.IntegrationMode.SDK, vm.trip.integrationMode)
            // 출시 앱에는 모의 차량 입력이 없다. Pleos 에뮬레이터 Virtual Console에서 P 기어·정차가 관측되어야 진행된다.
            compose.waitUntil(15000) { vm.engine.stationaryBlockers(vm.now).isEmpty() }
            compose.runOnIdle { vm.perform { now -> board(childId, now) } }
            Assert.assertEquals(Attendance.ONBOARD, vm.trip.children.single().status)
            compose.runOnIdle { vm.perform { now -> startOperationalTrip(now) } }
            Assert.assertEquals(TripPhase.DRIVING, vm.trip.phase)
            compose.runOnIdle { vm.perform { now -> chooseAlighting(now) } }
            // Ensure server integration has not bypassed the human handover guard.
            compose.runOnIdle { vm.perform { now -> recordAlightingAndContinue(childId, now) } }
            Assert.assertEquals(Attendance.ONBOARD, vm.trip.children.single().status)
            compose.runOnIdle {
                vm.perform { now -> confirmHandover(childId, now) }
                vm.perform { now -> recordAlightingAndContinue(childId, now) }
            }
            Assert.assertEquals(Attendance.ALIGHTED, vm.trip.children.single().status)
            compose.waitUntil(20000) { InstitutionConnection(context).pending(vm.trip) == 0 }
            val remote = Api.request(server, "/api/guardian/state", parent).getJSONArray("events")
            Assert.assertTrue((0 until remote.length()).any { remote.getJSONObject(it).getString("body").contains("하차했습니다") })
            Assert.assertTrue(TripStore(context).load()!!.ledger.any { it.after == Attendance.ALIGHTED })
        } finally {
            activity?.close()
            val editor = prefs.edit().clear()
            backup.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            archive.listFiles().orEmpty().filter { it.name !in oldArchives }.forEach { it.delete() }
        }
    }
}
