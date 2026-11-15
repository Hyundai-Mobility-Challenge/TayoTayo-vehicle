package com.hanhyo.pleosshuttle

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.RouteObjective
import com.hanhyo.pleosshuttle.domain.Source
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 기관(SDK) 운행 화면 흐름. 운행 파일에 [DeviceTrips.sdkTrip]을 저장해 두고 앱을 켠다(기관 서버에는 묶이지 않음 → 동기화·위치 전송 없음).
 *
 * 공통 실행 전제:
 * - Automotive 에뮬레이터(검증 기준: Pleos Connect AVD)에 이 앱 디버그 빌드가 설치되어 있고 CAR_SPEED 권한이 허용되어 있다.
 * - 차량 관측은 [VhalInput]이 VHAL 저장값을 바꿔 만든다(fake VHAL `dumpsys … --set` 우선, 안 되면 inject-vhal-event). 1초마다 다시 넣는다.
 * - 입력이 실제로 앱에 닿는지 매번 확인한다: 시작할 때 먼저 D 기어 · 5 m/s를 넣고 앱의 SDK 관측(vm.sdkVehicle)이 D · 이동이 될 때까지
 *   기다린 뒤 P · 0으로 바꾼다. AVD 기본값이 이미 P · 0이어서 입력 없이 통과하는 경우를 막기 위해서다.
 *   명령이 모두 거부되거나(user 빌드 등) 15초 안에 앱이 입력값을 읽지 못하면 테스트는 실패 대신 건너뛴다(assume).
 * - 자동 길 안내는 실제 Pleos 내비에 경유지를 요청할 수 있다. 끝나면 이 앱이 만든 경로를 취소한다.
 */
