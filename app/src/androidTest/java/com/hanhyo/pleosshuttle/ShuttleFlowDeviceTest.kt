package com.hanhyo.pleosshuttle

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 앱 시작 상태(운행 없음)·저장 운행 복원·개발 도구 탭 노출을 확인한다. 출시 앱에는 모의 차량 입력이 없으므로
 * 탑승·인계·하차 흐름 자체는 엔진 단위 테스트와 Pleos 에뮬레이터(Virtual Console) 수동 확인으로 검증한다.
 */
@RunWith(AndroidJUnit4::class)
class ShuttleFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: ShuttleViewModel
    private val checkpointBackup = linkedMapOf<String, ByteArray?>()
    private var isolationStarted = false
    private val tripFile get() = File(context.filesDir, "trip-v1.bin")

    @Before fun 운행_파일을_격리한다() {
        // Never clear package data or touch other packages.
        check(context.packageName == "com.hanhyo.pleosshuttle")
        isolationStarted = true
        listOf("trip-v1.bin", "trip-v1.bin.bak", "trip-v1.bin.new").forEach { name ->
            val file = File(context.filesDir, name)
            checkpointBackup[name] = if (file.exists()) file.readBytes() else null
            check(!file.exists() || file.delete()) { "Cannot isolate test checkpoint: $name" }
        }
    }

    @After fun 시험_전_운행_파일을_복원한다() {
        if (!isolationStarted) return
        try {
            scenario?.close()
            scenario = null
            instrumentation.waitForIdleSync()
        } finally {
            checkpointBackup.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes == null) check(!file.exists() || file.delete())
                else file.writeBytes(bytes)
            }
        }
    }

    @Test fun `저장된_운행이_없으면_운행_없음으로_시작하고_운행_조작을_받지_않는다`() {
        launch()
        compose.runOnIdle {
            assertFalse(model.trip.hasTrip)
            assertNull(model.storageError)
        }
        compose.onNodeWithTag(NO_TRIP_LOADED_TAG).assertExists()
        compose.onNodeWithTag("board-child-a").assertDoesNotExist()
        compose.onNodeWithTag("start-trip").assertDoesNotExist()
        compose.onNodeWithTag("vehicle-tracking-summary").assertDoesNotExist()
        // 하단 바는 음성 안내만(음악 버튼 없음).
        compose.onNodeWithTag("voice-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("music-play").assertDoesNotExist()
        compose.runOnIdle {
            model.perform { startDriving(it) }
            assertEquals(TripPhase.PREPARING, model.trip.phase)
            assertTrue(model.trip.ledger.isEmpty())
            assertNotNull(model.actionError)
            assertTrue(model.automaticAnnouncementKeys.isEmpty())
            assertTrue(model.automaticNavigationStops.isEmpty())
            assertFalse(model.trackingControls.canEnable)
        }
        // 운행 없음 상태는 저장하지 않는다.
        assertFalse(tripFile.exists())
    }

    @Test fun `이전_빌드의_모의_운행은_버리고_운행_없음으로_시작한다`() {
        val archive = File(context.filesDir, "completed-trips")
        val archivedBefore = archive.listFiles().orEmpty().map { it.name }.toSet()
        TripStore(context).save(DeviceTrips.legacySimulationTrip())
        try {
            launch()
            compose.runOnIdle {
                assertNull(model.storageError)
                assertFalse(model.trip.hasTrip)
            }
            compose.onNodeWithTag(NO_TRIP_LOADED_TAG).assertExists()
            assertFalse(tripFile.exists())
            // 버리기 전에 보관 폴더에 사본을 남긴다.
            assertEquals(1, archive.listFiles().orEmpty().count { it.name !in archivedBefore })
        } finally {
            archive.listFiles().orEmpty().filter { it.name !in archivedBefore }.forEach { check(it.delete()) }
        }
    }

    @Test fun `저장된_기관_운행은_이어서_복원한다`() {
        val saved = DeviceTrips.sdkTrip("device-restore-1")
        TripStore(context).save(saved)
        launch()
        val restored: TripState = compose.runOnIdle { model.trip }
        assertTrue(restored.hasTrip)
        assertEquals(saved.tripId, restored.tripId)
        assertEquals(saved.children, restored.children)
        compose.onNodeWithTag(NO_TRIP_LOADED_TAG).assertDoesNotExist()
        compose.onNodeWithTag("tab-details").performClick()
        saved.children.forEach { compose.onNodeWithText(it.name).assertExists() }
    }

    @Test fun `손상된_운행파일은_새_운행으로_덮어쓰지_않고_오류와_함께_보존한다`() {
        val corrupt = "INTENTIONALLY_CORRUPT_TEST_CHECKPOINT".toByteArray()
        tripFile.writeBytes(corrupt)
        launch()
        compose.runOnIdle { assertNotNull(model.storageError) }
        compose.onNodeWithText("기존 파일을 보존합니다", substring = true).assertExists()
        compose.runOnIdle { model.perform { board("child-a", it) } }
        assertTrue(compose.runOnIdle { model.trip.ledger.isEmpty() })
        assertArrayEquals(corrupt, tripFile.readBytes())
    }

    @Test fun `개발_도구_탭은_디버그_빌드에서만_보인다`() {
        launch()
        compose.onNodeWithTag("tab-details").performClick()
        compose.onNodeWithTag("tab-tools").assertDoesNotExist()
        if (BuildConfig.DEBUG) {
            compose.onNodeWithTag("tab-dev").assertIsDisplayed().performClick()
            compose.onNodeWithTag("expand-tracking-lab").performScrollTo().assertExists()
            listOf("tab-observations", "tab-diagnostics").forEach { compose.onNodeWithTag(it).performScrollTo().assertExists() }
            listOf("tab-trials", "tab-door-lab", "expand-session-tools", "expand-connection-mode").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            compose.onNodeWithTag("tab-diagnostics").performScrollTo().performClick()
            compose.onNodeWithTag("export-diagnostics").performScrollTo().assertExists()
        } else {
            compose.onNodeWithTag("tab-dev").assertDoesNotExist()
        }
    }

    @Test fun `Gleo_음성_Intent는_화면만_열고_기록을_바꾸지_않는다`() {
        launch()
        listOf("OPEN_DROPOFF_PREPARATION", "SHOW_NEXT_STOP").forEach { action ->
            compose.runOnIdle { model.panel = "details" }
            instrumentation.runOnMainSync {
                context.startActivity(Intent(action).apply {
                    setClass(context, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            compose.waitUntil(5_000) { model.panel == "flow" }
            compose.runOnIdle {
                assertFalse(model.trip.hasTrip)
                assertTrue(model.trip.ledger.isEmpty())
            }
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
}
