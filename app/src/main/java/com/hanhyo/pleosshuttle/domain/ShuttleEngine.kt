package com.hanhyo.pleosshuttle.domain

/** Single-owner domain state. Call on the ViewModel owner thread; adapters only deliver observations. */
class ShuttleEngine(initial: TripState = SampleTrip.create()) {
    var state: TripState = initial
        private set
    var vehicle: VehicleSnapshot = VehicleSnapshot()
        private set
    var surroundings: Surroundings = Surroundings()
        private set
    var lastMessage: String = "가상의 명단과 공개 장소로 하원 흐름을 체험합니다."
        private set
    private var safetyRevision = 0L
    private var sequence = 0L

    private fun reject(message: String): Boolean { lastMessage = message; return false }
    private fun update(value: TripState, message: String): Boolean {
        state = value.copy(revision = state.revision + 1)
        lastMessage = message
        return true
    }
    private fun id(prefix: String, now: Long) = "$prefix-$now-${++sequence}"
    private fun diagnostic(entry: DiagnosticEntry) {
        state = state.copy(diagnostics = (state.diagnostics + entry).takeLast(500))
    }
    private fun invalidate(now: Long, reason: String) {
        safetyRevision++
        val active = state.unlockAttempt?.takeIf { it.completedAtMillis == null }
        if (state.unlockConfirmation != null || active != null) {
            diagnostic(DiagnosticEntry(now, "문 해제 확인", ResultStatus.UNVERIFIED, "확인 무효화: $reason"))
        }
        state = state.copy(
            unlockConfirmation = null,
            manualSurroundingsCheckAtMillis = null,
            manualDoorReason = null, manualDoorAtMillis = null, directCabinCheckAtMillis = null,
            unlockAttempt = state.unlockAttempt?.copy(invalidated = true),
            revision = state.revision + 1,
        )
    }
    private fun guardKey(value: VehicleSnapshot): List<Any?> = listOf(
        value.connected, value.connectionGeneration,
        value.speedKph.value, value.speedKph.source, value.speedKph.status,
        value.gear.value, value.gear.source, value.gear.status,
        value.parkingBrakeSupported, value.parkingBrake.value, value.parkingBrake.source, value.parkingBrake.status,
    )

