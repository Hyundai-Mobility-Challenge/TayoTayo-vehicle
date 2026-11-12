package com.hanhyo.pleosshuttle

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.ResultStatus
import com.hanhyo.pleosshuttle.domain.Source
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tests the fictional local flow only; no test selects the real SDK adapter. */
@RunWith(AndroidJUnit4::class)
class ShuttleFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var scenarioLaunchIntent: Intent? = null
    private lateinit var model: ShuttleViewModel
    private val checkpointBackup = linkedMapOf<String, ByteArray?>()
    private var generatedArchive: File? = null
    private var preferencesBackup: Map<String, *> = emptyMap<String, Any>()
    private var isolationStarted = false
    private val trialPreferences get() = context.getSharedPreferences("simulation-settings", 0)
    private val routePreferences get() = context.getSharedPreferences("route-settings", 0)
    private var routePreferencesBackup: Map<String, *> = emptyMap<String, Any>()

    @Before fun 시험용_운행_파일을_격리한다() {
        // Never clear package data or touch the old PleosProbe/PleosTest packages.
        check(context.packageName == "com.hanhyo.pleosshuttle")
        preferencesBackup = trialPreferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        routePreferencesBackup = routePreferences.all.toMap()
        isolationStarted = true
        // 경로 확인 설정(기준 처음 보기, 보호자 알림 스위치)은 시험마다 처음 상태에서 시작한다.
        check(routePreferences.edit().clear().commit())
        // Isolate only the two simulation inputs. Unrelated preferences remain untouched.
        check(trialPreferences.edit().remove("scenario").remove("doorLocked").commit())
        listOf("trip-v1.bin", "trip-v1.bin.bak", "trip-v1.bin.new").forEach { name ->
            val file = File(context.filesDir, name)
            checkpointBackup[name] = if (file.exists()) file.readBytes() else null
            check(!file.exists() || file.delete()) { "Cannot isolate test checkpoint: $name" }
        }
    }

    @After fun 시험_전_운행_파일을_복원한다() {
        if (!isolationStarted) return
        try {
            scenario?.let { active ->
                // ActivityScenario matches lifecycle events against its original launch Intent.
                // MainActivity correctly replaces its Intent in onNewIntent, so restore only the
                // test tracking identity after all delivery/body assertions, before destruction.
                val original = checkNotNull(scenarioLaunchIntent)
                active.onActivity { activity -> activity.intent = Intent(original) }
                active.close()
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, active.state)
            }
            scenario = null
            scenarioLaunchIntent = null
            instrumentation.waitForIdleSync()
        } finally {
            generatedArchive?.let { file -> check(!file.exists() || file.delete()) }
            generatedArchive = null
            checkpointBackup.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes == null) check(!file.exists() || file.delete())
                else file.writeBytes(bytes)
            }
            val editor = trialPreferences.edit().remove("scenario").remove("doorLocked")
            preferencesBackup.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> editor.remove(key)
                    else -> error("Unexpected SharedPreferences value for $key")
                }
            }
            check(editor.commit())
            val routeEditor = routePreferences.edit().clear()
            routePreferencesBackup.forEach { (key, value) -> if (value is Boolean) routeEditor.putBoolean(key, value) else error("Unexpected route setting $key") }
            check(routeEditor.commit())
        }
    }

    @Test fun 기록을_보관하고_선택한_가상_단계부터_재시작한다() {
        launch()
        val folder = File(context.filesDir, "completed-trips")
        val before = folder.listFiles().orEmpty().map { it.name }.toSet()
        try {
            val initial = state()
            compose.runOnIdle { model.restartSimulation(1) }
            assertEquals(TripPhase.ALIGHTING, state().phase)
            assertEquals(4, state().onboardChildren.size)
            assertTrue(state().ledger.isEmpty())
            assertNotEquals(initial.tripId, state().tripId)
            assertTrue(TripStore(context).savedTrips().any { it == initial })
            assertEquals(state(), TripStore(context).load())
            compose.runOnIdle { model.restartSimulation(2) }
            assertEquals(TripPhase.END_REVIEW, state().phase)
            assertTrue(state().onboardChildren.isEmpty())
            assertNull(state().endRecord)
            compose.runOnIdle { model.restartSimulation(0) }
            assertEquals(TripPhase.PREPARING, state().phase)
            assertTrue(state().children.all { it.status == Attendance.NOT_BOARDED })
            compose.runOnIdle {
                assertTrue(model.exportDiagnostics().readText().contains(model.trip.tripId))
                model.audio.stop()
            }
            compose.onNodeWithTag("music-stop").assertIsDisplayed()
        } finally {
            folder.listFiles().orEmpty().filter { it.name !in before }.forEach { check(it.delete()) }
        }
    }

    @Test fun `탑승부터_인계와_하차와_실내확인을_거쳐_하원_종료기록을_저장한다`() {
        launch()
        val checkpoints = JSONArray()
        val childIds = state().children.map { it.id }
        childIds.forEach { press("board-$it") }
        assertEquals(4, state().onboardChildren.size)
        assertEquals(4, state().ledger.size)
        compose.onNodeWithTag("board-${childIds.first()}").assertIsNotEnabled()

        press("calculate-route")
        assertNotNull(state().routeProposal)
        assertTrue(state().routeProposal!!.synthetic)
        assertEquals(6, state().routeProposal!!.evaluatedOrders)
        val proposed = state().routeProposal!!.stopOrder
        press("apply-route")
        assertEquals(proposed, state().routeOrder)
        assertFalse(state().routeIsExample)
        assertNull(state().routeProposal)
        checkpoints.put(record("prepared", state()))
        press("start-driving")

        repeat(proposed.size) { stopIndex ->
            assertEquals(TripPhase.DRIVING, state().phase)
            val stop = state().currentStop!!
            press("navigate-next")
            compose.runOnIdle {
                assertTrue(model.simulatedNavigation.contains(stop.name))
                assertTrue(model.simulatedNavigation.contains(stop.latitude.toString()))
            }
            press("choose-alighting")
            assertEquals(TripPhase.ALIGHTING, state().phase)
            press("confirm-surroundings")
            if (stopIndex == 0) {
                press("prepare-door")
                assertNotNull(state().unlockConfirmation)
                press("confirm-door")
                compose.waitUntil(6_000) { model.trip.unlockAttempt?.status == ResultStatus.TARGET_OBSERVED }
                assertEquals(false, state().unlockAttempt?.observedLocked)
                // A door observation never edits child attendance.
                assertEquals(4, state().onboardChildren.size)
                assertEquals(4, state().ledger.size)
                compose.runOnIdle { assertEquals(1, model.simulatedDoorRequests) }
            } else {
                compose.onNodeWithTag("prepare-door").performScrollTo().assertIsNotEnabled()
                reason("문은 이전 모의 지점에서 해제된 상태. 운전자가 직접 문 처리 사실을 기록함")
                press("manual-door")
                assertNotNull(state().manualDoorReason)
                assertNull(state().unlockAttempt)
            }
            press("tab-details", scroll = false)
            state().currentStopChildren.forEach { child ->
                compose.onNodeWithTag("alight-${child.id}").performScrollTo().assertIsNotEnabled()
                press("handover-${child.id}")
                assertTrue(child.id in state().handoverChildIds)
                press("alight-${child.id}")
                assertEquals(Attendance.ALIGHTED, state().children.first { it.id == child.id }.status)
            }
            checkpoints.put(record("alighted-${stop.id}", state()))
            compose.onNodeWithTag("resume-reason").performScrollTo().performTextReplacement("별도 시험 뒤 미확인 내용을 유지하고 운행 화면으로 복귀")
            closeSoftKeyboard()
            press("resume-driving")
            assertEquals(TripPhase.DRIVING, state().phase)
        }

        assertNull(state().currentStop)
        assertTrue(state().onboardChildren.isEmpty())
        press("navigate-next")
        compose.runOnIdle { assertTrue(model.simulatedNavigation.contains("서울시청")) }
        press("begin-end")
        assertEquals(TripPhase.END_REVIEW, state().phase)
        compose.onNodeWithTag("finish-trip").performScrollTo().assertIsNotEnabled()
        press("confirm-cabin")
        press("finish-trip")
        assertEquals(TripPhase.ENDED, state().phase)
        assertEquals(8, state().ledger.size)
        assertTrue(state().children.all { it.status == Attendance.ALIGHTED })
        assertNotNull(state().endRecord)
        assertFalse(state().endRecord!!.sensorCoverageKnown)
        assertTrue(state().endRecord!!.statement.contains("안전 보증이 아님"))
        assertEquals(TripPhase.ENDED, TripStore(context).load()?.phase)
        assertRealAdaptersAbsent()
        checkpoints.put(record("ended", state()))
        File(context.filesDir, "flow-device-evidence.json").writeText(JSONObject().apply {
            put("scope", "Compose UI and fictional local adapters; no SDK control")
            put("checkpoints", checkpoints)
        }.toString(2))
        screenshot("flow-ended-device.png")
        val completed = state()
        val archive = File(context.filesDir, "completed-trips/${completed.tripId}.bin")
        if (!archive.exists()) {
            generatedArchive = archive
            press("new-trip")
            assertEquals(TripPhase.PREPARING, state().phase)
            assertTrue(state().children.all { it.status == Attendance.NOT_BOARDED })
            java.io.ObjectInputStream(archive.inputStream()).use { assertEquals(completed, it.readObject()) }
            val archivedBytes = archive.readBytes()
            val recovered = com.hanhyo.pleosshuttle.domain.ShuttleEngine().also { it.restore(completed, System.currentTimeMillis()) }
            TripStore(context).archiveEnded(recovered.state)
            assertArrayEquals(archivedBytes, archive.readBytes())
        }
    }

    @Test fun `신호정차는_문을_요청하지_않고_이동과_데이터만료는_해제확인을_무효화한다`() {
        launch()
        press("board-child-a")
        press("start-driving")
        chooseScenario(TrialScenario.SIGNAL)
        compose.runOnIdle {
            assertEquals(TripPhase.DRIVING, model.trip.phase)
            assertEquals(0, model.simulatedDoorRequests)
            assertNull(model.trip.unlockAttempt)
        }
        press("tab-details", scroll = false)
        compose.onNodeWithTag("choose-alighting").performScrollTo().assertIsNotEnabled()
        chooseScenario(TrialScenario.MOVING)
        press("tab-flow", scroll = false)
        compose.onNodeWithTag("arrive-stop").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("choose-alighting").assertDoesNotExist()
        compose.onNodeWithTag("confirm-door").assertDoesNotExist()
        press("tab-details", scroll = false)
        compose.onNodeWithTag("choose-alighting").performScrollTo().assertIsNotEnabled()

        chooseScenario(TrialScenario.PARKED)
        press("tab-details", scroll = false)
        press("choose-alighting")
        press("confirm-surroundings")
        press("prepare-door")
        assertNotNull(state().unlockConfirmation)
        chooseScenario(TrialScenario.MOVING)
        assertNull(state().unlockConfirmation)
        compose.runOnIdle { assertEquals(0, model.simulatedDoorRequests) }

        chooseScenario(TrialScenario.PARKED)
        // Freeze already-read simulation observations; then obtain a short-lived confirmation.
        chooseScenario(TrialScenario.STALE)
        press("tab-details", scroll = false)
        press("confirm-surroundings")
        press("prepare-door")
        assertNotNull(state().unlockConfirmation)
        compose.waitUntil(8_000) { model.trip.unlockConfirmation == null && !model.vehicle.speedKph.isFresh(model.now) }
        compose.onNodeWithTag("confirm-door").assertDoesNotExist()
        compose.onNodeWithTag("prepare-door").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, model.simulatedDoorRequests)
            assertTrue(model.trip.diagnostics.any { it.message.contains("유효시간 만료") })
            model.simulateAutomaticIdea()
            assertTrue(model.autoSimulationMessage.contains("실제 요청 0회"))
        }
        assertRealAdaptersAbsent()
    }

    @Test fun `앱을_다시_열면_미완료_기록을_보존하고_오래된_해제확인을_버린다`() {
        launch()
        press("board-child-a")
        press("start-driving")
        press("choose-alighting")
        // This test targets checkpoint restoration. Create the valid pending confirmation in one
        // main-thread turn so UI scrolling latency does not consume its five-second lifetime.
        val before = compose.runOnIdle {
            model.chooseScenario(TrialScenario.PARKED)
            model.perform { confirmSurroundings(it) }
            model.prepareDoor()
            assertNotNull("${model.message}; ${model.engine.unlockBlockReasons(System.currentTimeMillis())}", model.trip.unlockConfirmation)
            model.trip
        }
        press("tab-door-lab", scroll = false)
        compose.onNodeWithTag("confirm-door").assertExists()
        assertNotNull(before.unlockConfirmation)
        assertEquals(before.ledger, TripStore(context).load()?.ledger)

        scenario!!.close()
        scenario = null
        // Simulate a checkpoint written before an abrupt process death: it still contains a token.
        TripStore(context).save(before)
        instrumentation.runOnMainSync {
            val restoredBeforeForeground = ShuttleViewModel(context.applicationContext as Application)
            assertEquals(TripPhase.ALIGHTING, restoredBeforeForeground.trip.phase)
            assertEquals(before.children, restoredBeforeForeground.trip.children)
            assertEquals(before.ledger, restoredBeforeForeground.trip.ledger)
            assertNull(restoredBeforeForeground.trip.unlockConfirmation)
            assertNull(restoredBeforeForeground.trip.manualSurroundingsCheckAtMillis)
            assertFalse(restoredBeforeForeground.vehicle.connected)
            assertNull(restoredBeforeForeground.vehicle.speedKph.value)
            restoredBeforeForeground.audio.close()
        }
        launch()
        assertEquals(TripPhase.ALIGHTING, state().phase)
        assertEquals(before.ledger, state().ledger)
        assertEquals(Attendance.ONBOARD, state().children.first { it.id == "child-a" }.status)
        assertNull(state().unlockConfirmation)
        assertNull(state().manualSurroundingsCheckAtMillis)
        compose.onNodeWithTag("confirm-door").assertDoesNotExist()
        press("tab-door-lab", scroll = false)
        compose.onNodeWithTag("confirm-surroundings").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, model.simulatedDoorRequests) }
        assertRealAdaptersAbsent()
    }

    @Test fun `손상된_운행파일은_새_운행으로_덮어쓰지_않고_오류와_함께_보존한다`() {
        val file = File(context.filesDir, "trip-v1.bin")
        val corrupt = "INTENTIONALLY_CORRUPT_FICTIONAL_TEST_CHECKPOINT".toByteArray()
        file.writeBytes(corrupt)
        launch()
        compose.runOnIdle { assertNotNull(model.storageError) }
        compose.onNodeWithText("기존 파일을 보존합니다", substring = true).assertExists()
        compose.runOnIdle { model.perform { board("child-a", it) } }
        assertTrue(state().ledger.isEmpty())
        assertArrayEquals(corrupt, file.readBytes())
        assertRealAdaptersAbsent()
    }

    @Test fun `새_Activity에서도_센서연결끊김과_갱신중단을_정상_관측으로_바꾸지_않는다`() {
        launch()
        press("board-child-a")
        press("start-driving")
        val ledger = state().ledger
        listOf(TrialScenario.DISCONNECTED, TrialScenario.STALE).forEach { input ->
            chooseScenario(input)
            assertEquals(input.name, trialPreferences.getString("scenario", null))
            scenario!!.close()
            scenario = null
            launch(expectedScenario = input)
            assertEquals(TripPhase.DRIVING, state().phase)
            assertEquals(ledger, state().ledger)
            compose.runOnIdle {
                assertEquals(input, model.scenario)
                assertFalse(model.vehicle.connected)
                assertNull(model.vehicle.speedKph.value)
                assertNull(model.trip.unlockConfirmation)
                assertEquals(0, model.simulatedDoorRequests)
            }
            compose.onNodeWithTag("choose-alighting").performScrollTo().assertIsNotEnabled()
            assertRealAdaptersAbsent()
        }
    }

    @Test fun `로컬_Intent_세_동작은_화면과_음악만_연결하고_하차와_문요청을_자동진행하지_않는다`() {
        launch()
        press("board-child-a")
        press("start-driving")
        val ledgerBefore = state().ledger
        val nextStopBefore = state().currentStop
        val checkpoints = JSONArray()
        try {
            listOf("OPEN_DROPOFF_PREPARATION", "SHOW_NEXT_STOP", "PLAY_TEST_MUSIC").forEach { action ->
                // Starting from another panel makes screen-opening intents observable.
                if (action != "PLAY_TEST_MUSIC") press("tab-diagnostics", scroll = false)
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(action).apply {
                        setClass(context, MainActivity::class.java)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                compose.waitUntil(5_000) {
                    var delivered = false
                    scenario!!.onActivity { delivered = it.intent.action == action }
                    delivered
                }
                scenario!!.onActivity { assertSame("SINGLE_TOP must reuse the current ViewModel", model, it.model) }
                if (action == "PLAY_TEST_MUSIC") {
                    compose.waitUntil(5_000) { model.audioState.isPlaying }
                } else {
                    compose.waitUntil(5_000) { model.panel == "flow" }
                    compose.onNodeWithTag("arrive-stop").assertExists()
                }
                compose.runOnIdle {
                    assertEquals(TripPhase.DRIVING, model.trip.phase)
                    assertEquals(IntegrationMode.SIMULATION, model.trip.integrationMode)
                    assertEquals(nextStopBefore, model.trip.currentStop)
                    assertEquals(ledgerBefore, model.trip.ledger)
                    assertNull(model.trip.unlockConfirmation)
                    assertNull(model.trip.unlockAttempt)
                    assertEquals(0, model.simulatedDoorRequests)
                    checkpoints.put(JSONObject().apply {
                        put("action", action)
                        put("delivery", "explicit local Intent to existing Activity.onNewIntent")
                        put("timeMillis", System.currentTimeMillis())
                        put("phase", model.trip.phase.name)
                        put("panel", model.panel)
                        put("nextStop", model.trip.currentStop?.name)
                        put("simulatedDoorRequests", model.simulatedDoorRequests)
                        put("musicPlaying", model.audioState.isPlaying)
                        put("musicState", model.audioState.musicState)
                        put("audioEvents", JSONArray(model.audioState.events))
                        put("gleoVoiceVerified", false)
                        put("schemaActivationVerified", false)
                        put("audibleOutputVerified", false)
                    })
                }
                assertRealAdaptersAbsent()
            }
        } finally {
            compose.runOnIdle { model.audio.stop() }
            File(context.filesDir, "gleo-local-intent-evidence.json").writeText(JSONObject().apply {
                put("scope", "Local Android Intent delivery only; no Gleo voice request, schema submission, or activation")
                put("checkpoints", checkpoints)
            }.toString(2))
        }
    }

    @Test fun `자동_음성과_같은_화면으로_인계와_하차를_기록하고_운행을_종료한다`() {
        launch(detailed = false)
        val checkpoints = JSONArray()
        fun visiblePress(tag: String) {
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("flow-guide").assertIsDisplayed()
        compose.onNodeWithTag("music-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("tab-trials").assertDoesNotExist()
        screenshot("guide-01-boarding.png")
        state().children.forEach { visiblePress("board-${it.id}") }
        screenshot("guide-02-ready.png")
        visiblePress("start-trip")
        compose.waitUntil(20_000) { model.audioState.speechStatus == com.hanhyo.pleosshuttle.audio.SpeechStatus.COMPLETED }
        assertEquals(1, compose.runOnIdle { model.automaticAnnouncementKeys.size })
        assertEquals(listOf(state().currentStop!!.id), compose.runOnIdle { model.automaticNavigationStops })
        val initialSpeechEvents = compose.runOnIdle { model.audioState.events.toList() }
        screenshot("guide-03-driving.png")
        // Recomposition/ticks and background return must not repeat the destination cue.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, compose.runOnIdle { model.automaticAnnouncementKeys.size })
        repeat(state().routeOrder.size) { index ->
            visiblePress("arrive-stop")
            listOf("prepare-door", "confirm-surroundings", "reason", "resume-reason", "tab-diagnostics").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            if(index == 0) screenshot("guide-04-handover.png")
            state().currentStopChildren.filter { it.status == Attendance.ONBOARD }.forEach { child ->
                compose.onNodeWithTag("alight-${child.id}").assertDoesNotExist()
                visiblePress("handover-${child.id}")
                compose.onNodeWithTag("handover-${child.id}").assertDoesNotExist()
                if(index == 0) screenshot("guide-05-alight.png")
                visiblePress("alight-${child.id}")
            }
            assertEquals(TripPhase.DRIVING, state().phase)
            assertEquals(index + 1, state().currentStopIndex)
            assertNull(state().manualDoorReason)
            assertNull(state().manualSurroundingsCheckAtMillis)
            assertNull(state().unlockAttempt)
            assertEquals(0, compose.runOnIdle { model.simulatedDoorRequests })
            checkpoints.put(record("automatic-stop-$index", state()))
        }
        assertNull(state().currentStop)
        assertTrue(state().onboardChildren.isEmpty())
        visiblePress("arrive-origin")
        screenshot("guide-06-cabin.png")
        visiblePress("confirm-finish")
        assertEquals(TripPhase.ENDED, state().phase)
        assertEquals(8, state().ledger.size)
        assertTrue(state().children.all { it.status == Attendance.ALIGHTED })
        assertFalse(state().endRecord!!.sensorCoverageKnown)
        assertEquals(TripPhase.ENDED, TripStore(context).load()?.phase)
        assertEquals(state().routeOrder + state().origin.id, compose.runOnIdle { model.automaticNavigationStops })
        val cues = compose.runOnIdle { model.automaticAnnouncementKeys.toList() }
        assertEquals(9, cues.size)
        assertEquals(cues.size, cues.distinct().size)
        checkpoints.put(record("automatic-ended", state()))
        assertRealAdaptersAbsent()
        screenshot("guide-07-ended.png")
        File(context.filesDir, "guided-complete-flow.json").writeText(JSONObject()
            .put("scope", "Same teacher flow for all three simulated stops and origin; no scroll, tabs, door commands or speech button")
            .put("firstAutomaticSpeechCompleted", true)
            .put("audibleOutputVerified", false)
            .put("automaticCueKeys", JSONArray(cues))
            .put("automaticNavigationStops", JSONArray(compose.runOnIdle { model.automaticNavigationStops }))
            .put("firstSpeechEvents", JSONArray(initialSpeechEvents))
            .put("checkpoints", checkpoints).toString(2))
    }

    @Test fun `상세_첫화면은_네명_명단을_보이고_시험과_로그는_별도로_연다`() {
        launch(detailed=false)
        val before = state()
        compose.onNodeWithTag("tab-details").assertIsDisplayed().performClick()
        state().children.forEach { child ->
            compose.onNodeWithText(child.name.removePrefix("가상 ")).assertIsDisplayed()
            compose.onNodeWithTag("correct-${child.id}").assertIsDisplayed()
        }
        compose.onNodeWithTag("board-child-a").assertDoesNotExist()
        compose.onNodeWithTag("tab-diagnostics").assertDoesNotExist()
        compose.onNodeWithTag("content-route-summary").assertDoesNotExist()
        screenshot("details-roster.png")
        compose.onNodeWithTag("correct-child-a").performClick()
        compose.onNodeWithTag("correct-to-ONBOARD").assertIsNotEnabled()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("tab-tools").assertIsDisplayed().performClick()
        listOf("trials", "door-lab", "observations", "diagnostics").forEach { compose.onNodeWithTag("tab-$it").assertIsDisplayed() }
        screenshot("details-tools.png")
        compose.onNodeWithTag("tab-diagnostics").performClick()
        compose.onNodeWithTag("export-diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("content-app-log").assertDoesNotExist()
        screenshot("details-diagnostics.png")
        compose.onNodeWithTag("expand-app-log").performScrollTo().performClick()
        compose.onNodeWithTag("content-app-log").assertExists()
        compose.onNodeWithTag("expand-app-log").performScrollTo().performClick()
        compose.onNodeWithTag("content-app-log").assertDoesNotExist()
        compose.onNodeWithTag("tab-flow").performClick()
        compose.onNodeWithTag("board-child-a").assertIsDisplayed()
        assertEquals(before.ledger, state().ledger)
        assertEquals(before.children, state().children)
        assertEquals(before.phase, state().phase)
        assertEquals(0, compose.runOnIdle { model.simulatedDoorRequests })
        assertRealAdaptersAbsent()
    }

    @Test fun `음악_재생위치는_자동_갱신되고_탭을_바꿔도_재생버튼이_보인다`() {
        launch(detailed = false)
        compose.onNodeWithTag("music-play").assertIsDisplayed().performClick()
        try {
            compose.waitUntil(5_000) { model.audioState.isPlaying && model.audioState.playbackPositionMillis > 0 }
            compose.onNodeWithTag("music-status").assertIsDisplayed()
            assertTrue(compose.runOnIdle { model.audioState.durationMillis } > 0)
            press("tab-trials", scroll = false)
            compose.onNodeWithTag("music-pause").assertIsDisplayed().performClick()
            compose.waitUntil(3_000) { !model.audioState.isPlaying }
            compose.onNodeWithTag("music-play").assertIsDisplayed()
            assertNull(compose.runOnIdle { model.audioState.outputRouteLabel })
        } finally {
            compose.runOnIdle { model.audio.stop() }
        }
    }

    @Test fun `경로_확인에서_순서를_바꾸면_고정_순서로_출발하고_정차_중에는_남은_순서만_바꾼다`() {
        launch(detailed = false)
        fun visiblePress(tag: String) {
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
        }
        state().children.forEach { visiblePress("board-${it.id}") }
        // 경로 확인(L2): 방문 순서 띠, 처음 한 번 보이는 기준, 운행 시작 버튼
        compose.onNodeWithTag("route-strip").assertIsDisplayed()
        compose.onNodeWithTag("route-criteria-list").assertIsDisplayed()
        compose.onNodeWithTag("start-trip").assertExists()
        assertTrue(compose.runOnIdle { model.routeCriteriaSeen })
        screenshot("route-01-check.png")
        visiblePress("open-reorder")
        compose.onNodeWithTag("reorder").assertIsDisplayed()
        compose.onNodeWithTag("move-up-forest").assertIsNotEnabled()
        visiblePress("move-up-park")
        compose.onNodeWithTag("route-warnings").performScrollTo().assertIsDisplayed()
        visiblePress("reorder-reason-보호자 요청")
        screenshot("route-02-reorder.png")
        compose.onNodeWithTag("reorder-apply").performScrollTo()
        visiblePress("reorder-apply")
        assertEquals(listOf("park", "forest", "museum"), state().routeOrder)
        assertEquals(com.hanhyo.pleosshuttle.domain.RouteObjective.FIXED, state().routeObjective)
        assertFalse(state().routeIsExample)
        assertTrue(state().diagnostics.last { it.feature == "방문 순서 변경" }.message.contains("보호자 요청"))
        compose.onNodeWithTag("reorder").assertDoesNotExist()
        compose.onNodeWithTag("start-trip").performScrollTo()
        visiblePress("start-trip")
        assertEquals(listOf("park", "forest", "museum"), state().routeOrder)
        assertEquals(listOf("park"), compose.runOnIdle { model.automaticNavigationStops })
        // 정차 중(L5)에는 지금 정류장을 고정하고 남은 두 곳만 바꾼다.
        visiblePress("arrive-stop")
        compose.onNodeWithTag("open-reorder").performScrollTo()
        visiblePress("open-reorder")
        compose.onNodeWithTag("move-up-park").assertDoesNotExist()
        compose.onNodeWithTag("move-up-forest").assertIsNotEnabled()
        visiblePress("move-up-museum")
        screenshot("route-03-reorder-stopped.png")
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
        assertEquals(listOf("park", "museum"), compose.runOnIdle { model.automaticNavigationStops })
        assertRealAdaptersAbsent()
    }

    private fun launch(expectedScenario: TrialScenario = TrialScenario.PARKED, detailed: Boolean = true) {
        check(scenario == null)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        scenarioLaunchIntent = Intent(launchIntent)
        scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        scenario!!.onActivity { model = it.model }
        compose.waitUntil(5_000) {
            model.scenario == expectedScenario && (model.storageError != null || when (expectedScenario) {
                TrialScenario.DISCONNECTED -> !model.vehicle.connected && model.vehicle.speedKph.source == Source.SIMULATED
                TrialScenario.STALE -> !model.vehicle.connected && model.vehicle.speedKph.value == null
                else -> model.vehicle.connected
            })
        }
        compose.waitForIdle()
        assertEquals(IntegrationMode.SIMULATION, state().integrationMode)
        // Legacy safety/inspection cases exercise the explicit detailed controls. The complete
        // teacher journey below stays on the default focused screen and never enters details.
        if (detailed) {
            compose.onNodeWithTag("tab-details").performClick()
            compose.onNodeWithTag("open-controls").performScrollTo().performClick()
        }
        compose.waitForIdle()
    }

    private fun state(): TripState = compose.runOnIdle { model.trip }

    private fun press(tag: String, scroll: Boolean = true) {
        try {
            val toolTabs = setOf("tab-trials", "tab-diagnostics", "tab-observations", "tab-door-lab")
            val doorActions = setOf("confirm-surroundings", "prepare-door", "confirm-door", "manual-door")
            if (tag in toolTabs || (tag in doorActions && model.panel != "door-lab")) {
                if(model.panel == "flow") compose.onNodeWithTag("tab-details").performClick()
                compose.onNodeWithTag("tab-tools").performClick()
                compose.waitForIdle()
                if(tag in doorActions) {
                    compose.onNodeWithTag("tab-door-lab").performClick()
                    compose.waitForIdle()
                }
            }
            val node = compose.onNodeWithTag(tag)
            if (scroll) node.performScrollTo()
            node.assertIsEnabled().performClick()
            compose.waitForIdle()
            // Legacy editing tests enter the explicit controls screen. Focused/read-only journeys
            // click nodes directly, so they never silently open the editing controls.
            if(tag == "tab-details") {
                compose.onNodeWithTag("open-controls").performScrollTo().performClick()
                compose.waitForIdle()
            }
        } catch (error: Throwable) {
            val details = recordFailure("press($tag)", error)
            throw AssertionError("$tag interaction failed. $details", error)
        }
    }

    private fun reason(text: String) {
        compose.onNodeWithTag("reason").performScrollTo().performTextReplacement(text)
        closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun chooseScenario(value: TrialScenario) {
        press("tab-trials", scroll = false)
        press("scenario-${value.name}")
    }

    private fun assertRealAdaptersAbsent() = compose.runOnIdle {
        // Inspect only our own private dependencies, never platform or vehicle hidden APIs.
        listOf("realVehicle", "realNavi").forEach { name ->
            val field = ShuttleViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertNull("Simulation must not instantiate $name", field.get(model))
        }
    }

    private fun record(label: String, trip: TripState) = JSONObject().apply {
        put("checkpoint", label)
        put("phase", trip.phase.name)
        put("mode", trip.integrationMode.name)
        put("currentStop", trip.currentStop?.id)
        put("onboardCount", trip.onboardChildren.size)
        put("ledgerEntries", trip.ledger.size)
        put("route", JSONArray(trip.routeOrder))
        put("doorOutcome", trip.unlockAttempt?.status?.name)
        put("manualDoorReason", trip.manualDoorReason)
        put("teacherCabinCheck", trip.directCabinCheckAtMillis)
        put("sensorCoverageKnown", trip.endRecord?.sensorCoverageKnown)
        put("endStatement", trip.endRecord?.statement)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File(context.filesDir, name)
        val status = File(context.filesDir, "flow-screenshot-status.txt")
        if (output.exists()) output.delete()
        runCatching {
            // Capture the Compose root without claiming the system UiAutomation service.
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            status.writeText("${System.currentTimeMillis()} Captured Compose root: $name. This is not a full system-screen capture.\n")
        }.onFailure { error ->
            status.writeText("${System.currentTimeMillis()} Screenshot not captured: ${error.javaClass.simpleName}: ${error.message}\n")
            recordFailure("optional Compose screenshot", error)
        }
    }

    private fun recordFailure(label: String, failure: Throwable): String {
        var details = "State unavailable"
        runCatching {
            instrumentation.runOnMainSync {
                val wallNow = System.currentTimeMillis()
                details = buildString {
                    appendLine("$label wallNow=$wallNow uiNow=${model.now} clockLag=${wallNow-model.now}")
                    appendLine("phase=${model.trip.phase} scenario=${model.scenario} preferences=${trialPreferences.all}")
                    appendLine("message=${model.message} storageError=${model.storageError}")
                    appendLine("connected=${model.vehicle.connected} speed=${model.vehicle.speedKph}")
                    appendLine("gear=${model.vehicle.gear} parking=${model.vehicle.parkingBrake} door=${model.vehicle.doorLocked}")
                    appendLine("surroundings=${model.surroundings} manualAt=${model.trip.manualSurroundingsCheckAtMillis}")
                    appendLine("confirmation=${model.trip.unlockConfirmation} attempt=${model.trip.unlockAttempt}")
                    appendLine("stationaryAtUiNow=${model.engine.stationaryBlockers(model.now)}")
                    appendLine("stationaryAtWallNow=${model.engine.stationaryBlockers(wallNow)}")
                    appendLine("unlockAtUiNow=${model.engine.unlockBlockReasons(model.now)}")
                    appendLine("unlockAtWallNow=${model.engine.unlockBlockReasons(wallNow)}")
                    model.trip.diagnostics.takeLast(8).forEach { appendLine(it) }
                }
            }
            File(context.filesDir, "flow-failure-diagnostics.txt").appendText(
                "$details\n${failure.javaClass.name}: ${failure.message}\n\n"
            )
        }
        return details
    }
}
