package com.hanhyo.pleosshuttle

import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.LedgerEntry
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Host sequence: stage=prepare, force-stop this package, then stage=verify in a new process.
 * Default all-tests execution skips this helper. No SDK adapter or control method is invoked.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestoreDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("simulation-settings", 0)
    private val backupDirectory get() = File(context.filesDir, "process-restore-backup")
    private var activity: ActivityScenario<MainActivity>? = null
    private val protectedFiles get() = linkedMapOf(
        "trip" to File(context.filesDir, "trip-v1.bin"),
        "tripBak" to File(context.filesDir, "trip-v1.bin.bak"),
        "tripNew" to File(context.filesDir, "trip-v1.bin.new"),
        "preferences" to File(context.applicationInfo.dataDir, "shared_prefs/simulation-settings.xml"),
        "preferencesBak" to File(context.applicationInfo.dataDir, "shared_prefs/simulation-settings.xml.bak"),
    )

    @Test fun `실제_프로세스_종료_전후의_미완료_운행을_확인하고_원본을_복원한다`() {
        val stage = InstrumentationRegistry.getArguments().getString("stage")
        assumeTrue("Host must select stage=prepare or stage=verify", stage != null)
        check(context.packageName == "com.hanhyo.pleosshuttle")
        when (stage) {
            "prepare" -> prepare()
            "verify" -> verifyAndRestore()
            else -> error("Unsupported stage=$stage; no files changed")
        }
    }

    private fun prepare() {
        // Claim a fresh directory before reading or changing app state. An interrupted prior run
        // retains its original backup and must be verified/restored instead of overwritten.
        check(backupDirectory.mkdir()) { "Existing backup preserved: ${backupDirectory.path}; run stage=verify first" }
        val original = JSONObject().put("runId", UUID.randomUUID().toString())
            .put("preparePid", Process.myPid()).put("uid", Process.myUid())
        val report = JSONObject().put("stage", "prepare").put("pid", Process.myPid()).put("uid", Process.myUid())
        var prepared = false
        try {
            captureOriginal(original)
            writeAtomic(File(backupDirectory, "original.json"), original)
            // Only the new app's checkpoint and location-sharing preferences are isolated.
            protectedFiles.filterKeys { it.startsWith("trip") }.values.forEach { check(!it.exists() || it.delete()) }
            val tripId = "process-restore-${original.getString("runId") }"
            // 출시 앱에는 모의 차량 입력이 없으므로, 운행 중(탑승 1명) 상태를 운행 파일에 직접 만들어 둔다.
            val boardedAt = System.currentTimeMillis()
            val unfinished = DeviceTrips.sdkTrip(tripId).let { trip ->
                trip.copy(phase = TripPhase.DRIVING,
                    children = trip.children.map { if (it.id == "child-a") it.copy(status = Attendance.ONBOARD) else it },
                    ledger = listOf(LedgerEntry("ledger-$boardedAt-1", "child-a", Attendance.NOT_BOARDED, Attendance.ONBOARD, boardedAt, "선생님 탑승 확인")))
            }
            TripStore(context).save(unfinished)
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var expected: TripState
            activity!!.onActivity { screen ->
                val model = screen.model
                assertEquals(IntegrationMode.SDK, model.trip.integrationMode)
                assertTrue(model.trip.hasTrip)
                assertNull(model.storageError)
                assertEquals(TripPhase.DRIVING, model.trip.phase)
                assertEquals(1, model.trip.ledger.size)
                assertEquals(listOf("child-a"), model.trip.onboardChildren.map { it.id })
                assertNull(model.trip.unlockConfirmation)
                expected = model.trip
            }
            closeActivity()
            // Persist the captured unfinished checkpoint through the production AtomicFile store.
            TripStore(context).save(expected)
            assertEquals(expected, TripStore(context).load())
            val progress = JSONObject().put("status", "PREPARED").put("runId", original.getString("runId"))
                .put("preparePid", Process.myPid()).put("uid", Process.myUid()).put("tripId", tripId)
                .put("expectedLedger", ledger(expected)).put("preparedAtMillis", System.currentTimeMillis())
            writeAtomic(File(backupDirectory, "progress.json"), progress)
            report.put("status", "PREPARED_AWAITING_HOST_FORCE_STOP").put("runId", original.getString("runId"))
                .put("checkpoint", checkpoint(expected))
                .put("backupDirectory", backupDirectory.path)
                .put("boundary", "One test boarding record written directly to the trip file; no door confirmation or SDK control was created.")
            writeAtomic(File(context.filesDir, "process-restore-prepare.json"), report)
            prepared = true
        } catch (failure: Throwable) {
            report.put("status", "PREPARE_FAILED").put("error", failure.toString())
            throw failure
        } finally {
            if (!prepared) {
                try { closeActivity() } finally {
                    try {
                        restoreOriginal(original)
                        report.put("originalRestored", true)
                    } catch (restoreFailure: Throwable) {
                        report.put("originalRestored", false).put("restoreError", restoreFailure.toString())
                        throw restoreFailure
                    } finally { writeAtomic(File(context.filesDir, "process-restore-prepare.json"), report) }
                }
            }
        }
    }

    private fun verifyAndRestore() {
        check(backupDirectory.isDirectory) { "Missing prepare backup; no app state changed" }
        val original = readAtomic(File(backupDirectory, "original.json"))
        val report = JSONObject().put("stage", "verify").put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("preparePid", original.getInt("preparePid")).put("runId", original.getString("runId"))
        try {
            val progress = readAtomic(File(backupDirectory, "progress.json"))
            assertEquals("PREPARED", progress.getString("status"))
            assertEquals(original.getString("runId"), progress.getString("runId"))
            assertEquals(original.getInt("uid"), Process.myUid())
            assertNotEquals("Host must force-stop and run verify in a new process", original.getInt("preparePid"), Process.myPid())
            val savedBeforeLaunch = TripStore(context).load()!!
            assertEquals(progress.getString("tripId"), savedBeforeLaunch.tripId)
            assertEquals(IntegrationMode.SDK, savedBeforeLaunch.integrationMode)
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity!!.onActivity { screen ->
                val model = screen.model
                assertNull(model.storageError)
                assertEquals(progress.getString("tripId"), model.trip.tripId)
                assertEquals(IntegrationMode.SDK, model.trip.integrationMode)
                assertTrue(model.trip.hasTrip)
                assertEquals(TripPhase.DRIVING, model.trip.phase)
                assertEquals(1, model.trip.ledger.size)
                assertEquals(progress.getJSONArray("expectedLedger").toString(), ledger(model.trip).toString())
                assertEquals(Attendance.ONBOARD, model.trip.children.single { it.id == "child-a" }.status)
                assertEquals(1, model.trip.onboardChildren.size)
                assertNull(model.trip.endRecord)
                assertNull(model.trip.unlockConfirmation)
                assertNull(model.trip.manualSurroundingsCheckAtMillis)
                report.put("restoredCheckpoint", checkpoint(model.trip))
                    .put("connected", model.vehicle.connected).put("unlockConfirmationPresent", model.trip.unlockConfirmation != null)
            }
            report.put("status", "NEW_PROCESS_UNFINISHED_CHECKPOINT_OBSERVED")
        } catch (failure: Throwable) {
            report.put("status", "VERIFY_FAILED").put("error", failure.toString())
            throw failure
        } finally {
            try { closeActivity() } finally {
                try {
                    restoreOriginal(original)
                    report.put("originalRestored", true)
                    // Keep the backup as evidence. Future prepare calls refuse to overwrite it.
                    writeAtomic(File(backupDirectory, "restored.json"), JSONObject().put("restoredAtMillis", System.currentTimeMillis()).put("pid", Process.myPid()))
                } catch (restoreFailure: Throwable) {
                    report.put("originalRestored", false).put("restoreError", restoreFailure.toString())
                    throw restoreFailure
                } finally {
                    report.put("finishedAtMillis", System.currentTimeMillis())
                    writeAtomic(File(context.filesDir, "process-restore-verify.json"), report)
                }
            }
        }
    }

    private fun captureOriginal(original: JSONObject) {
        val files = JSONObject()
        // Capture all bytes before SharedPreferences can recover its own .bak file.
        protectedFiles.forEach { (key, file) ->
            val entry = JSONObject().put("exists", file.exists())
            if (file.exists()) entry.put("bytes", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            files.put(key, entry)
        }
        original.put("files", files)
        val values = JSONObject()
        preferences.all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> entry.put("type", "String").put("value", value)
                is Boolean -> entry.put("type", "Boolean").put("value", value)
                is Int -> entry.put("type", "Int").put("value", value)
                is Long -> entry.put("type", "Long").put("value", value)
                is Float -> entry.put("type", "Float").put("value", value.toString())
                is Set<*> -> entry.put("type", "Set").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> error("Unsupported preference type: $key")
            }
            values.put(key, entry)
        }
        original.put("preferenceValues", values)
    }

    private fun restoreOriginal(original: JSONObject) {
        // If capture failed before any app mutation there may be no completed file snapshot.
        val files = original.optJSONObject("files") ?: return
        val failures = mutableListOf<Throwable>()
        runCatching { original.optJSONObject("preferenceValues")?.let { values ->
            val editor = preferences.edit().clear()
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                when (entry.getString("type")) {
                    "String" -> editor.putString(key, entry.getString("value"))
                    "Boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "Int" -> editor.putInt(key, entry.getInt("value"))
                    "Long" -> editor.putLong(key, entry.getLong("value"))
                    "Float" -> editor.putFloat(key, entry.getString("value").toFloat())
                    "Set" -> { val array = entry.getJSONArray("value"); editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet()) }
                }
            }
            check(editor.commit()) { "Location-sharing preferences restore failed; backup retained" }
        } }.onFailure(failures::add)
        protectedFiles.forEach { (key, file) ->
            runCatching {
            val entry = files.getJSONObject(key)
            if (entry.getBoolean("exists")) {
                val bytes = Base64.decode(entry.getString("bytes"), Base64.NO_WRAP)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                check(file.readBytes().contentEquals(bytes)) { "Original file verification failed: $key" }
            } else check(!file.exists() || file.delete()) { "Original absence restore failed: $key" }
            }.onFailure(failures::add)
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Original restore had ${failures.size} failure(s); backup retained").also { error -> failures.forEach(error::addSuppressed) }
    }

    private fun closeActivity() { activity?.close(); activity = null; instrumentation.waitForIdleSync() }
    private fun writeAtomic(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(json.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun readAtomic(file: File) = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    private fun ledger(state: TripState) = JSONArray(state.ledger.map { entry ->
        JSONObject().put("id", entry.id).put("childId", entry.childId).put("before", entry.before.name).put("after", entry.after.name)
            .put("recordedAtMillis", entry.recordedAtMillis).put("reason", entry.reason).put("source", entry.source.name)
    })
    private fun checkpoint(state: TripState) = JSONObject().put("tripId", state.tripId).put("phase", state.phase.name)
        .put("mode", state.integrationMode.name).put("ledger", ledger(state)).put("onboardCount", state.onboardChildren.size)
        .put("endRecordPresent", state.endRecord != null).put("unlockConfirmationPresent", state.unlockConfirmation != null)
}