    fun updateVehicle(snapshot: VehicleSnapshot, now: Long): Boolean {
        tick(now)
        if (guardKey(vehicle) != guardKey(snapshot)) invalidate(now, "차량 운행·연결 상태 변화")
        vehicle = snapshot
        tick(now)
        return true
    }
    fun updateSurroundings(snapshot: Surroundings, now: Long): Boolean {
        tick(now)
        if (surroundings != snapshot) invalidate(now, "주변 정보 변경")
        surroundings = snapshot
        return true
    }
    fun background(now: Long): Boolean {
        invalidate(now, "앱이 백그라운드로 이동함")
        vehicle = VehicleSnapshot(connectionGeneration = vehicle.connectionGeneration + 1)
        return true
    }
    fun restore(saved: TripState, now: Long): Boolean {
        state = saved.copy(
            unlockConfirmation = null, manualSurroundingsCheckAtMillis = null,
            directCabinCheckAtMillis = null, manualDoorReason = null, manualDoorAtMillis = null,
            handoverChildIds = emptySet(),
            unlockAttempt = saved.unlockAttempt?.let {
                if (it.completedAtMillis == null) it.copy(status = ResultStatus.UNVERIFIED, invalidated = true, completedAtMillis = now) else it.copy(invalidated = true)
            },
        )
        vehicle = VehicleSnapshot(connectionGeneration = vehicle.connectionGeneration + 1)
        surroundings = Surroundings()
        safetyRevision++
        diagnostic(DiagnosticEntry(now, "복원", ResultStatus.UNVERIFIED, "운행과 기록 복원. 차량·주변 정보 및 해제 확인은 다시 확인해야 함."))
        lastMessage = "진행 중 운행을 복원했습니다. 센서와 확인 절차는 다시 확인합니다."
        return true
    }
    fun setIntegrationMode(mode: IntegrationMode, now: Long): Boolean {
        if (state.phase == TripPhase.ENDED) return reject("종료된 운행의 연동 모드는 변경할 수 없습니다.")
        if (state.unlockAttempt?.completedAtMillis == null && state.unlockAttempt != null) return reject("문 요청 결과를 기다리는 중입니다.")
        if (state.phase != TripPhase.PREPARING && stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        invalidate(now, "실제·모의 모드 전환")
        vehicle = VehicleSnapshot(connectionGeneration = vehicle.connectionGeneration + 1)
        surroundings = Surroundings()
        return update(state.copy(integrationMode = mode, unlockAttempt = null), "${mode.label}로 전환했습니다. 새 관측이 필요합니다.")
    }
    fun setRoutePolicy(objective: RouteObjective?, returnToOrigin: Boolean, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("방문 순서 기준은 운행 준비에서 정합니다.")
        if (vehicle.connected && stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        return update(state.copy(routeObjective = objective, returnToOrigin = returnToOrigin, routeProposal = null, routeVersion = state.routeVersion + 1), "방문 순서 기준을 저장했습니다.")
    }
    fun setResumePolicy(policy: ResumePolicy?): Boolean = update(state.copy(resumePolicy = policy), "운행 복귀 조건을 저장했습니다.")

    /** Ordinary records require parked, fresh telemetry, not a door-actuation interlock. */
    fun stationaryBlockers(now: Long): List<String> = buildList {
        if (!vehicle.connected) add("차량 서비스 연결 미확인")
        if (!vehicle.speedKph.isFresh(now)) add("속도 미확인 또는 오래된 정보")
        else if (!vehicle.speedKph.value!!.isFinite() || kotlin.math.abs(vehicle.speedKph.value!!) > 0.01) add("이동 중: 정차가 필요함")
        if (!vehicle.gear.isFresh(now) || vehicle.gear.value == Gear.UNKNOWN) add("기어 미확인 또는 오래된 정보")
        else if (vehicle.gear.value != Gear.PARK) add("운행 기록은 정차 후 P 기어에서 확인해 주세요")
        if (state.integrationMode == IntegrationMode.SDK &&
            (vehicle.speedKph.source !in REAL_SOURCES || vehicle.gear.source !in REAL_SOURCES))
            add("모의 정보로 운행 기록을 확인할 수 없음 · 실제 차량 속도·기어 필요")
    }
    /** Kept separate: no ordinary attendance or route operation may inherit this condition. */
    fun doorStationaryBlockers(now: Long): List<String> = buildList {
        addAll(stationaryBlockers(now))
        if (vehicle.parkingBrakeSupported && (!vehicle.parkingBrake.isFresh(now) || vehicle.parkingBrake.value != true))
            add("문 제어 시험은 지원되는 주차 브레이크 체결 확인이 필요함")
        if (state.integrationMode == IntegrationMode.SDK && vehicle.parkingBrakeSupported && vehicle.parkingBrake.source !in REAL_SOURCES)
            add("모의 주차 정보로 실제 문 제어를 할 수 없음")
    }
    fun unlockBlockers(now: Long, requireLocked: Boolean = true): List<String> = buildList {
        if (state.phase != TripPhase.ALIGHTING) add("운전자의 명시적 하차 선택이 필요함")
        addAll(doorStationaryBlockers(now))
        if (surroundings.kind == SurroundingKind.WARNING) add("접근 물체 등 위험 정보가 있음")
        val manualAt = state.manualSurroundingsCheckAtMillis
        if (manualAt == null || now < manualAt || now - manualAt > MANUAL_CHECK_TTL) add("운전자의 최신 주변 직접 확인이 필요함")
        val surroundingAt = surroundings.observedAtMillis
        if (surroundingAt != null && (now < surroundingAt || now - surroundingAt > MANUAL_CHECK_TTL) && (manualAt == null || manualAt <= surroundingAt)) add("주변 정보가 오래됨: 직접 다시 확인해야 함")
        if (requireLocked) {
            if (!vehicle.doorLocked.isFresh(now)) add("문 잠금 현재값 미확인 또는 오래된 정보")
            else if (vehicle.doorLocked.value != true) add("현재 잠금 상태가 아니므로 추가 해제 요청하지 않음")
            if (state.integrationMode == IntegrationMode.SDK && vehicle.doorLocked.source !in REAL_SOURCES) add("모의 문 상태로 실제 SDK 제어를 할 수 없음")
        }
    }
    fun tick(now: Long): Boolean {
        val inspected = state.directCabinCheckAtMillis
        if (inspected != null && (now < inspected || now - inspected > INSPECTION_TTL)) {
            state = state.copy(directCabinCheckAtMillis = null)
        }
        val manualDoor = state.manualDoorAtMillis
        if (manualDoor != null && (now < manualDoor || now - manualDoor > MANUAL_CHECK_TTL)) {
            state = state.copy(manualDoorReason = null, manualDoorAtMillis = null)
        }
        val confirmation = state.unlockConfirmation
        if (confirmation != null && (now > confirmation.expiresAtMillis || confirmation.safetyRevision != safetyRevision || unlockBlockers(now).isNotEmpty())) {
            invalidate(now, "확인 또는 입력의 유효시간 만료")
        }
        val attempt = state.unlockAttempt
        if (attempt != null && attempt.completedAtMillis == null) {
            if (!attempt.invalidated && unlockBlockers(now, requireLocked = false).isNotEmpty()) invalidate(now, "요청 중 운행·주변 정보 미확인")
            if (now - attempt.request.requestedAtMillis > REQUEST_TIMEOUT) {
                state = state.copy(unlockAttempt = state.unlockAttempt!!.copy(status = ResultStatus.TIMEOUT, completedAtMillis = now))
                diagnostic(DiagnosticEntry(now, "문 해제", ResultStatus.TIMEOUT, "목표 잠금 상태를 기한 안에 관측하지 못함", requested = "false", observed = vehicle.doorLocked.value?.toString()))
            }
        }
        return true
    }

    fun board(childId: String, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("탑승 기록은 운행 준비에서 입력합니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.NOT_BOARDED) return reject("이미 탑승 또는 하차 기록이 있습니다. 정정 기능을 이용하세요.")
        return changeAttendance(child, Attendance.ONBOARD, "선생님 탑승 확인", now)
    }
    private fun changeAttendance(child: ChildRecord, target: Attendance, reason: String, now: Long): Boolean {
        val event = LedgerEntry(id("ledger", now), child.id, child.status, target, now, reason)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(status = target) else it },
            ledger = state.ledger + event,
            handoverChildIds = state.handoverChildIds - child.id,
            directCabinCheckAtMillis = null,
        ), "${child.name}: ${target.label} 저장")
    }
    fun correct(childId: String, target: Attendance, reason: String, now: Long): Boolean {
        if (state.phase == TripPhase.DRIVING || state.phase == TripPhase.ENDED) return reject("운행 중과 종료 후에는 명단을 정정할 수 없습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태 확인 후 정정할 수 있습니다.")
        if (reason.isBlank()) return reject("정정 사유가 필요합니다.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status == target) return reject("현재 기록과 같은 상태입니다.")
        if (target == Attendance.ALIGHTED && (child.status != Attendance.ONBOARD || child.id !in state.handoverChildIds)) return reject("탑승 기록과 선생님의 보호자 대면 인계 확인이 필요합니다.")
        return changeAttendance(child, target, "정정: $reason", now)
    }
    fun startDriving(now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("운행 준비 단계가 아닙니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject("출발 전 정차 상태를 확인하세요.")
        if (state.onboardChildren.isEmpty()) return reject("선생님이 탑승 기록을 먼저 입력하세요.")
        invalidate(now, "운행 시작")
        return update(state.copy(phase = TripPhase.DRIVING), "운행 화면으로 전환했습니다. 실제 차량 출발을 제어하지 않습니다.")
    }
    fun chooseAlighting(now: Long): Boolean {
        if (state.phase != TripPhase.DRIVING) return reject("운행 화면에서 하차를 선택하세요.")
        if (state.currentStop == null) return reject("남은 하차 지점이 없습니다. 종료 확인으로 진행하세요.")
        val blockers = stationaryBlockers(now)
        if (blockers.isNotEmpty()) return reject(blockers.joinToString(" · "))
        invalidate(now, "도착·정차 확인")
        return update(state.copy(phase = TripPhase.ALIGHTING, unlockAttempt = null, handoverChildIds = emptySet()), "도착·정차 확인. 선생님이 보호자 인계와 하차를 기록합니다.")
    }
    fun confirmSurroundings(now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING) return reject("하차 화면에서 직접 확인합니다.")
        if (doorStationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("위험 정보가 남아 있어 주변 확인을 진행할 수 없습니다.")
        state = state.copy(unlockConfirmation = null)
        return update(state.copy(manualSurroundingsCheckAtMillis = now), "운전자 직접 확인 기록. 주변 API 미확인은 그대로 유지됩니다.")
    }
    fun prepareUnlock(now: Long): Boolean {
        tick(now)
        val blockers = unlockBlockers(now)
        if (blockers.isNotEmpty()) return reject(blockers.joinToString(" · "))
        if (state.unlockAttempt != null) return reject("이 하차 절차에서는 문 해제 시험을 한 번만 요청합니다. 결과를 확인하세요.")
        if (state.unlockConfirmation != null) return reject("최종 확인이 이미 준비되어 있습니다.")
        return update(state.copy(unlockConfirmation = UnlockConfirmation(id("unlock", now), now, now + CONFIRMATION_TTL, safetyRevision, vehicle.connectionGeneration, state.integrationMode)), "최종 확인 준비. 버튼을 누르면 현재 모드로 한 번 요청합니다.")
    }
    fun consumeUnlock(now: Long): UnlockRequest? {
        tick(now)
        val token = state.unlockConfirmation ?: run { reject("최신 최종 확인이 필요합니다."); return null }
        if (token.safetyRevision != safetyRevision || token.connectionGeneration != vehicle.connectionGeneration || token.mode != state.integrationMode || unlockBlockers(now).isNotEmpty() || state.unlockAttempt != null) {
            invalidate(now, "요청 직전 조건 변화"); reject("조건이 바뀌어 확인을 다시 해야 합니다."); return null
        }
        val request = UnlockRequest(token.id, now, state.integrationMode, state.currentStop!!.id, safetyRevision, vehicle.connectionGeneration, beforeLocked = true)
        update(state.copy(unlockConfirmation = null, unlockAttempt = UnlockAttempt(request)), "문 해제 요청 전송 중. 상태 변화는 아직 미확인입니다.")
        diagnostic(DiagnosticEntry(now, "문 해제", ResultStatus.UNVERIFIED, "운전자 최종 확인 후 단일 요청", before = "true", requested = "false"))
        return request
    }
    fun onUnlockResult(requestId: String, status: ResultStatus, observedLocked: Boolean?, errorCode: String?, now: Long): Boolean {
        tick(now)
        val attempt = state.unlockAttempt ?: return reject("활성 문 요청이 없습니다.")
        if (attempt.request.id != requestId) return reject("다른 요청의 응답입니다.")
        val invalid = attempt.invalidated || attempt.request.safetyRevision != safetyRevision || attempt.request.mode != state.integrationMode
        if (invalid || attempt.completedAtMillis != null) {
            diagnostic(DiagnosticEntry(now, "늦은 문 응답", status, "현재 절차 완료로 사용하지 않음", observed = observedLocked?.toString(), errorCode = errorCode))
            return reject("이전 확인이 무효화되어 응답을 진단에만 기록했습니다.")
        }
        val normalized = if (status == ResultStatus.TARGET_OBSERVED && observedLocked != false) ResultStatus.PARTIAL else status
        val completed = normalized != ResultStatus.ACCEPTED && normalized != ResultStatus.UNVERIFIED
        diagnostic(DiagnosticEntry(now, "문 해제", normalized, if (normalized == ResultStatus.TARGET_OBSERVED) "잠금 해제 상태를 관측함. 하차 기록과 별개." else "요청과 실제 관측을 분리함", before = "true", requested = "false", observed = observedLocked?.toString(), errorCode = errorCode))
        return update(state.copy(unlockAttempt = attempt.copy(status = normalized, observedLocked = observedLocked, errorCode = errorCode, completedAtMillis = if (completed) now else null)), "문 해제: ${normalized.label}")
    }
    fun manualDoorHandling(reason: String, now: Long): Boolean {
        tick(now)
        if (state.phase != TripPhase.ALIGHTING) return reject("하차 화면에서 수동 대안을 기록합니다.")
        if (doorStationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("위험 정보가 있어 수동 대안도 진행하지 않습니다.")
        val checked = state.manualSurroundingsCheckAtMillis
        if (checked == null || now < checked || now - checked > MANUAL_CHECK_TTL) return reject("운전자의 최신 주변 직접 확인이 필요합니다.")
        if (state.unlockAttempt != null && state.unlockAttempt?.completedAtMillis == null) return reject("문 요청 처리 결과를 먼저 확인하세요.")
        if (reason.isBlank()) return reject("수동 대안 사유가 필요합니다.")
        diagnostic(DiagnosticEntry(now, "문 수동 대안", ResultStatus.UNVERIFIED, "수동 대안 · SDK 해제 미확인: $reason", observed = vehicle.doorLocked.value?.toString()))
        return update(state.copy(unlockConfirmation = null, manualDoorReason = reason, manualDoorAtMillis = now), "수동 대안을 기록했습니다. SDK 요청과 해제 완료 관측은 생성하지 않았습니다.")
    }
    fun confirmHandover(childId: String, now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING && state.phase != TripPhase.END_REVIEW) return reject("하차 또는 종료 확인 화면에서 인계를 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("주변 위험 경고를 먼저 확인하세요.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 기록이 있는 어린이만 인계 확인할 수 있습니다.")
        if (state.phase == TripPhase.ALIGHTING && child.stopId != state.currentStop?.id) return reject("현재 하차 지점의 어린이가 아닙니다.")
        if (childId in state.handoverChildIds) return reject("이미 보호자 대면 인계를 확인했습니다.")
        diagnostic(DiagnosticEntry(now, "보호자 대면 인계", ResultStatus.TARGET_OBSERVED, "수동 기록: ${child.name}의 보호자 대면 인계 확인"))
        return update(state.copy(handoverChildIds = state.handoverChildIds + childId), "선생님 보호자 대면 인계 확인을 기록했습니다.")
    }
    fun alight(childId: String, now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING) return reject("도착·정차 확인 후 하차를 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("주변 위험 경고를 먼저 확인하세요.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.stopId != state.currentStop?.id) return reject("현재 하차 지점의 어린이가 아닙니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 기록이 없거나 이미 하차 기록이 있습니다.")
        if (childId !in state.handoverChildIds) return reject("선생님이 보호자 대면 인계를 먼저 확인해야 합니다.")
        return changeAttendance(child, Attendance.ALIGHTED, "선생님 보호자 대면 인계 후 직접 하차 기록", now)
    }
    /** Attendance is a human record, independent of optional door-control experiments. */
    fun recordAlightingAndContinue(childId: String, now: Long): Boolean {
        if (!alight(childId, now)) return false
        if (state.currentStopChildren.none { it.status == Attendance.ONBOARD }) completeCurrentStop(now)
        return true // The attendance write remains valid even if a lab request delays transition.
    }
    fun completeCurrentStop(now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING || state.currentStop == null) return reject("현재 하차 지점이 없습니다.")
        if (state.currentStopChildren.any { it.status == Attendance.ONBOARD }) return reject("현재 지점의 인계·하차 기록이 남아 있습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("주변 위험 경고를 먼저 확인하세요.")
        if (state.unlockAttempt?.let { it.completedAtMillis == null } == true) return reject("별도 문 시험의 응답을 기다리고 있습니다.")
        diagnostic(DiagnosticEntry(now, "지점 기록 완료", ResultStatus.TARGET_OBSERVED,
            "현재 지점의 인계·하차 기록 완료로 다음 목적지 표시. 차량 출발 승인 또는 문·주변 안전 확인이 아님. 주변=${surroundings.kind}, 문시험=${state.unlockAttempt?.status}"))
        invalidate(now, "지점 기록 완료 후 다음 목적지 표시")
        return update(state.copy(phase = TripPhase.DRIVING, currentStopIndex = state.currentStopIndex + 1, handoverChildIds = emptySet()), "하차 기록 완료 · 다음 지점을 안내합니다.")
    }
    fun startOperationalTrip(now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("이미 운행을 시작했습니다.")
        if (state.routeIsExample) {
            if (!calculateRoute(now) || !applyRouteProposal(now)) return false
        }
        return startDriving(now)
    }
    fun inspectAndFinish(now: Long): Boolean {
        if (state.onboardChildren.isNotEmpty()) return reject("미하차 기록을 먼저 확인하세요.")
        if (!confirmCabinInspection(now)) return false
        return finishTrip(now)
    }

    fun resumeWarnings(now: Long): List<String> = buildList {
        if (state.currentStopChildren.any { it.status == Attendance.ONBOARD }) add("현재 지점에 미하차 기록이 남아 있음")
        addAll(doorStationaryBlockers(now))
        if (surroundings.kind != SurroundingKind.DIRECT_CHECK_REQUIRED || surroundings.observedAtMillis == null || now - surroundings.observedAtMillis!! > MANUAL_CHECK_TTL) add("주변 정보 미확인 또는 오래됨")
        if (state.unlockAttempt?.status != ResultStatus.TARGET_OBSERVED) add("문 해제 목표 상태 관측 미확인")
        if (state.unlockAttempt?.completedAtMillis == null && state.unlockAttempt != null) add("문 요청 처리 중")
    }
    fun resumeDriving(reason: String, now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING) return reject("하차 단계에서 운행으로 복귀할 수 있습니다.")
        val policy = state.resumePolicy ?: return reject("운행 복귀 조건이 아직 정해지지 않았습니다.")
        if (state.unlockAttempt != null && state.unlockAttempt?.completedAtMillis == null) return reject("문 요청이 처리 중입니다. 결과 또는 시간 초과를 확인하세요.")
        val warnings = resumeWarnings(now)
        if (warnings.isNotEmpty() && policy == ResumePolicy.REQUIRE_ALL_RESOLVED) return reject(warnings.joinToString(" · "))
        if (warnings.isNotEmpty() && policy == ResumePolicy.REQUIRE_REASON_AND_DRIVER_CONFIRM && reason.isBlank()) return reject("미완료·미확인 항목을 남기는 사유를 기록한 뒤 운전자가 복귀를 확인해야 합니다.")
        diagnostic(DiagnosticEntry(now, "운행 복귀", if (warnings.isEmpty()) ResultStatus.TARGET_OBSERVED else ResultStatus.PARTIAL, "운전자 명시적 복귀. 사유: ${reason.ifBlank { "미완료 항목 없음" }}. ${warnings.joinToString("; ")}"))
        invalidate(now, "운행 화면 복귀")
        return update(state.copy(phase = TripPhase.DRIVING, currentStopIndex = state.currentStopIndex + 1, handoverChildIds = emptySet()), "운행 화면으로 복귀. 미하차 기록은 유지되며 실제 차량 출발을 제어하지 않습니다.")
    }
    fun beginEndReview(now: Long): Boolean {
        if (state.phase == TripPhase.PREPARING || state.phase == TripPhase.ENDED) return reject("운행 중인 기록의 종료를 확인합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (state.unlockAttempt != null && state.unlockAttempt?.completedAtMillis == null) return reject("문 요청 결과를 먼저 확인하세요.")
        invalidate(now, "운행 종료 검토")
        return update(state.copy(phase = TripPhase.END_REVIEW, directCabinCheckAtMillis = null), "명단·좌석 관측·미확인 정보·직접 실내 확인을 각각 검토하세요.")
    }
    fun confirmCabinInspection(now: Long): Boolean {
        if (state.phase != TripPhase.END_REVIEW) return reject("종료 확인 단계가 아닙니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        return update(state.copy(directCabinCheckAtMillis = now), "선생님 직접 실내 확인 기록. 센서와 명단의 불일치는 별도로 남습니다.")
    }
    fun finishTrip(now: Long): Boolean {
        if (state.phase != TripPhase.END_REVIEW) return reject("종료 확인 단계가 아닙니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("종료 기록 전 정차 상태를 다시 확인하세요.")
        if (state.onboardChildren.isNotEmpty()) return reject("기록상 미하차 어린이가 남아 있습니다. 기록을 확인·정정하세요.")
        val inspected = state.directCabinCheckAtMillis ?: return reject("선생님의 직접 실내 확인이 필요합니다.")
        if (now < inspected || now - inspected > INSPECTION_TTL) return reject("직접 실내 확인이 오래되어 다시 확인해야 합니다.")
        val record = EndRecord(now, inspected, state.onboardChildren.map { it.id }, vehicle.seats.filter { it.occupancy.isFresh(now) && it.occupancy.value == true }.map { it.areaId }, vehicle.seats.filter { !it.occupancy.isFresh(now) }.map { it.areaId }, vehicle.connected, sensorCoverageKnown = false, observedVehicle = vehicle,
            occupiedAreaLabels = vehicle.seats.filter { it.occupancy.isFresh(now) && it.occupancy.value == true }.map { it.areaLabel },
            unknownOrStaleAreaLabels = vehicle.seats.filter { !it.occupancy.isFresh(now) }.map { it.areaLabel })
        return update(state.copy(phase = TripPhase.ENDED, endRecord = record), "운행 종료 기록 저장. 좌석 미확인·불일치도 기록에 보존하며 안전을 자동 판정하지 않습니다.")
    }
    fun previewRoute(proposal: RouteProposal): Boolean {
        if (state.phase != TripPhase.PREPARING || proposal.routeVersion != state.routeVersion) return reject("현재 운행 준비에 맞는 경로 계산이 아닙니다.")
        if (!validOrder(proposal.stopOrder)) return reject("승인된 지점 전체만 재정렬할 수 있습니다.")
        return update(state.copy(routeProposal = proposal), "합성 비용 계산 결과 미리보기. 적용 전 순서를 확인하세요.")
    }
    fun applyRoute(order: List<String>, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("운행 중 방문 순서를 변경하지 않습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태 확인 후 순서를 적용하세요.")
        if (!validOrder(order)) return reject("승인된 지점 전체를 중복 없이 포함해야 합니다.")
        return update(state.copy(routeOrder = order.toList(), routeVersion = state.routeVersion + 1, routeProposal = null, routeIsExample = false), "방문 순서를 적용했습니다. 새 하차 지점은 추가하지 않았습니다.")
    }
    fun applyPreview(now: Long): Boolean {
        val proposal = state.routeProposal ?: return reject("적용할 계산 결과가 없습니다.")
        if (proposal.routeVersion != state.routeVersion) return reject("계산 후 경로 조건이 바뀌었습니다. 다시 계산하세요.")
        return applyRoute(proposal.stopOrder, now)
    }
    fun switchMode(mode: IntegrationMode, now: Long): Boolean = setIntegrationMode(mode, now)
    fun calculateRoute(now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("운행 준비에서만 순서를 계산할 수 있습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태 확인 후 계산하세요.")
        return try { previewRoute(RoutePlanner.plan(state)) } catch (error: IllegalArgumentException) { reject(error.message ?: "비용 행렬을 확인하세요.") }
    }
    fun applyRouteProposal(now: Long): Boolean = applyPreview(now)
    fun reorderStops(order: List<String>, now: Long): Boolean = applyRoute(order, now)

    /** 순서 바꾸기에서 고정할 앞부분 길이. 정차 중이면 이미 방문한 지점과 지금 정류장까지 그대로 둔다. */
    fun lockedRouteCount(): Int = if (state.phase == TripPhase.ALIGHTING) state.currentStopIndex + 1 else 0

    fun manualRouteBlockers(now: Long): List<String> = buildList {
        if (state.phase != TripPhase.PREPARING && state.phase != TripPhase.ALIGHTING) add("순서는 운행 전이나 정차 중에만 바꿀 수 있음")
        addAll(stationaryBlockers(now))
    }

    /**
     * 선생님이 정한 방문 순서를 RouteObjective.FIXED로 반영한다. 운행 전에는 previewRoute/applyRoute를 거치고,
     * 정차 중에는 남은 지점만 바꾼다. 내비는 다음 목적지로 넘어갈 때 새 순서의 다음 지점을 받는다.
     */
    fun applyManualRoute(order: List<String>, reason: String?, now: Long): Boolean {
        manualRouteBlockers(now).firstOrNull()?.let { return reject(it) }
        if (!validOrder(order)) return reject("승인된 지점 전체를 중복 없이 포함해야 합니다.")
        val locked = lockedRouteCount()
        if (order.take(locked) != state.routeOrder.take(locked)) return reject("이미 방문했거나 정차 중인 지점의 순서는 바꿀 수 없습니다.")
        val proposal = try {
            RoutePlanner.optimize(state.origin.id, order, RoutePlanner.syntheticMatrix(state.origin, state.stops, RouteObjective.FIXED), RouteObjective.FIXED, state.returnToOrigin, state.routeVersion)
        } catch (error: IllegalArgumentException) { return reject(error.message ?: "비용 행렬을 확인하세요.") }
        val before = state.routeOrder
        if (state.phase == TripPhase.PREPARING) {
            if (!previewRoute(proposal) || !applyPreview(now)) return false
        } else {
            // 지금 정류장의 정차·직접 확인은 그대로 둔다. 바뀌는 것은 이후 목적지뿐이다.
            update(state.copy(routeOrder = order.toList(), routeVersion = state.routeVersion + 1, routeProposal = null, routeIsExample = false), "")
        }
        diagnostic(DiagnosticEntry(now, "방문 순서 변경", ResultStatus.TARGET_OBSERVED,
            "선생님이 정한 순서(고정 순서): ${before.joinToString(" → ")} ⇒ ${order.joinToString(" → ")}. 이유: ${reason ?: "선택 안 함"}. 이동 시간은 합성 값이며 실제 도로 시간이 아님"))
        return update(state.copy(routeObjective = RouteObjective.FIXED), if (locked == 0) "정한 순서로 방문합니다." else "남은 방문 순서를 바꿨습니다. 다음 정류장부터 새 순서로 안내합니다.")
    }
    fun invalidateConfirmation(reason: String, now: Long): Boolean { invalidate(now, reason); return true }
    fun editBlockReasons(now: Long): List<String> = buildList {
        if (state.phase == TripPhase.DRIVING) add("운행 화면에서는 명단과 상세 설정을 편집할 수 없음")
        if (state.phase == TripPhase.ENDED) add("종료된 운행 기록은 편집할 수 없음")
        addAll(stationaryBlockers(now))
    }
    fun unlockBlockReasons(now: Long): List<String> = unlockBlockers(now)

    private fun validOrder(order: List<String>): Boolean = order.size == state.stops.size && order.toSet().size == order.size && order.toSet() == state.stops.map { it.id }.toSet()

    companion object {
        const val CONFIRMATION_TTL = 5_000L
        const val MANUAL_CHECK_TTL = 30_000L
        const val REQUEST_TIMEOUT = 8_000L
        const val INSPECTION_TTL = 60_000L
        /** D34: 탈 아이 0명이면 운행하지 않음. */
        const val NO_TRIP_TODAY = "오늘 탈 어린이가 없어 운행하지 않습니다."
        private val REAL_SOURCES = setOf(Source.SDK, Source.EMULATOR_INPUT)
    }
}