@RunWith(AndroidJUnit4::class)
class SdkTripFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: ShuttleViewModel
    private val checkpointBackup = linkedMapOf<String, ByteArray?>()
    private val routePreferences get() = context.getSharedPreferences("route-settings", 0)
    private var routePreferencesBackup: Map<String, *> = emptyMap<String, Any>()
    private var isolationStarted = false

    @Before fun 운행_파일을_격리하고_기관_운행을_저장한다() {
        check(context.packageName == "com.hanhyo.pleosshuttle")
        assumeTrue("Automotive 에뮬레이터 필요", context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        routePreferencesBackup = routePreferences.all.toMap()
        isolationStarted = true
        check(routePreferences.edit().clear().commit())
        listOf("trip-v1.bin", "trip-v1.bin.bak", "trip-v1.bin.new").forEach { name ->
            val file = File(context.filesDir, name)
            checkpointBackup[name] = if (file.exists()) file.readBytes() else null
            check(!file.exists() || file.delete()) { "Cannot isolate test checkpoint: $name" }
        }
    }

    @After fun 원래_운행_파일과_차량_입력을_복원한다() {
        if (!isolationStarted) return
        try {
            runCatching { compose.runOnIdle { model.cancelOwnNavigation(); model.audio.stopSpeech() } }
            scenario?.close()
            scenario = null
            instrumentation.waitForIdleSync()
        } finally {
            // 다음 기기 테스트가 이동 상태로 시작하지 않도록 정차 값을 한 번 넣고 멈춘다.
            runCatching { VhalInput.park() }
            VhalInput.release()
            checkpointBackup.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes == null) check(!file.exists() || file.delete()) else file.writeBytes(bytes)
            }
            val routeEditor = routePreferences.edit().clear()
            routePreferencesBackup.forEach { (key, value) -> if (value is Boolean) routeEditor.putBoolean(key, value) }
            check(routeEditor.commit())
        }
    }

    /**
     * (옛 148) 상세 조작 화면으로 탑승 → 순서 계산·적용 → 출발 → 정류장마다 정차·인계·하차·복귀 → 복귀·실내 확인 → 종료 저장.
     * 전제: 공통 전제(정차 관측). 이동 관측은 쓰지 않는다(정차 상태로 단계만 넘긴다; 잠깐 멈춤으로 하차 단계가 되지 않는 것은 엔진 단위 테스트).
     */
    @Test fun `탑승부터_인계와_하차와_실내확인을_거쳐_하원_종료기록을_저장한다`() {
        start(DeviceTrips.sdkTrip("device-flow-1"), detailed = true)
        val checkpoints = JSONArray()
        val childIds = state().children.map { it.id }
        childIds.forEach { press("board-$it") }
        assertEquals(4, state().onboardChildren.size)
        assertEquals(4, state().ledger.size)
        compose.onNodeWithTag("board-${childIds.first()}").assertIsNotEnabled()

        press("calculate-route")
        assertNotNull(state().routeProposal)
        assertEquals(6, state().routeProposal!!.evaluatedOrders)
        val proposed = state().routeProposal!!.stopOrder
        press("apply-route")
        assertEquals(proposed, state().routeOrder)
        assertNull(state().routeProposal)
        checkpoints.put(record("prepared", state()))
        press("start-driving")

        repeat(proposed.size) {
            assertEquals(TripPhase.DRIVING, state().phase)
            compose.onNodeWithTag("navigate-next").performScrollTo().assertIsEnabled()
            press("choose-alighting")
            assertEquals(TripPhase.ALIGHTING, state().phase)
            val stop = state().currentStop!!
            state().currentStopChildren.forEach { child ->
                compose.onNodeWithTag("alight-${child.id}").performScrollTo().assertIsNotEnabled()
                press("handover-${child.id}")
                assertTrue(child.id in state().handoverChildIds)
                press("alight-${child.id}")
                assertEquals(Attendance.ALIGHTED, state().children.first { it.id == child.id }.status)
            }
            checkpoints.put(record("alighted-${stop.id}", state()))
            compose.onNodeWithTag("resume-reason").performScrollTo().performTextReplacement("정류장 기록 확인 후 운행 화면으로 복귀")
            closeSoftKeyboard()
            press("resume-driving")
            assertEquals(TripPhase.DRIVING, state().phase)
        }

        assertNull(state().currentStop)
        assertTrue(state().onboardChildren.isEmpty())
        press("begin-end")
        assertEquals(TripPhase.END_REVIEW, state().phase)
        compose.onNodeWithTag("finish-trip").performScrollTo().assertIsNotEnabled()
        press("confirm-cabin")
        press("finish-trip")
        assertEquals(TripPhase.ENDED, state().phase)
        assertEquals(8 + 1, state().ledger.size) // 탑승 4 + 하차 4 + 출발 때 "기사 확인 없음" 1(V-08)
        assertTrue(state().children.all { it.status == Attendance.ALIGHTED })
        assertNotNull(state().endRecord)
        assertTrue(state().endRecord!!.statement.contains("안전 보증이 아님"))
        assertEquals(TripPhase.ENDED, TripStore(context).load()?.phase)
        assertEquals(IntegrationMode.SDK, state().integrationMode)
        checkpoints.put(record("ended", state()))
        File(context.filesDir, "sdk-flow-device-evidence.json").writeText(JSONObject()
            .put("scope", "Compose UI with an institution(SDK) trip; vehicle observations from VHAL input; no door control")
            .put("checkpoints", checkpoints).toString(2))
    }

    /**
     * (옛 448) 기본 운행 화면(흐름 카드)만으로: 탑승 → [이 순서로 · 운행 시작] → 정류장마다 도착·정차 → 인계 → 하차 → 기관 복귀 → 실내 확인 · 종료.
     * 전제: 공통 전제 + Pleos 기기 내 TTS(ON_DEVICE)가 동작해야 첫 출발 안내 음성이 COMPLETED가 된다.
     */
    @Test fun `자동_음성과_같은_화면으로_인계와_하차를_기록하고_운행을_종료한다`() {
        start(DeviceTrips.sdkTrip("device-guided-1", routeIsExample = true), detailed = false)
        compose.onNodeWithTag("flow-guide").assertIsDisplayed()
        compose.onNodeWithTag("voice-toolbar").assertIsDisplayed()
        compose.onNodeWithTag(NO_TRIP_LOADED_TAG).assertDoesNotExist()
        state().children.forEach { visiblePress("board-${it.id}") }
        compose.onNodeWithTag("start-trip").performScrollTo()
        visiblePress("start-trip")
        assertEquals(TripPhase.DRIVING, state().phase)
        compose.waitUntil(20_000) { model.audioState.speechStatus == com.hanhyo.pleosshuttle.audio.SpeechStatus.COMPLETED }
        assertEquals(1, compose.runOnIdle { model.automaticAnnouncementKeys.size })
        // 백그라운드 복귀가 같은 목적지 안내를 반복하지 않는다.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, compose.runOnIdle { model.automaticAnnouncementKeys.size })
        awaitStationary()
        repeat(state().routeOrder.size) { index ->
            visiblePress("arrive-stop")
            listOf("prepare-door", "confirm-surroundings", "reason", "resume-reason", "tab-diagnostics").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            state().currentStopChildren.filter { it.status == Attendance.ONBOARD }.forEach { child ->
                compose.onNodeWithTag("alight-${child.id}").assertDoesNotExist()
                visiblePress("handover-${child.id}")
                visiblePress("alight-${child.id}")
            }
            assertEquals(TripPhase.DRIVING, state().phase)
            assertEquals(index + 1, state().currentStopIndex)
        }
        assertNull(state().currentStop)
        assertTrue(state().onboardChildren.isEmpty())
        visiblePress("arrive-origin")
        visiblePress("confirm-finish")
        assertEquals(TripPhase.ENDED, state().phase)
        assertTrue(state().children.all { it.status == Attendance.ALIGHTED })
        assertEquals(TripPhase.ENDED, TripStore(context).load()?.phase)
        val cues = compose.runOnIdle { model.automaticAnnouncementKeys.toList() }
        assertEquals(cues.size, cues.distinct().size)
    }

    /**
     * (옛 571) 경로 확인에서 순서 바꾸기 → 고정 순서로 출발, 정차 중에는 지금 정류장을 고정하고 남은 순서만 바꾼다.
     * 전제: 공통 전제. 경로 기준 설정(route-settings)은 테스트마다 처음 상태로 시작한다.
     */
    @Test fun `경로_확인에서_순서를_바꾸면_고정_순서로_출발하고_정차_중에는_남은_순서만_바꾼다`() {
        start(DeviceTrips.sdkTrip("device-route-1", routeIsExample = true), detailed = false)
        state().children.forEach { visiblePress("board-${it.id}") }
        compose.onNodeWithTag("route-strip").assertIsDisplayed()
        compose.onNodeWithTag("route-criteria-list").assertIsDisplayed()
        compose.onNodeWithTag("start-trip").assertExists()
        assertTrue(compose.runOnIdle { model.routeCriteriaSeen })
        visiblePress("open-reorder")
        compose.onNodeWithTag("reorder").assertIsDisplayed()
        compose.onNodeWithTag("move-up-forest").assertIsNotEnabled()
        visiblePress("move-up-park")
        visiblePress("reorder-reason-보호자 요청")
        compose.onNodeWithTag("reorder-apply").performScrollTo()
        visiblePress("reorder-apply")
        assertEquals(listOf("park", "forest", "museum"), state().routeOrder)
        assertEquals(RouteObjective.FIXED, state().routeObjective)
        assertFalse(state().routeIsExample)
        assertTrue(state().diagnostics.last { it.feature == "방문 순서 변경" }.message.contains("보호자 요청"))
        compose.onNodeWithTag("reorder").assertDoesNotExist()
        compose.onNodeWithTag("start-trip").performScrollTo()
        visiblePress("start-trip")
        assertEquals(listOf("park", "forest", "museum"), state().routeOrder)
        awaitStationary()
        visiblePress("arrive-stop")
        compose.onNodeWithTag("open-reorder").performScrollTo()
        visiblePress("open-reorder")
        compose.onNodeWithTag("move-up-park").assertDoesNotExist()
        compose.onNodeWithTag("move-up-forest").assertIsNotEnabled()
        visiblePress("move-up-museum")
        compose.onNodeWithTag("reorder-apply").performScrollTo()
        visiblePress("reorder-apply")
        assertEquals(listOf("park", "museum", "forest"), state().routeOrder)
        assertEquals(TripPhase.ALIGHTING, state().phase)
        assertEquals("park", state().currentStop?.id)
        state().currentStopChildren.filter { it.status == Attendance.ONBOARD }.forEach { child ->
            visiblePress("handover-${child.id}")
            visiblePress("alight-${child.id}")
        }
        assertEquals(TripPhase.DRIVING, state().phase)
        assertEquals("museum", state().currentStop?.id)
    }

    /**
     * (옛 299) 앱을 다시 열면 미완료 운행 기록(정차 · 하차 단계)을 그대로 이어 쓰고, 차량 관측은 새로 받아야 한다.
     * 전제: 공통 전제. 프로세스 종료는 하지 않고 Activity를 닫은 뒤 새 ViewModel과 새 Activity로 복원을 확인한다
     * (실제 프로세스 종료 복원은 ProcessRestoreDeviceTest, 호스트가 stage=prepare/verify로 실행).
     */
    @Test fun `앱을_다시_열면_미완료_기록을_보존하고_차량_관측은_새로_받는다`() {
        start(DeviceTrips.sdkTrip("device-restore-2"), detailed = false)
        visiblePress("board-child-a")
        compose.runOnIdle { model.perform { startDriving(it) } }
        assertEquals(TripPhase.DRIVING, state().phase)
        awaitStationary()
        compose.runOnIdle { model.perform { chooseAlighting(it) } }
        val before = state()
        assertEquals(TripPhase.ALIGHTING, before.phase)
        assertEquals(before.ledger, TripStore(context).load()?.ledger)
        scenario!!.close(); scenario = null
        instrumentation.runOnMainSync {
            val restoredBeforeForeground = ShuttleViewModel(context.applicationContext as Application)
            assertTrue(restoredBeforeForeground.trip.hasTrip)
            assertEquals(TripPhase.ALIGHTING, restoredBeforeForeground.trip.phase)
            assertEquals(before.children, restoredBeforeForeground.trip.children)
            assertEquals(before.ledger, restoredBeforeForeground.trip.ledger)
            assertTrue(restoredBeforeForeground.trip.handoverChildIds.isEmpty())
            // 복원 직후(포그라운드 전)에는 차량 관측이 없다. 이전 관측을 이어 쓰지 않는다.
            assertFalse(restoredBeforeForeground.vehicle.connected)
            assertNull(restoredBeforeForeground.vehicle.speedKph.value)
            restoredBeforeForeground.audio.close()
        }
        launch()
        assertEquals(TripPhase.ALIGHTING, state().phase)
        assertEquals(before.ledger, state().ledger)
        assertEquals(Attendance.ONBOARD, state().children.first { it.id == "child-a" }.status)
        awaitStationary()
        visiblePress("handover-child-a")
        visiblePress("alight-child-a")
        assertEquals(Attendance.ALIGHTED, state().children.first { it.id == "child-a" }.status)
    }

    /**
     * (옛 359) 새 Activity에서도 이동 관측(D · 5 m/s)을 정차로 바꾸지 않는다. 기록은 그대로이고 도착·정차 확인은 막힌다.
     * 전제: 공통 전제 + VHAL에 D 기어 · 속도 입력이 가능해야 한다. 새 Activity가 이동 값을 읽지 못하면 실패 대신 건너뛴다(assume).
     * 운행 중 D · 이동이면 앱이 뒤로 갈 수 있어(주행 집중) 화면 노드가 아니라 ViewModel 상태로 확인한다. 센서 연결 끊김·갱신 중단 자체는 VHAL로 재현할 수 없어
     * 복원 직후 "관측 없음" 확인(위 테스트)과 엔진 단위 테스트(ShuttleEngineTest 오래된 속도)로 대신한다.
     */
    @Test fun `새_Activity에서도_이동_관측을_정차로_바꾸지_않는다`() {
        start(DeviceTrips.sdkTrip("device-moving-1"), detailed = false)
        visiblePress("board-child-a")
        compose.runOnIdle { model.perform { startDriving(it) } }
        assertEquals(TripPhase.DRIVING, state().phase)
        val ledger = state().ledger
        scenario!!.close(); scenario = null
        VhalInput.drive(5f)
        launch()
        // 운행 중(DRIVING)에 D · 이동이면 주행 집중 규칙이 앱을 뒤로 보낼 수 있다. 그래서 화면 노드 대신 ViewModel 상태로만 확인한다.
        awaitVehicle(VhalInput.GEAR_DRIVE, moving = true, what = "새 Activity의 이동 관측")
        compose.waitUntil(5_000) { model.vehicle.speedKph.isFresh(System.currentTimeMillis()) && (model.vehicle.speedKph.value ?: 0.0) > 1.0 }
        assertEquals(TripPhase.DRIVING, state().phase)
        assertEquals(ledger, state().ledger)
        compose.runOnIdle {
            assertEquals(Source.SDK, model.vehicle.speedKph.source)
            assertTrue(model.engine.stationaryBlockers(System.currentTimeMillis()).isNotEmpty())
            model.perform { chooseAlighting(it) }
            assertEquals(TripPhase.DRIVING, model.trip.phase)
        }
        VhalInput.park()
    }

    private fun start(trip: TripState, detailed: Boolean) {
        TripStore(context).save(trip)
        launch()
        assertTrue(state().hasTrip)
        assertEquals(trip.tripId, state().tripId)
        // 입력이 앱에 닿는지 먼저 확인: D · 이동을 넣고 앱이 이동으로 읽은 뒤에 P · 0으로 바꾼다(운행 준비 단계라 앱을 숨기지 않는다).
        VhalInput.drive(5f)
        awaitVehicle(VhalInput.GEAR_DRIVE, moving = true, what = "시작 전 이동 입력")
        compose.runOnIdle { assertTrue(model.engine.stationaryBlockers(System.currentTimeMillis()).isNotEmpty()) }
        awaitStationary()
        if (detailed) {
            compose.onNodeWithTag("tab-details").performClick()
            compose.onNodeWithTag("open-controls").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    private fun launch() {
        check(scenario == null)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        scenario!!.onActivity { model = it.model }
        compose.waitForIdle()
    }

    /** P · 0을 넣고, 앱이 그 값을 읽어 정차로 판단할 때까지 기다린다. 입력이 안 되거나 앱이 못 읽으면 건너뛴다. */
    private fun awaitStationary() {
        VhalInput.park()
        awaitVehicle(VhalInput.GEAR_PARK, moving = false, what = "정차 입력")
        // UX 제한 해제는 car service 환경에 달려 있어 건너뛴다. 앱이 P·0을 읽은 뒤의 정차 판단은 앱 기능이라 실패로 본다.
        val uxFree = runCatching { compose.waitUntil(15_000) { model.sdkVehicle.uxRestricted.value != true } }.isSuccess
        assumeTrue("차량 UX 제한이 풀리지 않음", uxFree)
        val stationary = runCatching { compose.waitUntil(15_000) { model.engine.stationaryBlockers(System.currentTimeMillis()).isEmpty() } }.isSuccess
        assertTrue("P·0을 읽었는데 정차로 판단하지 않음: ${compose.runOnIdle { model.engine.stationaryBlockers(System.currentTimeMillis()) }}", stationary)
    }

    /**
     * 앱의 실제 SDK 관측(vm.sdkVehicle: AAOS getProperty 폴링)이 넣은 기어·속도와 같아질 때까지 기다린다.
     * 입력 명령이 모두 거부됐거나(VhalInput.method == null) 15초 안에 같아지지 않으면 assume으로 건너뛴다.
     */
    private fun awaitVehicle(gear: Int, moving: Boolean, what: String) {
        assumeTrue("$what: VHAL 입력 명령이 모두 거부됨 · ${VhalInput.lastOutput.take(400)}", VhalInput.method != null)
        val matched = runCatching {
            compose.waitUntil(15_000) {
                val v = model.sdkVehicle
                val at = System.currentTimeMillis()
                val speed = v.speedMetersPerSecond.value
                v.connected && v.gear.value == gear && at - v.gear.receivedAtMillis in 0..5_000 &&
                    speed != null && at - v.speedMetersPerSecond.receivedAtMillis in 0..5_000 &&
                    (if (moving) speed > 1f else kotlin.math.abs(speed) <= 0.01f)
            }
        }.isSuccess
        assumeTrue("$what: 앱이 입력값을 읽지 못함(방식 ${VhalInput.method?.label}) · 관측 gear=${model.sdkVehicle.gear.value} " +
            "speed=${model.sdkVehicle.speedMetersPerSecond.value} · ${model.sdkVehicle.speedMetersPerSecond.error}", matched)
    }

    private fun state(): TripState = compose.runOnIdle { model.trip }

    private fun visiblePress(tag: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    /** 상세 조작 화면의 버튼. 탭을 오가면 상세 조작으로 다시 들어간다(옛 press와 같음). */
    private fun press(tag: String) {
        compose.waitForIdle()
        if (tag == "tab-details") {
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag("open-controls").performScrollTo().performClick()
        } else {
            compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
        }
        compose.waitForIdle()
    }

    private fun record(label: String, trip: TripState) = JSONObject().apply {
        put("checkpoint", label); put("phase", trip.phase.name); put("mode", trip.integrationMode.name)
        put("currentStop", trip.currentStop?.id); put("onboardCount", trip.onboardChildren.size)
        put("ledgerEntries", trip.ledger.size); put("route", JSONArray(trip.routeOrder))
        put("teacherCabinCheck", trip.directCabinCheckAtMillis); put("endStatement", trip.endRecord?.statement)
    }
}
