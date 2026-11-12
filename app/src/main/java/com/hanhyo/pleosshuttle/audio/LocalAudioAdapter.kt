package com.hanhyo.pleosshuttle.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.hanhyo.pleosshuttle.R
import java.util.Locale

enum class SpeechStatus { IDLE, INITIALIZING, SPEAKING, COMPLETED, STOPPED, FAILED }

data class AudioSnapshot(
    val trackLabel: String = "직접 생성한 시험 멜로디 — 동요 음원 아님",
    val musicState: String = "정지",
    val ttsState: String = "안내 음성 대기 · Pleos 기기 내 한국어",
    val speechStatus: SpeechStatus = SpeechStatus.IDLE,
    val speechRequestId: Long? = null,
    val ttsBackend: String = "Pleos ON_DEVICE · ko-KR",
    val focusState: String = "포커스 요청 전",
    val isPlaying: Boolean = false,
    val volume: Float = 1f,
    val playbackPositionMillis: Int = 0,
    val durationMillis: Int = 0,
    // A routed output is observable only while playing; it does not prove audible sound.
    val outputRouteLabel: String? = null,
    val observedAtMillis: Long = System.currentTimeMillis(),
    val events: List<String> = emptyList(),
)

/** Local-only media; speech engine results never modify the music capability result. */
class LocalAudioAdapter(
    context: Context,
    private val onChange: (AudioSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private val policy = AudioFocusPolicy()
    private var player: MediaPlayer? = null
    private var closed = false
    private var musicFocusRequested = false
    private var probeRequested = false
    private var interactionRevision = 0L
    private val speechGate = SpeechRequestGate()
    private var voice: VoiceHandle? = null
    private var speechTimeout: Runnable? = null

    private class VoiceHandle(val id: Long, val engine: PleosTextToSpeech) {
        var initialized = false
        var listener: EventListener? = null
        var focus: AudioFocusRequest? = null
    }

    @Volatile var snapshot = AudioSnapshot()
        private set

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val musicFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAttributes)
        .setAcceptsDelayedFocusGain(false)
        // Android may duck in the mixer without delivering CAN_DUCK to the app.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> handleMusicFocus(change) }, main)
        .build()
    private var probeFocus: AudioFocusRequest? = null

    fun onForeground() = onMain {
        policy.foreground()
        event("포그라운드 복귀 · 자동 재생 없음")
    }

    fun refresh() = onMain {
        refreshPlaybackState()
        snapshot = snapshot.copy(observedAtMillis = System.currentTimeMillis())
        onChange(snapshot)
    }

    fun play() = onMain {
        interactionRevision++
        speechGate.clearResumeIntent()
        if (!policy.foreground) {
            event("재생 차단 · 앱이 백그라운드 상태")
            return@onMain
        }
        cancelVoice("음악 재생 선택으로 안내 음성을 중단했습니다", resume = false)
        if (policy.playback == AudioFocusPolicy.Playback.PLAYING) return@onMain
        try {
            ensurePlayer()
            val result = manager.requestAudioFocus(musicFocus)
            musicFocusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            snapshot = snapshot.copy(focusState = "음악 포커스 요청 결과 $result")
            if (!musicFocusRequested) {
                policy.pause()
                applyPlayback()
                event("음악 재생 보류 · 오디오 포커스 미수락")
                return@onMain
            }
            policy.playGranted()
            applyPlayback()
            event("MediaPlayer 재생 시작 · 청취 확인은 별도")
        } catch (error: Exception) {
            failMusic(error)
        }
    }

    fun pause() = onMain {
        interactionRevision++
        speechGate.clearResumeIntent()
        policy.pause()
        applyPlayback()
        abandonMusicFocus()
        event("사용자 음악 일시정지")
    }

    fun stop() = onMain {
        interactionRevision++
        speechGate.clearResumeIntent()
        policy.stop()
        applyPlayback()
        abandonMusicFocus()
        event("사용자 음악 정지 · 다음 재생은 처음부터")
    }

    fun onBackground() = onMain {
        interactionRevision++
        speechGate.clearResumeIntent()
        cancelVoice("앱을 벗어나 안내 음성을 중단했습니다", resume = false)
        policy.background()
        applyPlayback()
        abandonMusicFocus()
        releaseProbe()
        event("백그라운드 전환 · 음악 일시정지와 복귀 의도 취소")
    }

    /** Pleos public ON_DEVICE synthesis only: no network voice or Android-engine success inferred. */
    fun speak(text: String) = onMain {
        if (!policy.foreground || speechGate.active != null) {
            event("안내 음성 요청 생략 · 백그라운드 또는 이전 요청 진행 중")
            return@onMain
        }
        if (text.isBlank()) {
            snapshot = snapshot.copy(ttsState = "안내할 문장이 없습니다", speechStatus = SpeechStatus.FAILED)
            event("빈 안내 문장 요청 생략")
            return@onMain
        }
        val id = checkNotNull(speechGate.begin(SystemClock.elapsedRealtime()))
        snapshot = snapshot.copy(ttsState = "기기 내 한국어 음성 준비 중", speechStatus = SpeechStatus.INITIALIZING, speechRequestId = id)
        event("Pleos ON_DEVICE 초기화 요청 · ko-KR · 요청 $id")
        scheduleVoiceTimeout(id)
        try {
            val handle = VoiceHandle(id, PleosTextToSpeech(appContext, Mode.ON_DEVICE))
            voice = handle
            handle.engine.initialize(object : OnInitializeListener {
                override fun onSuccess() = voiceCallback(id) {
                    handle.initialized = true
                    prepareVoice(handle, text)
                }
                override fun onFailure(errorType: ErrorType) = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.FAILED, "음성 준비 실패 · $errorType · 다시 눌러 재시도", resume = true)
                }
            })
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "음성 준비 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도", resume = true)
        }
    }

    fun stopSpeech() = onMain {
        cancelVoice("안내 음성을 중단했습니다", resume = true)
    }

    private fun prepareVoice(handle: VoiceHandle, text: String) {
        val id = handle.id
        if (!speechGate.accepts(id) || voice !== handle || speechGate.active?.phase != SpeechRequestGate.Phase.INITIALIZING) return
        try {
            val listener = object : EventListener {
                override fun onReady() = voiceCallback(id) { event("Pleos ON_DEVICE onReady · 요청 $id") }
                override fun onStart() = voiceCallback(id) {
                    snapshot = snapshot.copy(ttsState = "안내 중 · Pleos 기기 내 한국어", speechStatus = SpeechStatus.SPEAKING)
                    event("Pleos ON_DEVICE onStart · 실제 청취는 별도 · 요청 $id")
                }
                override fun onDone() = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.COMPLETED, "안내 완료 · Pleos 기기 내 한국어", resume = true)
                }
                override fun onStop() = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.STOPPED, "음성 엔진이 안내를 중단했습니다", resume = true)
                }
                override fun onError(errorType: ErrorType) = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.FAILED, "안내 음성 실패 · $errorType · 다시 눌러 재시도", resume = true)
                }
                override fun onUpdatedRms(rms: Double) = Unit
            }
            handle.engine.addEventListener(listener)
            handle.listener = listener
            handle.engine.setLocale(Locale.KOREA)
            handle.engine.setAudioAttributes(speechAttributes)
            startVoice(handle, text)
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 설정 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도", resume = true)
        }
    }

    private fun startVoice(handle: VoiceHandle, text: String) {
        val id = handle.id
        if (!policy.foreground || voice !== handle || !speechGate.start(id, SystemClock.elapsedRealtime(),
                policy.playback == AudioFocusPolicy.Playback.PLAYING, interactionRevision)) return
        // Keep music playing during initialization; pause only when this request is ready to speak.
        policy.pause()
        applyPlayback()
        abandonMusicFocus()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) voiceCallback(id) {
                    finishVoice(id, SpeechStatus.STOPPED, "다른 소리의 요청으로 안내를 중단했습니다", resume = false)
                }
            }, main).build()
        handle.focus = focus
        val result = runCatching { manager.requestAudioFocus(focus) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 재생 권한을 얻지 못했습니다 · 다시 눌러 재시도", resume = true)
            return
        }
        if (!speechGate.accepts(id)) return
        scheduleVoiceTimeout(id)
        snapshot = snapshot.copy(ttsState = "안내 음성 요청 · 시작 대기")
        event("Pleos ON_DEVICE speak · ko-KR · isFinal=true · 요청 $id · 발화 내용은 로그에 저장하지 않음")
        try {
            handle.engine.speak(text, true)
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 요청 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도", resume = true)
        }
    }

    private fun voiceCallback(id: Long, action: () -> Unit) {
        // Always queue SDK callbacks, including synchronous ones, so handle assignment and cleanup
        // finish before a callback can act. Each fresh SDK instance captures its request identity.
        main.post { if (!closed && policy.foreground && speechGate.accepts(id)) action() }
    }

    private fun scheduleVoiceTimeout(id: Long) {
        speechTimeout?.let(main::removeCallbacks)
        val request = speechGate.active?.takeIf { it.id == id } ?: return
        val timeout = Runnable {
            if (!closed && speechGate.timedOut(id, SystemClock.elapsedRealtime())) {
                val step = if (speechGate.active?.phase == SpeechRequestGate.Phase.INITIALIZING) "음성 준비" else "안내 음성"
                finishVoice(id, SpeechStatus.FAILED, "$step 응답 시간 초과 · 다시 눌러 재시도", resume = true)
            }
        }
        speechTimeout = timeout
        main.postDelayed(timeout, (request.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun cancelVoice(message: String, resume: Boolean) {
        speechGate.active?.id?.let { finishVoice(it, SpeechStatus.STOPPED, message, resume) }
    }

    private fun finishVoice(id: Long, status: SpeechStatus, message: String, resume: Boolean) {
        val completion = speechGate.finish(id, interactionRevision, policy.foreground, resume) ?: return
        // Invalidate first: stop/release/focus callbacks must not complete or resume another request.
        val handle = voice?.takeIf { it.id == id }
        voice = null
        speechTimeout?.let(main::removeCallbacks)
        speechTimeout = null
        snapshot = snapshot.copy(ttsState = message, speechStatus = status)
        handle?.let {
            it.focus?.let { focus -> runCatching { manager.abandonAudioFocusRequest(focus) } }
            if (it.initialized) runCatching { it.engine.stop() }.onFailure { error -> event("음성 정리 stop 오류 ${error.javaClass.simpleName}") }
            it.listener?.let { listener -> runCatching { it.engine.removeEventListener(listener) }.onFailure { error -> event("음성 리스너 정리 오류 ${error.javaClass.simpleName}") } }
            runCatching { it.engine.release() }.onFailure { error -> event("음성 엔진 정리 오류 ${error.javaClass.simpleName}") }
        }
        event("$message · 요청 $id")
        if (completion.resumeMusic) play()
    }

    /** A real OS focus request for diagnostics, not recorded navigation speech. */
    fun beginFocusProbe(mayDuck: Boolean) = onMain {
        if (!policy.foreground || probeRequested) return@onMain
        val gain = if (mayDuck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(speechAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> event("시험 포커스 소유자 콜백 $change") }, main)
            .build()
        val result = runCatching { manager.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        probeRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        probeFocus = request.takeIf { probeRequested }
        event("시험 포커스 요청 $gain 결과 $result · 내비 음성 아님")
    }

    fun endFocusProbe() = onMain {
        releaseProbe()
        event("시험 포커스 반환 · 음악 콜백/볼륨/재생 상태를 별도 확인")
    }

    fun close() = onMain {
        interactionRevision++
        speechGate.clearResumeIntent()
        cancelVoice("앱 종료로 안내 음성을 중단했습니다", resume = false)
        policy.stop()
        applyPlayback()
        abandonMusicFocus()
        releaseProbe()
        closed = true
    }

    private fun ensurePlayer() {
        if (player != null) return
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(mediaAttributes)
            appContext.resources.openRawResourceFd(R.raw.test_melody).use { file ->
                created.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            created.isLooping = true
            created.setOnErrorListener { _, what, extra ->
                onMain {
                    policy.stop()
                    player?.release()
                    player = null
                    abandonMusicFocus()
                    snapshot = snapshot.copy(musicState = "오류 · MediaPlayer $what/$extra", isPlaying = false)
                    refreshPlaybackState()
                    event("MediaPlayer 오류 $what/$extra")
                }
                true
            }
            created.prepare()
            player = created
        } catch (error: Exception) {
            created.release()
            throw error
        }
    }

    private fun handleMusicFocus(change: Int) {
        if (closed || !musicFocusRequested) return
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusPolicy.Change.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusPolicy.Change.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusPolicy.Change.TRANSIENT_LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusPolicy.Change.CAN_DUCK
            else -> return
        }
        policy.changed(mapped)
        if (mapped == AudioFocusPolicy.Change.LOSS) abandonMusicFocus()
        snapshot = snapshot.copy(focusState = "실제 오디오 포커스 콜백 $mapped")
        applyPlayback()
        event("음악 포커스 $mapped · 앱 음량 ${policy.volume}")
    }

    private fun applyPlayback() {
        try {
            when (policy.playback) {
                AudioFocusPolicy.Playback.PLAYING -> {
                    player?.setVolume(policy.volume, policy.volume)
                    if (player?.isPlaying == false) player?.start()
                }
                AudioFocusPolicy.Playback.PAUSED -> if (player?.isPlaying == true) player?.pause()
                AudioFocusPolicy.Playback.STOPPED -> {
                    player?.release()
                    player = null
                }
            }
            snapshot = snapshot.copy(
                musicState = when (policy.playback) {
                    AudioFocusPolicy.Playback.PLAYING -> "재생 중 · 생성 시험 멜로디"
                    AudioFocusPolicy.Playback.PAUSED -> if (policy.resumeOnGain) "포커스 일시정지 · 복귀 대기" else "일시정지"
                    AudioFocusPolicy.Playback.STOPPED -> "정지"
                },
                volume = policy.volume,
            )
            refreshPlaybackState()
        } catch (error: Exception) {
            failMusic(error)
        }
    }

    private fun refreshPlaybackState() {
        val currentPlayer = player
        val playing = runCatching { currentPlayer?.isPlaying == true }.getOrDefault(false)
        val route = if (playing) {
            runCatching { currentPlayer?.routedDevice?.let(::outputRouteLabel) }.getOrNull()
        } else null
        snapshot = snapshot.copy(
            isPlaying = playing,
            playbackPositionMillis = runCatching { currentPlayer?.currentPosition ?: 0 }.getOrDefault(0),
            durationMillis = runCatching { currentPlayer?.duration ?: 0 }.getOrDefault(0),
            outputRouteLabel = route,
        )
    }

    private fun outputRouteLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUS -> "차량 미디어 출력" + device.address.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "기기 스피커"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 출력"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 이어폰 출력"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 오디오 출력"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI 오디오 출력"
        else -> "Android 오디오 출력"
    }

    private fun abandonMusicFocus() {
        musicFocusRequested = false
        manager.abandonAudioFocusRequest(musicFocus)
    }

    private fun releaseProbe() {
        probeFocus?.let { manager.abandonAudioFocusRequest(it) }
        probeFocus = null
        probeRequested = false
    }

    private fun failMusic(error: Exception) {
        policy.stop()
        player?.release()
        player = null
        abandonMusicFocus()
        snapshot = snapshot.copy(musicState = "오류 · ${error.javaClass.simpleName}", isPlaying = false)
        refreshPlaybackState()
        event("음악 오류 ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun event(message: String) {
        val now = System.currentTimeMillis()
        snapshot = snapshot.copy(observedAtMillis = now, events = (snapshot.events + "$now $message").takeLast(100))
        onChange(snapshot)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) action()
        } else main.post { if (!closed) action() }
    }
}
