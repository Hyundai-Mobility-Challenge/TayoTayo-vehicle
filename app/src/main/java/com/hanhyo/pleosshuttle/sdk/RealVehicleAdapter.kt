package com.hanhyo.pleosshuttle.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/** Only public SDK APIs, read-only: observes speed·gear·parking brake·door lock·belts. Never writes vehicle state (no door control). */
class RealVehicleAdapter(context: Context, private val onUpdate: (VehicleSnapshot) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val capabilityPreferences = app.getSharedPreferences("sdk_capabilities", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private val aaosConnection = AaosConnectionFence<CarPropertyManager>()
    private val properties: CarPropertyManager? get() = aaosConnection.manager
    private var uxManager: CarUxRestrictionsManager? = null
    private var running = false
    private var closed = false
    private var epoch = 0L
    private var subscriptionSerial = 0L
    private var selectedDoorCapability: ExactSupportedArea<DoorArea>? = null
    private var doorListener: DoorLockStateListener? = null
    private var telltaleListener: TelltaleInfoListener? = null
    /** 경고등 구독을 시도한 세대인지. listener 등록이 권한 등으로 실패해도 주기 조회(getTelltalesInfo)는 따로 시도한다. */
    private var telltaleWatched = false
    private val cleanups = mutableListOf<() -> Unit>()
    private val beltAreas = mutableMapOf<String, SeatArea>()
    private val inflight = mutableSetOf<String>()
    private val lastLoggedObservations = mutableMapOf<String, String>()
    private class PendingAaosRead(
        val capture: AaosConnectionFence.Capture<CarPropertyManager>,
        val epoch: Long,
        val after: (() -> Unit)?,
        val onRejected: ((String) -> Unit)?,
        var completed: Boolean = false,
    )
    private var pendingAaosRead: PendingAaosRead? = null
    private val aaosReadPending: Boolean get() = pendingAaosRead != null
    private var snapshot = VehicleSnapshot(parkingBrakeSupported = capabilityPreferences.getBoolean("parking_brake_supported", false), versions = mapOf(
        "Vehicle SDK" to "2.3.2", "Vehicle Service" to packageVersion("ai.pleos.playground.service.vehicle"),
        "Android" to "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
        "Build fingerprint" to Build.FINGERPRINT, "Hardware" to Build.HARDWARE,
    ))
    val current: VehicleSnapshot get() = snapshot

    fun initialize() = onStart()
    fun onStart() {
        if (closed || running) return
        running = true
        val startEpoch = ++epoch
        try {
            val v = Vehicle(app)
            vehicle = v
            v.initialize()
            log("Vehicle.initialize returned; connection is confirmed by individual responses")
            discoverCapabilities(startEpoch)
        } catch (e: Exception) { log("Vehicle.initialize ERROR ${e.javaClass.simpleName}: ${e.message}") }
        try {
            car = Car.createCar(app, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                handler.post {
                    if (!running || epoch != startEpoch) return@post
                    clearAaosConnection(if (ready) "AAOS 연결 세대 변경 · 최신 조회 필요" else "AAOS 서비스 연결 해제")
                    if (!ready) {
                        publish()
                    } else {
                        try {
                            aaosConnection.replace(connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)
                            val readyGeneration = aaosConnection.generation
                            val readyProperties = properties
                            val readyUxManager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            uxManager = readyUxManager
                            readyUxManager?.registerListener { restrictions -> handler.post {
                                if (running && epoch == startEpoch && aaosConnection.generation == readyGeneration && properties === readyProperties && uxManager === readyUxManager) {
                                    val restricted = restrictions.isRequiresDistractionOptimization
                                    snapshot = snapshot.copy(uxRestricted = observed(restricted, "AAOS UX 제한"), activeUxRestrictions = restrictions.activeRestrictions)
                                    publish()
                                }
                            } }
                            readyUxManager?.currentCarUxRestrictions?.let { restrictions ->
                                snapshot = snapshot.copy(uxRestricted = observed(restrictions.isRequiresDistractionOptimization, "AAOS UX 제한 조회"), activeUxRestrictions = restrictions.activeRestrictions)
                            }
                        } catch (e: Exception) { snapshot = snapshot.copy(uxRestricted = failure(e, "AAOS UX 제한")) }
                        refresh()
                    }
                }
            }
        } catch (e: Exception) { log("AAOS 연결 ERROR ${e.javaClass.simpleName}: ${e.message}") }
        catch (e: LinkageError) { log("AAOS 라이브러리 현재 환경 미지원: ${e.message}") }
        handler.post(tick)
        publish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }

    fun refresh() {
        if (!running) return
        refreshAaos()
        val v = vehicle ?: return
        val currentEpoch = epoch
        selectedDoorCapability?.let { selected ->
            read("door:${selected.key}", currentEpoch, { ok, fail -> v.getDoor().getDoorLockState(selected.original, ok, fail) }) { result ->
                updateDoor(result)
            }
        }
        beltAreas.toMap().forEach { (name, area) ->
            read("belt:$name", currentEpoch, { ok, fail -> v.getSeat().getCurrentSeatBeltBuckledState(area, ok, fail) }) { result ->
                snapshot = snapshot.copy(seatBelts = snapshot.seatBelts + (name to result)); publish()
            }
        }
        // V-06: 경고등은 바뀔 때만 listener가 오므로 다른 관측처럼 주기 조회로 신선도를 유지한다.
        if (telltaleWatched) {
            // 원본 경고등 목록은 넘기지 않고 SeatBelt 상태만 꺼내 관측·진단 로그에 남긴다.
            read<BeltTelltaleState>("telltale:SeatBelt", currentEpoch, { ok, fail -> v.getSafety().getTelltalesInfo({ ok(it.seatBeltState()) }, fail) }) { result ->
                updateBeltTelltale(result)
            }
        }
    }

    private fun discoverCapabilities(startEpoch: Long) {
        val v = vehicle ?: return
        try {
            v.getDoor().checkDoorLockCapabilities(onComplete = { config, areas -> handler.post {
                if (!running || epoch != startEpoch) return@post
                val supported = exactSupportedAreas(areas) { it.values.map { value -> value.name } }
                val selected = selectExactArea(supported, DoorValue.ROW_2_RIGHT.name)
                selectedDoorCapability = selected
                snapshot = snapshot.copy(supportedDoorAreas = supported.map { it.key }, selectedDoorArea = selected?.key)
                log("Door capability access=${config.accessType} originalAreas=$areas exactGroups=${supported.map { it.key }}; selectedExactGroup=${selected?.key}; all members of selected group share the request")
                if (selected != null) registerDoor(startEpoch, selected)
                else snapshot = snapshot.copy(doorLocked = SdkObservation(outcome = SdkOutcome.UNSUPPORTED, error = "ROW_2_RIGHT를 포함하는 원본 SDK Area 미지원"))
                refresh()
            } }, onFailed = { e -> handler.post { if (running && epoch == startEpoch) { snapshot = snapshot.copy(doorLocked = failure(e, "Vehicle capability")); log("Door capability ERROR $e") } } })
            // V-07 삭제(D39): 좌석 점유(checkSeatOccupancyCapabilities/registerSeatOccupancy)는 구독하지 않는다.
            v.getSeat().checkSeatBeltBuckledCapabilities(onComplete = { _, areas -> handler.post {
                if (!running || epoch != startEpoch) return@post
                val supported = exactSupportedAreas(areas) { it.values.map { value -> value.name } }
                supported.forEach { beltAreas[it.key] = it.original }
                snapshot = snapshot.copy(supportedBeltAreas = supported.map { it.key })
                log("Seat belt capability originalAreas=$areas exactGroups=${snapshot.supportedBeltAreas}")
                beltAreas.forEach { (name, area) -> registerBelt(name, area, startEpoch) }
                refresh()
            } }, onFailed = { e -> handler.post { if (running && epoch == startEpoch) log("Belt capability ERROR $e") } })
        } catch (e: Exception) { log("Capability ERROR $e") }
        registerBeltTelltale(startEpoch)
    }

    /** V-06 차량 벨트 경고등(Safety.registerTelltales). 좌석 구분 없는 차량 전체 신호 · 메모리 관측만. */
    private fun registerBeltTelltale(startEpoch: Long) {
        if (telltaleWatched) return
        val safety = vehicle?.getSafety() ?: return
        telltaleWatched = true
        val listener = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(value: Telltales?) = dispatch(startEpoch) { updateBeltTelltale(observed(value.seatBeltState(), "실제 Vehicle SDK listener")) }
            override fun onFailed(exception: Exception) = dispatch(startEpoch) { updateBeltTelltale(failure(exception, "Vehicle SDK telltale listener")) }
        }
        try { safety.registerTelltales(listener); telltaleListener = listener; cleanups += { safety.unregisterTelltales(listener) }; log("SUBSCRIBE epoch=$startEpoch key=telltale/SeatBelt serial=${++subscriptionSerial} app call returned") }
        catch (e: Exception) { log("Telltale listener ERROR $e") }
    }
    private fun updateBeltTelltale(result: SdkObservation<BeltTelltaleState>) {
        recordObservation("Vehicle.telltale.SeatBelt", result)
        snapshot = snapshot.copy(seatBeltTelltale = result); publish()
    }

    private fun registerDoor(startEpoch: Long, selected: ExactSupportedArea<DoorArea>) {
        if (doorListener != null) return
        val door = vehicle?.getDoor() ?: return
        val listener = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(value: Boolean?) = dispatch(startEpoch) { updateDoor(observed(value, "실제 Vehicle SDK listener")) }
            override fun onFailed(exception: Exception) = dispatch(startEpoch) { updateDoor(failure(exception, "Vehicle SDK listener")) }
        }
        try { door.registerDoorLockState(selected.original, listener); doorListener = listener; cleanups += { door.unregisterDoorLockState(selected.original, listener) }; log("SUBSCRIBE epoch=$startEpoch key=door/${selected.key} serial=${++subscriptionSerial} app call returned") }
        catch (e: Exception) { log("Door listener ERROR $e") }
    }
    private fun registerBelt(name: String, area: SeatArea, startEpoch: Long) {
        val seat = vehicle?.getSeat() ?: return
        val listener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) = dispatch(startEpoch) { snapshot = snapshot.copy(seatBelts = snapshot.seatBelts + (name to observed(value, "실제 Vehicle SDK listener"))); publish() }
            override fun onFailed(exception: Exception) = dispatch(startEpoch) { snapshot = snapshot.copy(seatBelts = snapshot.seatBelts + (name to failure(exception, "Vehicle SDK listener"))); publish() }
        }
        try { seat.registerSeatBeltBuckledState(area, listener); cleanups += { seat.unregisterSeatBeltBuckledState(area, listener) }; log("SUBSCRIBE epoch=$startEpoch key=belt/$name serial=${++subscriptionSerial} app call returned") }
        catch (e: Exception) { log("Belt $name listener ERROR $e") }
    }

    private fun clearAaosConnection(reason: String) {
        aaosConnection.replace(null)
        snapshot = snapshot.copy(connected = false, speedMetersPerSecond = unavailable(reason), gear = unavailable(reason), parkingBrake = unavailable(reason), uxRestricted = unavailable(reason), activeUxRestrictions = null)
        pendingAaosRead?.let { rejectAaosRead(it, reason) }
        val previousUx = uxManager
        uxManager = null
        runCatching { previousUx?.unregisterListener() }.onFailure { log("UX listener cleanup ERROR $it") }
        log("AAOS connection generation=${aaosConnection.generation} invalidated: $reason")
    }

    private fun rejectAaosRead(read: PendingAaosRead, reason: String) {
        if (read.completed) return
        read.completed = true
        if (pendingAaosRead === read) pendingAaosRead = null
        read.onRejected?.invoke(reason)
    }

    private fun refreshAaos(onRejected: ((String) -> Unit)? = null, after: (() -> Unit)? = null) {
        if (aaosReadPending) { onRejected?.invoke("실제 AAOS 재조회 중 · 문 요청 전송 안 함"); return }
        val capture = aaosConnection.capture()
        if (!running || capture == null || car?.isConnected != true) {
            onRejected?.invoke("AAOS 현재 연결 미확인 · 문 요청 전송 안 함")
            return
        }
        val manager = capture.manager
        val read = PendingAaosRead(capture, epoch, after, onRejected)
        val parkingSupportedBefore = snapshot.parkingBrakeSupported
        pendingAaosRead = read
        handler.postDelayed({
            if (!read.completed) {
                rejectAaosRead(read, "AAOS 실제 재조회 4초 시간 초과")
                if (running && epoch == read.epoch && aaosConnection.accepts(read.capture)) {
                    snapshot = snapshot.copy(connected = false, speedMetersPerSecond = unavailable("AAOS 조회 시간 초과"), gear = unavailable("AAOS 조회 시간 초과"), parkingBrake = unavailable("AAOS 조회 시간 초과"))
                    log("AAOS read timeout generation=${read.capture.generation}; late result discarded")
                }
            }
        }, 4_000)
        worker.execute {
            val speed = property<Float>(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED, true)
            val gear = property<Int>(manager, VehiclePropertyIds.GEAR_SELECTION, false)
            val park = property<Boolean>(manager, VehiclePropertyIds.PARKING_BRAKE_ON, false)
            val parkingSupported = parkingSupportedBefore || park.value != null || runCatching { manager.getCarPropertyConfig(VehiclePropertyIds.PARKING_BRAKE_ON) != null }.getOrDefault(false)
            handler.post {
                if (read.completed) return@post
                if (!running || read.epoch != epoch || pendingAaosRead !== read || !aaosConnection.accepts(read.capture) || car?.isConnected != true) {
                    rejectAaosRead(read, "AAOS 연결 세대 또는 manager 변경 · 이전 조회 폐기")
                    return@post
                }
                read.completed = true
                pendingAaosRead = null
                if (parkingSupported && !snapshot.parkingBrakeSupported) {
                    capabilityPreferences.edit().putBoolean("parking_brake_supported", true).apply()
                }
                snapshot = snapshot.copy(speedMetersPerSecond = speed, gear = gear, parkingBrake = park, parkingBrakeSupported = parkingSupported, connected = speed.value != null || gear.value != null)
                recordObservation("AAOS.speed", speed); recordObservation("AAOS.gear", gear); recordObservation("AAOS.parkingBrake", park)
                publish()
                read.after?.invoke()
            }
        }
    }

    private fun <T> property(manager: CarPropertyManager, id: Int, continuous: Boolean): SdkObservation<T> = try {
        val property = manager.getProperty<T>(id, 0)
        val now = System.currentTimeMillis()
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - property.timestamp) / 1_000_000
        when {
            property.status != CarPropertyValue.STATUS_AVAILABLE -> SdkObservation(source = "AAOS 실제 조회", outcome = SdkOutcome.UNVERIFIED, error = "property=$id status=${property.status}")
            property.value == null -> SdkObservation(source = "AAOS 실제 조회", error = "property=$id null")
            continuous && (ageMillis !in 0..5_000) -> SdkObservation(property.value, now - ageMillis, "AAOS 오래된 원본 sample", SdkOutcome.UNVERIFIED, "property=$id sample age=${ageMillis}ms", now, property.timestamp)
            else -> SdkObservation(property.value, now, "AAOS 실제 조회", SdkOutcome.TARGET_OBSERVED, sourceTimestampElapsedNanos = property.timestamp)
        }
    } catch (e: Exception) { failure(e, "AAOS property=$id") }

    private fun updateDoor(result: SdkObservation<Boolean>) {
        recordObservation("Vehicle.door.${snapshot.selectedDoorArea}", result)
        snapshot = snapshot.copy(doorLocked = result)
        publish()
    }
    fun onStop() {
        if (!running) return
        val stoppedEpoch = epoch
        running = false; epoch++
        handler.removeCallbacks(tick)
        clearAaosConnection("앱 백그라운드 전환")
        val cleanupCount = cleanups.size
        var cleanupFailures = 0
        cleanups.forEach { cleanup -> runCatching(cleanup).onFailure { cleanupFailures++; log("Listener cleanup ERROR $it") } }; cleanups.clear()
        doorListener = null; telltaleListener = null; telltaleWatched = false; selectedDoorCapability = null; beltAreas.clear(); inflight.clear()
        runCatching { uxManager?.unregisterListener() }.onFailure { log("UX listener cleanup ERROR $it") }; uxManager = null
        runCatching { car?.disconnect() }.onFailure { log("AAOS disconnect ERROR $it") }; car = null
        runCatching { vehicle?.release() }.onFailure { log("Vehicle release ERROR $it") }; vehicle = null
        snapshot = snapshot.copy(connected = false, speedMetersPerSecond = snapshot.speedMetersPerSecond.copy(outcome = SdkOutcome.UNVERIFIED, error = "백그라운드 이후 재조회 필요"), gear = snapshot.gear.copy(outcome = SdkOutcome.UNVERIFIED, error = "백그라운드 이후 재조회 필요"), doorLocked = snapshot.doorLocked.copy(outcome = SdkOutcome.UNVERIFIED, error = "백그라운드 이후 재조회 필요"))
        log("Vehicle UNSUBSCRIBE epoch=$stoppedEpoch attempted=$cleanupCount failures=$cleanupFailures; late callbacks ignored by epoch")
    }
    fun close() { onStop(); closed = true; worker.shutdownNow() }
    private fun dispatch(expectedEpoch: Long, action: () -> Unit) { handler.post { if (running && epoch == expectedEpoch) action() } }
    private fun <T> read(key: String, expectedEpoch: Long, call: ((T?) -> Unit, (Exception) -> Unit) -> Unit, receive: (SdkObservation<T>) -> Unit) {
        if (!inflight.add(key)) return
        var completed = false
        fun finish(result: SdkObservation<T>) = dispatch(expectedEpoch) {
            if (!completed) { completed = true; inflight.remove(key); recordObservation(key, result); receive(result) }
        }
        try { call({ value -> finish(observed(value, "실제 Vehicle SDK 조회")) }, { e -> finish(failure(e, "Vehicle SDK $key")) }) }
        catch (e: Exception) { finish(failure(e, "Vehicle SDK $key")) }
        handler.postDelayed({ if (!completed && running && epoch == expectedEpoch) finish(SdkObservation(source = "Vehicle SDK $key", outcome = SdkOutcome.TIMEOUT, error = "조회 4초 시간 초과")) }, 4_000)
    }
    /** 경고등 목록에서 SeatBelt 항목만 꺼낸다. 목록에 없으면 꺼짐(OFF)으로 본다. 다른 경고등·원본 목록은 보관하지 않는다. */
    private fun Telltales?.seatBeltState(): BeltTelltaleState? {
        val list = this?.telltales ?: return null
        return when (list.firstOrNull { it.type == TelltaleType.SEAT_BELT }?.state) {
            null, TelltaleState.OFF -> BeltTelltaleState.OFF
            TelltaleState.ON -> BeltTelltaleState.ON
            TelltaleState.BLINK -> BeltTelltaleState.BLINK
        }
    }
    private fun <T> observed(value: T?, source: String) = SdkObservation(value, System.currentTimeMillis(), source, if (value == null) SdkOutcome.UNVERIFIED else SdkOutcome.TARGET_OBSERVED, if (value == null) "null · 미확인" else null)
    private fun <T> unavailable(reason: String) = SdkObservation<T>(source = "AAOS", outcome = SdkOutcome.UNVERIFIED, error = reason)
    private fun <T> failure(e: Exception, source: String) = SdkObservation<T>(source = source, outcome = when (e) { is SecurityException -> SdkOutcome.PERMISSION_REQUIRED; is IllegalArgumentException -> SdkOutcome.UNSUPPORTED; else -> SdkOutcome.ERROR }, error = "${e.javaClass.simpleName}: ${e.message}")
    private fun packageVersion(name: String) = runCatching { @Suppress("DEPRECATION") app.packageManager.getPackageInfo(name, 0).versionName ?: "미확인" }.getOrDefault("미설치 또는 조회 권한/가시성 미확인")
    private fun recordObservation(key: String, result: SdkObservation<*>) {
        val signature = "${result.value}|${result.outcome}|${result.error}"
        if (lastLoggedObservations.put(key, signature) != signature) log("$key observed=${result.value} outcome=${result.outcome} source=${result.source} observedAt=${result.observedAtMillis} receivedAt=${result.receivedAtMillis} originalElapsedNanos=${result.sourceTimestampElapsedNanos} error=${result.error}")
    }
    private fun log(message: String) {
        val line = "${System.currentTimeMillis()} $message"
        snapshot = snapshot.copy(diagnostics = (snapshot.diagnostics + line).takeLast(100))
        runCatching { File(app.filesDir, "sdk-vehicle.log").appendText(line + "\n") }
        publish()
    }
    private fun publish() {
        onUpdate(snapshot)
    }
}
