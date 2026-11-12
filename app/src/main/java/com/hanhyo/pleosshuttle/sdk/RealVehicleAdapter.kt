package com.hanhyo.pleosshuttle.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/** Only public SDK APIs. This class has no entry point for simulated sensor inputs. */
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
    private var revision = 0L
    private var subscriptionSerial = 0L
    private var token: UnlockConfirmation? = null
    private var doorWritable = false
    private var selectedDoorCapability: ExactSupportedArea<DoorArea>? = null
    private var doorListener: DoorLockStateListener? = null
    private val cleanups = mutableListOf<() -> Unit>()
    private val occupancyAreas = mutableMapOf<String, SeatArea>()
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
    private var doorAttemptInProgress = false
    private var doorFailureLatch: String? = null
    private var doorCallback: ((SdkObservation<Boolean>) -> Unit)? = null
    private var snapshot = VehicleSnapshot(parkingBrakeSupported = capabilityPreferences.getBoolean("parking_brake_supported", false), versions = mapOf(
        "Vehicle SDK" to "2.3.2", "Vehicle Service" to packageVersion("ai.pleos.playground.service.vehicle"),
        "Android" to "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
        "Build fingerprint" to Build.FINGERPRINT, "Hardware" to Build.HARDWARE,
    ))
    val current: VehicleSnapshot get() = snapshot

    // Exact identity read from emulator-5556 Pleos_Connect_v2.1.0 on 2026-10-30. No UI/runtime override.
    private fun isVerifiedEmulator(): Boolean = UnlockSafetyPolicy.isVerifiedEmulator(
        app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT,
        VERIFIED_PLEOS_FINGERPRINTS, VERIFIED_PLEOS_PRODUCTS,
    )

    fun initialize() = onStart()
    fun onStart() {
        if (closed || running) return
        running = true
        val startEpoch = ++epoch
        snapshot = snapshot.copy(emulatorGuard = isVerifiedEmulator())
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
                                    if (snapshot.uxRestricted.value != restricted) invalidateConfirmation("주행 UX 제한 상태 변경")
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
            if (token != null && (SystemClock.elapsedRealtime() - token!!.createdElapsed > CONFIRMATION_TTL || motionBlock() != null)) invalidateConfirmation("입력 만료 또는 상태 변화")
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
        occupancyAreas.toMap().forEach { (name, area) ->
            read("seat:$name", currentEpoch, { ok, fail -> v.getSeat().getCurrentSeatOccupancy(area, ok, fail) }) { result ->
                snapshot = snapshot.copy(seatOccupancy = snapshot.seatOccupancy + (name to result)); publish()
            }
        }
        beltAreas.toMap().forEach { (name, area) ->
            read("belt:$name", currentEpoch, { ok, fail -> v.getSeat().getCurrentSeatBeltBuckledState(area, ok, fail) }) { result ->
                snapshot = snapshot.copy(seatBelts = snapshot.seatBelts + (name to result)); publish()
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
                if (selectedDoorCapability?.key != selected?.key) invalidateConfirmation("SDK 문 그룹 범위 변경")
                selectedDoorCapability = selected
                doorWritable = config.accessType == AccessType.READ_WRITE
                snapshot = snapshot.copy(supportedDoorAreas = supported.map { it.key }, selectedDoorArea = selected?.key)
                log("Door capability access=${config.accessType} originalAreas=$areas exactGroups=${supported.map { it.key }}; selectedExactGroup=${selected?.key}; all members of selected group share the request")
                if (selected != null) registerDoor(startEpoch, selected)
                else snapshot = snapshot.copy(doorLocked = SdkObservation(outcome = SdkOutcome.UNSUPPORTED, error = "ROW_2_RIGHT를 포함하는 원본 SDK Area 미지원"))
                refresh()
            } }, onFailed = { e -> handler.post { if (running && epoch == startEpoch) { doorWritable = false; snapshot = snapshot.copy(doorLocked = failure(e, "Vehicle capability")); log("Door capability ERROR $e") } } })
            v.getSeat().checkSeatOccupancyCapabilities(onComplete = { _, areas -> handler.post {
                if (!running || epoch != startEpoch) return@post
                val supported = exactSupportedAreas(areas) { it.values.map { value -> value.name } }
                supported.forEach { occupancyAreas[it.key] = it.original }
                snapshot = snapshot.copy(supportedSeatAreas = supported.map { it.key })
                log("Seat occupancy capability originalAreas=$areas exactGroups=${snapshot.supportedSeatAreas}")
                occupancyAreas.forEach { (name, area) -> registerOccupancy(name, area, startEpoch) }
                refresh()
            } }, onFailed = { e -> handler.post { if (running && epoch == startEpoch) log("Seat capability ERROR $e") } })
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
    private fun registerOccupancy(name: String, area: SeatArea, startEpoch: Long) {
        val seat = vehicle?.getSeat() ?: return
        val listener = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) = dispatch(startEpoch) { snapshot = snapshot.copy(seatOccupancy = snapshot.seatOccupancy + (name to observed(value, "실제 Vehicle SDK listener"))); publish() }
            override fun onFailed(exception: Exception) = dispatch(startEpoch) { snapshot = snapshot.copy(seatOccupancy = snapshot.seatOccupancy + (name to failure(exception, "Vehicle SDK listener"))); publish() }
        }
        try { seat.registerSeatOccupancy(area, listener); cleanups += { seat.unregisterSeatOccupancy(area, listener) }; log("SUBSCRIBE epoch=$startEpoch key=occupancy/$name serial=${++subscriptionSerial} app call returned") }
        catch (e: Exception) { log("Occupancy $name listener ERROR $e") }
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
        invalidateConfirmation(reason)
        snapshot = snapshot.copy(connected = false, speedMetersPerSecond = unavailable(reason), gear = unavailable(reason), parkingBrake = unavailable(reason), uxRestricted = unavailable(reason), activeUxRestrictions = null)
        pendingAaosRead?.let { rejectAaosRead(it, reason) }
        val previousUx = uxManager
        uxManager = null
        runCatching { previousUx?.unregisterListener() }.onFailure { log("UX listener cleanup ERROR $it") }
        if (doorAttemptInProgress) finishDoor(SdkObservation(outcome = SdkOutcome.UNVERIFIED, error = "$reason · 이미 보낸 문 요청의 최종 상태 미확인"), "AAOS 연결 변경 중 문 요청: 반복 요청 차단")
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
                rejectAaosRead(read, "AAOS 실제 재조회 4초 시간 초과 · 문 요청 전송 안 함")
                if (running && epoch == read.epoch && aaosConnection.accepts(read.capture)) {
                    invalidateConfirmation("AAOS 실제 재조회 시간 초과")
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
                    rejectAaosRead(read, "AAOS 연결 세대 또는 manager 변경 · 이전 조회 폐기 · 문 요청 전송 안 함")
                    return@post
                }
                read.completed = true
                pendingAaosRead = null
                if (snapshot.speedMetersPerSecond.value != speed.value || snapshot.gear.value != gear.value || snapshot.parkingBrake.value != park.value || snapshot.speedMetersPerSecond.outcome != speed.outcome || snapshot.gear.outcome != gear.outcome) invalidateConfirmation("차량 운행 상태 변경")
                if (parkingSupported && !snapshot.parkingBrakeSupported) {
                    capabilityPreferences.edit().putBoolean("parking_brake_supported", true).apply()
                    invalidateConfirmation("주차 브레이크 지원 확인 · 최신 값 필요")
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

    /** Call only when entering the explicit driver's final-confirmation screen. */
    fun prepareUnlockConfirmation(confirmedAreaKey: String? = null): UnlockConfirmation? {
        if (confirmedAreaKey == null || confirmedAreaKey != selectedDoorCapability?.key) {
            token = null
            log("Unlock prepare blocked: 표시한 원본 SDK 문 그룹 전체의 명시적 확인 필요; selected=${selectedDoorCapability?.key}")
            return null
        }
        val reason = unlockBlock()
        if (reason != null) { log("Unlock prepare blocked: $reason"); return null }
        return UnlockConfirmation(System.nanoTime(), revision, SystemClock.elapsedRealtime(), confirmedAreaKey).also { token = it }
    }

    /** Consumes one token. Never retries, re-locks, or accepts synthetic safety inputs. */
    fun requestUnlock(confirmation: UnlockConfirmation, callback: (SdkObservation<Boolean>) -> Unit) {
        if (!UnlockSafetyPolicy.confirmationIsCurrent(confirmation, token, revision, SystemClock.elapsedRealtime())) {
            callback(SdkObservation(outcome = SdkOutcome.ERROR, error = "최종 확인 만료 또는 무효화")); return
        }
        val reason = unlockBlock()
        if (reason != null || aaosReadPending) { token = null; callback(SdkObservation(outcome = SdkOutcome.ERROR, error = reason ?: "실제 상태 재조회 중 · 다시 확인 필요")); return }
        refreshAaos(onRejected = { reason ->
            token = null
            callback(SdkObservation(outcome = SdkOutcome.UNVERIFIED, source = "AAOS 최종 실제 조회", error = reason))
        }) {
            val selected = selectedDoorCapability
            if (selected == null || confirmation.areaKey != selected.key || !UnlockSafetyPolicy.confirmationIsCurrent(confirmation, token, revision, SystemClock.elapsedRealtime()) || unlockBlock() != null) {
                token = null; callback(SdkObservation(outcome = SdkOutcome.ERROR, error = "최종 확인 뒤 상태 변경·만료: ${unlockBlock()}")); return@refreshAaos
            }
            token = null
            doorAttemptInProgress = true
            doorCallback = callback
            val currentEpoch = epoch
            val before = snapshot.doorLocked.value
            snapshot = snapshot.copy(doorRequest = SdkObservation(before, snapshot.doorLocked.observedAtMillis, "실제 SDK 요청 전 관측", SdkOutcome.UNVERIFIED))
            log("Door ONE-SHOT pre=$before request=false exactGroup=${selected.key} speed=${snapshot.speedMetersPerSecond.value} gear=${snapshot.gear.value} park=${snapshot.parkingBrake.value}")
            try {
                vehicle!!.getDoor().setDoorLock(selected.original, false, onComplete = { _, requested -> dispatch(currentEpoch) {
                    if (!doorAttemptInProgress) return@dispatch
                    snapshot = snapshot.copy(doorRequest = SdkObservation(snapshot.doorLocked.value, snapshot.doorLocked.observedAtMillis, "실제 SDK 요청 수락 · 값은 마지막 별도 관측", SdkOutcome.ACCEPTED))
                    log("Door request ACCEPTED requested=$requested; actual=${snapshot.doorLocked.value}")
                    callback(snapshot.doorRequest)
                    refresh()
                } }, onFailed = { e -> dispatch(currentEpoch) {
                    finishDoor(failure(e, "실제 SDK 문 요청"), "요청 오류 이후 자동 재시도 없음")
                } })
                handler.postDelayed({
                    if (running && epoch == currentEpoch && doorAttemptInProgress) finishDoor(SdkObservation(snapshot.doorLocked.value, snapshot.doorLocked.observedAtMillis, "실제 SDK 사후 관측", SdkOutcome.TIMEOUT, "요청 후 해제 상태 미관측; 마지막=${snapshot.doorLocked.value}"), "목표 상태 미관측: 반복 요청 차단")
                }, 6_000)
            } catch (e: Exception) { finishDoor(failure(e, "실제 SDK 문 요청"), "요청 오류 이후 자동 재시도 없음") }
        }
    }

    private fun updateDoor(result: SdkObservation<Boolean>) {
        recordObservation("Vehicle.door.${snapshot.selectedDoorArea}", result)
        if (snapshot.doorLocked.value != result.value || result.outcome != SdkOutcome.TARGET_OBSERVED) invalidateConfirmation("문 상태 변경 또는 미확인")
        snapshot = snapshot.copy(doorLocked = result)
        if (doorAttemptInProgress && result.value == false && result.isFresh()) finishDoor(result.copy(outcome = SdkOutcome.TARGET_OBSERVED), null)
        publish()
    }
    private fun finishDoor(result: SdkObservation<Boolean>, latch: String?) {
        doorAttemptInProgress = false
        if (latch != null) doorFailureLatch = latch
        snapshot = snapshot.copy(doorRequest = result)
        log("Door final=${result.outcome} observed=${result.value} error=${result.error}")
        val callback = doorCallback; doorCallback = null; callback?.invoke(result)
    }
    fun invalidateConfirmation(reason: String) {
        revision++
        if (token != null) { token = null; log("Unlock confirmation invalidated: $reason") }
    }
    private fun motionBlock(): String? = UnlockSafetyPolicy.motionBlock(
        snapshot, running, System.currentTimeMillis(), VehicleGear.GEAR_PARK,
    )
    private fun unlockBlock(): String? = when {
        !isVerifiedEmulator() -> "확인된 Pleos 에뮬레이터의 디버그 앱에서만 시험 가능"
        properties == null || car?.isConnected != true -> "AAOS 현재 연결 미확인 · 실제 manager 재연결 필요"
        doorAttemptInProgress -> "문 요청 처리 중 · 중복 요청 금지"
        doorFailureLatch != null -> doorFailureLatch
        !doorWritable -> "문 영역 읽기·쓰기 지원 미확인"
        selectedDoorCapability == null -> "지원하는 원본 SDK 문 그룹 미확인"
        !snapshot.doorLocked.isFresh() -> "문 상태 미확인 또는 오래된 값"
        snapshot.doorLocked.value != true -> "현재 잠금 상태가 true로 관측되지 않음"
        else -> motionBlock()
    }

    fun onStop() {
        if (!running) return
        val stoppedEpoch = epoch
        running = false; epoch++
        handler.removeCallbacks(tick)
        clearAaosConnection("앱 백그라운드 전환")
        if (doorAttemptInProgress) finishDoor(SdkObservation(outcome = SdkOutcome.UNVERIFIED, error = "앱 비활성화: 이미 보낸 요청의 최종 상태 미확인"), "문 요청 중 비활성화: 반복 요청 차단")
        val cleanupCount = cleanups.size
        var cleanupFailures = 0
        cleanups.forEach { cleanup -> runCatching(cleanup).onFailure { cleanupFailures++; log("Listener cleanup ERROR $it") } }; cleanups.clear()
        doorListener = null; selectedDoorCapability = null; occupancyAreas.clear(); beltAreas.clear(); inflight.clear(); doorWritable = false
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
        snapshot = snapshot.copy(unlockBlockReason = unlockBlock() ?: "실제 데이터 조건 충족 · 주변 직접 확인과 운전자 최종 확인 별도")
        onUpdate(snapshot)
    }
    companion object {
        private const val CONFIRMATION_TTL = UnlockSafetyPolicy.CONFIRMATION_TTL_MILLIS
        private val VERIFIED_PLEOS_FINGERPRINTS = setOf("Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys")
        private val VERIFIED_PLEOS_PRODUCTS = setOf("crp_avd_arm64")
    }
}
