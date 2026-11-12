package com.hanhyo.pleosshuttle.audio

import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech as AndroidTextToSpeech
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.MainActivity
import com.hanhyo.pleosshuttle.TripStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Bounded real ON_DEVICE synthesis; a completion callback is not a human hearing check. */
@RunWith(AndroidJUnit4::class)
class PleosTtsRuntimeDeviceTest {
    @get:org.junit.Rule val isolatedTrip = com.hanhyo.pleosshuttle.IsolatedTripRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.filesDir, "pleos-tts-runtime.json")

    @Test fun `Pleos_온디바이스_한국어_단문은_초기화와_합성완료_콜백을_반환한다`() {
        val events = CopyOnWriteArrayList<JSONObject>()
        fun event(name: String, detail: String? = null) {
            events.add(JSONObject().put("atMillis", System.currentTimeMillis())
                .put("event", name).put("detail", detail ?: JSONObject.NULL))
        }
        val report = JSONObject()
            .put("startedAtMillis", System.currentTimeMillis())
            .put("scope", "Public Pleos TextToSpeech ON_DEVICE, Locale.KOREA, one complete sentence; no server registration, vehicle control, or trip actions")
            .put("audibleOutputVerified", false)
            .put("fingerprint", Build.FINGERPRINT)
            .put("mode", "ON_DEVICE").put("locale", Locale.KOREA.toLanguageTag())
            .put("text", "다음 지점은 서울숲입니다.").put("isFinal", true)
            .put("ttsPermissionGranted", context.checkSelfPermission("pleos.car.permission.TTS_SERVICE") == PackageManager.PERMISSION_GRANTED)
        val exactAvd = Build.FINGERPRINT == VERIFIED_FINGERPRINT && Build.HARDWARE == "ranchu" && Build.PRODUCT == "crp_avd_arm64"
        if (!exactAvd) {
            report.put("result", "SKIPPED_NOT_VERIFIED_PLEOS_AVD")
            evidence.writeText(report.toString(2))
            assumeTrue("Exact verified Pleos AVD required; no synthesis requested", false)
        }
        val beforeTrip = TripStore(context).load()
        val initResult = AtomicReference("NOT_STARTED")
        val synthesisResult = AtomicReference("NOT_REQUESTED")
        val initLatch = CountDownLatch(1)
        val terminalLatch = CountDownLatch(1)
        val rmsCount = AtomicInteger()
        val finiteRms = CopyOnWriteArrayList<Double>()
        var scenario: ActivityScenario<MainActivity>? = null
        var sdk: PleosTextToSpeech? = null
        var androidTts: AndroidTextToSpeech? = null
        var listenerAdded = false
        var thrown: Throwable? = null
        val listener = object : EventListener {
            override fun onReady() { event("onReady") }
            override fun onStart() { event("onStart") }
            override fun onDone() {
                event("onDone")
                synthesisResult.compareAndSet("REQUESTED", "DONE")
                terminalLatch.countDown()
            }
            override fun onStop() {
                event("onStop")
                if (synthesisResult.compareAndSet("REQUESTED", "STOPPED")) terminalLatch.countDown()
            }
            override fun onError(errorType: ErrorType) {
                event("onError", errorType.toString())
                synthesisResult.compareAndSet("REQUESTED", "ERROR:$errorType")
                terminalLatch.countDown()
            }
            override fun onUpdatedRms(rms: Double) {
                val count = rmsCount.incrementAndGet()
                // Keep bounded scalar samples, never capture microphone or PCM output.
                if (count <= 100 && rms.isFinite()) finiteRms.add(rms)
            }
        }
        try {
            report.put("androidServiceQuery", JSONObject()
                .put("flags0", queryAndroidEngines(0))
                .put("matchDefaultOnly", queryAndroidEngines(PackageManager.MATCH_DEFAULT_ONLY)))
            scenario = ActivityScenario.launch(MainActivity::class.java)

            // Compare the framework's default engine discovery separately; do not ask it to speak.
            val androidInitLatch = CountDownLatch(1)
            val androidInitResult = AtomicReference<Int?>(null)
            checkNotNull(scenario).onActivity { activity ->
                androidTts = AndroidTextToSpeech(activity.applicationContext) { status ->
                    androidInitResult.set(status)
                    event("androidDefaultInit", status.toString())
                    androidInitLatch.countDown()
                }
            }
            val androidCallback = androidInitLatch.await(5, TimeUnit.SECONDS)
            checkNotNull(scenario).onActivity {
                report.put("androidDefault", JSONObject()
                    .put("callbackReceived", androidCallback)
                    .put("initStatus", androidInitResult.get() ?: JSONObject.NULL)
                    .put("defaultEngine", androidTts?.defaultEngine ?: JSONObject.NULL))
                androidTts?.shutdown()
                androidTts = null
                sdk = PleosTextToSpeech(context.applicationContext, Mode.ON_DEVICE)
                initResult.set("REQUESTED")
                sdk!!.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        initResult.set("SUCCESS")
                        event("initialize.onSuccess")
                        initLatch.countDown()
                    }
                    override fun onFailure(errorType: ErrorType) {
                        initResult.set("ERROR:$errorType")
                        event("initialize.onFailure", errorType.toString())
                        initLatch.countDown()
                    }
                })
            }
            if (!initLatch.await(10, TimeUnit.SECONDS)) initResult.set("TIMEOUT_10_SECONDS")
            if (initResult.get() == "SUCCESS") {
                checkNotNull(scenario).onActivity {
                    sdk!!.addEventListener(listener)
                    listenerAdded = true
                    sdk!!.setLocale(Locale.KOREA)
                    sdk!!.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    synthesisResult.set("REQUESTED")
                    event("speak.request", "ko-KR; isFinal=true")
                    sdk!!.speak("다음 지점은 서울숲입니다.", true)
                }
                if (!terminalLatch.await(15, TimeUnit.SECONDS)) {
                    synthesisResult.compareAndSet("REQUESTED", "TIMEOUT_15_SECONDS")
                }
            }
        } catch (failure: Throwable) {
            thrown = failure
            report.put("harnessError", failure.toString())
        } finally {
            val cleanupErrors = JSONArray()
            instrumentation.runOnMainSync {
                runCatching { androidTts?.shutdown() }.onFailure { cleanupErrors.put("Android shutdown: $it") }
                runCatching { sdk?.stop() }.onFailure { cleanupErrors.put("Pleos stop: $it") }
                if (listenerAdded) runCatching { sdk?.removeEventListener(listener) }.onFailure { cleanupErrors.put("Pleos remove listener: $it") }
                runCatching { sdk?.release() }.onFailure { cleanupErrors.put("Pleos release: $it") }
            }
            runCatching { scenario?.close() }.onFailure { cleanupErrors.put("Activity close: $it") }
            val afterTrip = runCatching { TripStore(context).load() }.getOrNull()
            val tripPreserved = beforeTrip == null || (afterTrip != null && afterTrip.tripId == beforeTrip.tripId &&
                afterTrip.children == beforeTrip.children && afterTrip.ledger == beforeTrip.ledger && afterTrip.phase == beforeTrip.phase)
            report.put("finishedAtMillis", System.currentTimeMillis())
                .put("initializeResult", initResult.get()).put("synthesisResult", synthesisResult.get())
                .put("events", JSONArray(events)).put("rmsCallbackCount", rmsCount.get())
                .put("finiteRmsSamples", JSONArray(finiteRms)).put("cleanupErrors", cleanupErrors)
                .put("existingTripPreserved", tripPreserved)
                .put("existingLedgerEntries", beforeTrip?.ledger?.size ?: JSONObject.NULL)
                .put("afterLedgerEntries", afterTrip?.ledger?.size ?: JSONObject.NULL)
                .put("result", if (thrown == null && initResult.get() == "SUCCESS" && synthesisResult.get() == "DONE" && tripPreserved && cleanupErrors.length() == 0) "CALLBACKS_VERIFIED_HEARING_SEPARATE" else "FAILED")
            evidence.writeText(report.toString(2))
        }
        thrown?.let { throw it }
        assertEquals(report.toString(2), "SUCCESS", initResult.get())
        assertEquals(report.toString(2), "DONE", synthesisResult.get())
        assertTrue(report.toString(2), report.getBoolean("existingTripPreserved"))
        assertEquals(report.toString(2), 0, report.getJSONArray("cleanupErrors").length())
    }

    @Suppress("DEPRECATION")
    private fun queryAndroidEngines(flags: Int): JSONArray = JSONArray(
        context.packageManager.queryIntentServices(Intent(AndroidTextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), flags)
            .map { item -> JSONObject().put("package", item.serviceInfo.packageName)
                .put("service", item.serviceInfo.name).put("isDefault", item.isDefault)
                .put("permission", item.serviceInfo.permission ?: JSONObject.NULL) }
    )

    private companion object {
        const val VERIFIED_FINGERPRINT = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
    }
}
