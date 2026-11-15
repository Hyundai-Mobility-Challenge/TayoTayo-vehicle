package com.hanhyo.pleosshuttle.domain

/** Single-owner domain state. Call on the ViewModel owner thread; adapters only deliver observations. */
class ShuttleEngine(initial: TripState = NoTrip.state()) {
    var state: TripState = initial
        private set
    var vehicle: VehicleSnapshot = VehicleSnapshot()
        private set
    var surroundings: Surroundings = Surroundings()
        private set
    var lastMessage: String = "운행을 준비해 주세요."
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
    /** V-08 기사 확인 원장 항목. 아이 기록이 아니므로 childId는 비우고, 서버 출결 이벤트로 보내지 않는다. */
    private fun driverCheckEntry(checked: Boolean, reason: String, now: Long) =
        LedgerEntry(id("driver", now), "", Attendance.ONBOARD, Attendance.ONBOARD, now, reason, driverCheck = checked)
    /**
     * D15: 기사 확인 뒤 출발 전에 출결(탑승 인원 포함)이나 출발 방문 순서가 바뀌면 체크를 푼다. 벨트 토글은 해당 없음
     * ([departureChangeKey]). 운행 준비가 아니거나 체크가 없으면 아무것도 하지 않는다.
     */
    private fun clearDriverCheckIfChanged(before: TripState, cause: String, now: Long) {
        if (before.phase != TripPhase.PREPARING || state.phase != TripPhase.PREPARING || state.driverCheckedAtMillis == null) return
        if (departureChangeKey(before) == departureChangeKey(state)) return
        state = state.copy(driverCheckedAtMillis = null, ledger = state.ledger + driverCheckEntry(false, DriverCheckRecords.autoCleared(cause), now))
        lastMessage = "$lastMessage · 출발 전 변경으로 기사 확인을 해제했습니다. 다시 확인해 주세요."
    }
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
    fun setRoutePolicy(objective: RouteObjective?, returnToOrigin: Boolean, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("방문 순서 기준은 운행 준비에서 정합니다.")
        if (vehicle.connected && stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        val before = state
        update(state.copy(routeObjective = objective, returnToOrigin = returnToOrigin, routeProposal = null, routeVersion = state.routeVersion + 1), "방문 순서 기준을 저장했습니다.")
        clearDriverCheckIfChanged(before, "방문 순서 기준 변경", now)
        return true
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
            add("차량에서 받은 속도·기어가 아니어서 운행 기록을 확인할 수 없음")
    }
    /** Kept separate: no ordinary attendance or route operation may inherit this condition. */
    fun doorStationaryBlockers(now: Long): List<String> = buildList {
        addAll(stationaryBlockers(now))
        if (vehicle.parkingBrakeSupported && (!vehicle.parkingBrake.isFresh(now) || vehicle.parkingBrake.value != true))
            add("문 잠금 해제는 지원되는 주차 브레이크 체결 확인이 필요함")
        if (state.integrationMode == IntegrationMode.SDK && vehicle.parkingBrakeSupported && vehicle.parkingBrake.source !in REAL_SOURCES)
            add("차량에서 받은 주차 정보가 아니어서 문 잠금 해제를 할 수 없음")
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
            if (state.integrationMode == IntegrationMode.SDK && vehicle.doorLocked.source !in REAL_SOURCES) add("차량에서 받은 문 상태가 아니어서 문 잠금 해제를 할 수 없음")
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
        // V-27 등원: 탑승은 정류장 도착·정차 확인 뒤, 인계받음을 기록한 아이만(출발 전 탑승 기록 없음).
        if (state.isPickup) return boardAtPickupStop(childId, now)
        if (state.phase != TripPhase.PREPARING) return reject("탑승 기록은 운행 준비에서 입력합니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.NOT_BOARDED) return reject("이미 탑승 또는 하차 기록이 있습니다. 정정 기능을 이용하세요.")
        return changeAttendance(child, Attendance.ONBOARD, "선생님 탑승 확인", now)
    }
    /**
     * V-02: 탑승 기록이 없는 아이를 선생님이 "미탑승"으로 확정한다(탑승 예정·미탑승 예정 모두). 자동 판정하지 않는다.
     * board()와 같은 조건(운행 준비 · 정차 확인, V-11)에서만 허용하고, 확정된 아이는 탑승 안내·하차 인원에서 빠진다.
     */
    fun confirmNotBoarded(childId: String, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("미탑승 확인은 운행 준비(탑승 단계)에서 입력합니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status == Attendance.CONFIRMED_NOT_BOARDED) return reject("이미 미탑승 확인한 어린이입니다.")
        if (child.status != Attendance.NOT_BOARDED) return reject("탑승 또는 하차 기록이 있습니다. 정정 기능을 이용하세요.")
        // V-26: 등원은 미탑승 예정(결석·직접 등원 알림) 아이만 출발 전에 확정한다. 알림이 없는 아이는 정류장에서(V-28).
        if (state.isPickup && child.expectedToBoard) return reject("미탑승 예정 알림이 없는 어린이는 정류장에서 미탑승 확인합니다.")
        val reason = if (child.expectedToBoard) "선생님 미탑승 확인"
            else "선생님 미탑승 확인 · 오늘 미탑승 예정(${notBoardingReasonLabel(child.notBoardingReason) ?: "사유 없음"})"
        return changeAttendance(child, Attendance.CONFIRMED_NOT_BOARDED, reason, now)
    }
    /** 미탑승 확인 취소. 출발 전(운행 준비)·정차 중에만. 아이는 다시 "탑승 기록 없음"으로 돌아간다. */
    fun undoNotBoarded(childId: String, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("미탑승 확인 취소는 출발 전에만 할 수 있습니다. 정정 기능을 이용하세요.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.CONFIRMED_NOT_BOARDED) return reject("미탑승 확인 기록이 없습니다.")
        return changeAttendance(child, Attendance.NOT_BOARDED, "미탑승 확인 취소", now)
    }
    private fun changeAttendance(child: ChildRecord, target: Attendance, reason: String, now: Long, stayOnboard: Boolean? = null,
                                 pickupAction: String? = null, receivedFromType: String? = null): Boolean {
        val event = LedgerEntry(id("ledger", now), child.id, child.status, target, now, reason, stayOnboard = stayOnboard,
            pickupAction = pickupAction, receivedFromType = receivedFromType)
        val before = state
        update(state.copy(
            // 새로 탑승(정정 포함)한 아이의 벨트는 항상 "미확인"에서 시작한다(V-05). 하차 등 다른 변경은 벨트 값을 건드리지 않는다.
            children = state.children.map { if (it.id == child.id) it.copy(status = target, beltConfirmed = if (target == Attendance.ONBOARD) false else it.beltConfirmed) else it },
            ledger = state.ledger + event,
            handoverChildIds = state.handoverChildIds - child.id,
            directCabinCheckAtMillis = null,
        ), "${child.name}: ${target.label} 저장")
        reincludeStopsWithRiders(now)
        clearDriverCheckIfChanged(before, "탑승 인원·출결 변경", now)
        return true
    }
    /** V-09 정류장 제외·다시 넣기 원장 항목. 아이 기록이 아니므로 childId는 비우고, 서버 출결 이벤트로 보내지 않는다. */
    private fun stopEntry(stopId: String, excluded: Boolean, reason: String, now: Long) =
        LedgerEntry(id("stop", now), "", Attendance.ONBOARD, Attendance.ONBOARD, now, reason, stopExcluded = excluded, stopId = stopId)
    private fun stopName(stopId: String) = state.stops.find { it.id == stopId }?.let(::stopLabel) ?: stopId

    /**
     * D17 넣기는 자동: 실제 상태 전이로 뺀 정류장에 다시 내릴 아이가 생기면 방문 순서에 다시 넣는다(운행 준비·정차 중).
     * routeVersion이 올라가 다음 목적지 안내 때 Pleos 경유지를 새 순서로 다시 요청한다(V-12).
     */
    private fun reincludeStopsWithRiders(now: Long) {
        val ids = StopExclusions.autoReinclude(state)
        if (ids.isEmpty()) return
        val message = lastMessage
        ids.forEach { id ->
            state = state.copy(routeOrder = StopExclusions.insertOrder(state, id), excludedStopIds = state.excludedStops - id,
                routeVersion = state.routeVersion + 1, routeProposal = null,
                ledger = state.ledger + stopEntry(id, false, StopExclusions.Records.AUTO_INCLUDED, now))
            diagnostic(DiagnosticEntry(now, "정류장 다시 넣기", ResultStatus.TARGET_OBSERVED, "자동 재포함: $id · 다시 내릴 어린이 있음"))
        }
        update(state, "$message · ${ids.joinToString { stopName(it) }} 정류장에 내릴 어린이가 생겨 방문 순서에 다시 넣었어요." +
            if (state.phase == TripPhase.PREPARING) "" else " 다음 출발 때 경로를 다시 안내해요.")
    }

    /**
     * V-09·D17 빈 정류장 빼기(선생님 확인). 아직 들르지 않았고 내릴 아이가 0명인 정류장만, 운행 준비·정차 중(V-11)에만.
     * 방문 순서·도착 예정·출발 요약·내비 경유지에서 빠지고, 원장에 기록한다. 서버에는 종료 때 skip으로 알린다.
     */
    fun excludeStop(stopId: String, now: Long): Boolean {
        manualRouteBlockers(now).firstOrNull()?.let { return reject(it) }
        val index = state.routeOrder.indexOf(stopId)
        if (index < 0) return reject("방문 순서에 없는 정류장입니다.")
        if (index < lockedRouteCount()) return reject("이미 방문했거나 정차 중인 정류장은 뺄 수 없습니다.")
        if (StopExclusions.hasRiders(state, stopId)) return reject("내릴 어린이가 있는 정류장은 뺄 수 없습니다.")
        val before = state
        update(state.copy(routeOrder = state.routeOrder - stopId, excludedStopIds = state.excludedStops + stopId,
            routeVersion = state.routeVersion + 1, routeProposal = null,
            ledger = state.ledger + stopEntry(stopId, true, StopExclusions.Records.EXCLUDED, now)),
            "${stopName(stopId)} 정류장을 방문 순서에서 뺐어요." + if (state.phase == TripPhase.PREPARING) "" else " 다음 출발 때 경로를 다시 안내해요.")
        diagnostic(DiagnosticEntry(now, "정류장 제외", ResultStatus.TARGET_OBSERVED, "선생님 확인으로 제외: $stopId · 내릴 어린이 없음"))
        clearDriverCheckIfChanged(before, "정류장 제외", now)
        return true
    }

    /** 뺀 정류장 다시 넣기(선생님). 운행 준비·정차 중에만. 지나온 지점 뒤 가장 가까운 위치에 넣는다. */
    fun includeStop(stopId: String, now: Long): Boolean {
        manualRouteBlockers(now).firstOrNull()?.let { return reject(it) }
        if (stopId !in state.excludedStops) return reject("뺀 정류장이 아닙니다.")
        val before = state
        update(state.copy(routeOrder = StopExclusions.insertOrder(state, stopId), excludedStopIds = state.excludedStops - stopId,
            routeVersion = state.routeVersion + 1, routeProposal = null,
            ledger = state.ledger + stopEntry(stopId, false, StopExclusions.Records.INCLUDED, now)),
            "${stopName(stopId)} 정류장을 방문 순서에 다시 넣었어요.")
        diagnostic(DiagnosticEntry(now, "정류장 다시 넣기", ResultStatus.TARGET_OBSERVED, "선생님 다시 넣기: $stopId"))
        clearDriverCheckIfChanged(before, "정류장 다시 넣기", now)
        return true
    }
    /**
     * V-05 벨트 착용 수동 확인. 탑승(ONBOARD) 중인 아이만, board()와 같은 정차 조건(V-11)에서만 기록한다.
     * 허용 단계: 운행 준비(출발 전)와 하차(정류장 정차 중, 재출발 전). 운행(DRIVING)은 조회 전용이다.
     * 출발을 막지 않으며(경고만), 기록은 기기 원장에만 남기고 서버로 보내지 않는다(D27).
     */
    fun setBeltConfirmed(childId: String, confirmed: Boolean, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING && state.phase != TripPhase.ALIGHTING)
            return reject("벨트 확인은 출발 전이나 정류장 정차 중에 기록합니다. 운행 중에는 조회만 할 수 있습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 기록이 있는 어린이만 벨트를 확인합니다.")
        val label = if (confirmed) "벨트 확인" else "벨트 미확인"
        if (child.beltConfirmed == confirmed) return reject("이미 $label 상태입니다.")
        val event = LedgerEntry(id("belt", now), child.id, Attendance.ONBOARD, Attendance.ONBOARD, now, "선생님 $label", belt = confirmed)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(beltConfirmed = confirmed) else it },
            ledger = state.ledger + event,
        ), "${child.name}: $label")
    }
    fun confirmBelt(childId: String, now: Long): Boolean = setBeltConfirmed(childId, true, now)
    fun unconfirmBelt(childId: String, now: Long): Boolean = setBeltConfirmed(childId, false, now)
    fun toggleBelt(childId: String, now: Long): Boolean {
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        return setBeltConfirmed(childId, !child.beltConfirmed, now)
    }
    fun correct(childId: String, target: Attendance, reason: String, now: Long): Boolean {
        if (state.phase == TripPhase.DRIVING || state.phase == TripPhase.ENDED) return reject("운행 중과 종료 후에는 명단을 정정할 수 없습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태 확인 후 정정할 수 있습니다.")
        if (reason.isBlank()) return reject("정정 사유가 필요합니다.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status == target) return reject("현재 기록과 같은 상태입니다.")
        if (target == Attendance.CONFIRMED_NOT_BOARDED) return reject("미탑승 확인은 탑승 단계의 [미탑승 확인]으로 기록합니다.")
        if (target == Attendance.ALIGHTED && (child.status != Attendance.ONBOARD || child.id !in state.handoverChildIds)) return reject("탑승 기록과 선생님의 보호자 대면 인계 확인이 필요합니다.")
        // V-27 등원: 정정으로도 인계받음 없이 탑승을 만들지 않는다.
        if (state.isPickup && target == Attendance.ONBOARD && !child.receivedAtStop) return reject("등원 탑승은 인계받음을 먼저 기록해야 합니다.")
        return changeAttendance(child, target, "정정: $reason", now)
    }
    /**
     * V-08 기사 확인 체크(D15·D37). 기사가 음성 요약을 듣고 말로 답하면 선생님이 체크한다(기사 조작 없음).
     * 운행 준비 · 정차 확인(V-11)에서만. 시각만 저장하고 출발을 막는 데 쓰지 않는다.
     */
    fun setDriverChecked(checked: Boolean, now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("기사 확인은 출발 전에 기록합니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        // 등원은 출발 때 탄 아이가 없다(정류장에서 태움). 탈 아이 0명은 위 noTripToday로 막는다.
        if (checked && !state.isPickup && state.onboardChildren.isEmpty()) return reject("탑승 기록이 있어야 기사 확인을 기록할 수 있습니다.")
        if ((state.driverCheckedAtMillis != null) == checked) return reject(if (checked) "이미 기사 확인을 기록했습니다." else "기사 확인 기록이 없습니다.")
        return update(state.copy(
            driverCheckedAtMillis = if (checked) now else null,
            ledger = state.ledger + driverCheckEntry(checked, if (checked) DriverCheckRecords.CHECKED else DriverCheckRecords.UNCHECKED, now),
        ), if (checked) "기사 확인을 기록했습니다(시각만 저장)." else "기사 확인을 해제했습니다.")
    }
    fun toggleDriverCheck(now: Long): Boolean = setDriverChecked(state.driverCheckedAtMillis == null, now)

    fun startDriving(now: Long): Boolean {
        if (state.phase != TripPhase.PREPARING) return reject("운행 준비 단계가 아닙니다.")
        if (state.noTripToday) return reject(NO_TRIP_TODAY)
        if (stationaryBlockers(now).isNotEmpty()) return reject("출발 전 정차 상태를 확인하세요.")
        // 등원(V-25)은 정류장에서 태우므로 출발 때 탑승 기록이 없어도 된다. 탈 아이 0명은 위 noTripToday로 막는다.
        if (!state.isPickup && state.onboardChildren.isEmpty()) return reject("선생님이 탑승 기록을 먼저 입력하세요.")
        // V-08: 기사 확인이 없어도 출발은 막지 않는다. 대신 운행 기록에 "기사 확인 없음"을 남긴다(D15).
        val missingDriverCheck = state.driverCheckedAtMillis == null
        invalidate(now, "운행 시작")
        val ledger = if (missingDriverCheck) state.ledger + driverCheckEntry(false, DriverCheckRecords.MISSING, now) else state.ledger
        return update(state.copy(phase = TripPhase.DRIVING, ledger = ledger),
            "운행 화면으로 전환했습니다. 실제 차량 출발을 제어하지 않습니다." + if (missingDriverCheck) " 기사 확인 없음으로 기록했습니다." else "")
    }
    fun chooseAlighting(now: Long): Boolean {
        if (state.phase != TripPhase.DRIVING) return reject("운행 화면에서 하차를 선택하세요.")
        if (state.currentStop == null) return reject("남은 하차 지점이 없습니다. 종료 확인으로 진행하세요.")
        val blockers = stationaryBlockers(now)
        if (blockers.isNotEmpty()) return reject(blockers.joinToString(" · "))
        invalidate(now, "도착·정차 확인")
        return update(state.copy(phase = TripPhase.ALIGHTING, unlockAttempt = null, handoverChildIds = emptySet()),
            if (state.isPickup) "도착·정차 확인. 선생님이 인계받음과 탑승을 기록합니다." else "도착·정차 확인. 선생님이 보호자 인계와 하차를 기록합니다.")
    }
    /** V-27 등원 정류장 [도착·정차 확인]. 하원 [도착·정차 확인]([chooseAlighting])과 같은 조건(운행 중 · 정차 확인)이다. */
    fun arriveAtPickupStop(now: Long): Boolean {
        if (!state.isPickup) return reject("등원 운행이 아닙니다.")
        return chooseAlighting(now)
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
        if (state.unlockAttempt != null) return reject("이 하차 절차에서는 문 잠금 해제를 한 번만 요청합니다. 결과를 확인하세요.")
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
        if (state.isPickup) return reject(PICKUP_NO_STOP_ALIGHTING)
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
        if (state.isPickup) return reject(PICKUP_NO_STOP_ALIGHTING)
        if (state.phase != TripPhase.ALIGHTING) return reject("도착·정차 확인 후 하차를 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("주변 위험 경고를 먼저 확인하세요.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.stopId != state.currentStop?.id) return reject("현재 하차 지점의 어린이가 아닙니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 기록이 없거나 이미 하차 기록이 있습니다.")
        if (childId !in state.handoverChildIds) return reject("선생님이 보호자 대면 인계를 먼저 확인해야 합니다.")
        return changeAttendance(child, Attendance.ALIGHTED, "선생님 보호자 대면 인계 후 직접 하차 기록", now)
    }
    /**
     * Attendance is a human record, independent of optional door-control experiments.
     * 마지막 하차 기록이면 지점을 자동 완료한다. 단, 이 지점에 동승 유지(V-21) 아이가 있으면 자동 완료하지 않고
     * [다음 지점으로] 단계에 머문다(같은 정류장에서 동승 유지를 취소할 기회를 남김).
     */
    fun recordAlightingAndContinue(childId: String, now: Long): Boolean {
        if (!alight(childId, now)) return false
        val here = state.currentStopChildren
        if (here.none { it.awaitingAlighting } && here.none { it.status == Attendance.ONBOARD && it.staysOnboard }) completeCurrentStop(now)
        return true // The attendance write remains valid even if a lab request delays transition.
    }
    /**
     * V-21 보호자 미도착 · 동승 유지(하원). 메인 인계 단계(인계 확인 전)에서만, 정차 확인(V-11) 중에만.
     * 아이는 탑승(ONBOARD) 그대로 두고 사유 코드(고정 선택지)만 표시한다. 이 지점은 이 아이의 하차 없이 완료할 수 있다.
     * 원장에 출결 기록(stayOnboard=true)을 남겨 서버에 GUARDIAN_NO_SHOW로 보낸다(D35 기관 쪽 자동 기록).
     */
    fun markStayOnboard(childId: String, reasonCode: String, now: Long): Boolean {
        // 등원 기관 담당자 부재 시 처리(OQ-29·D32)는 미확정이라 등원에서는 동승 유지를 기록하지 않는다.
        if (state.isPickup) return reject(PICKUP_NO_STAY_ONBOARD)
        if (state.phase != TripPhase.ALIGHTING) return reject("동승 유지는 정류장 도착·정차 확인 뒤 인계 단계에서 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        if (reasonCode !in StayOnboardReasons.ALL) return reject("동승 유지 사유를 선택해 주세요.")
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.stopId != state.currentStop?.id) return reject("현재 하차 지점의 어린이가 아닙니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 기록이 있는 어린이만 동승 유지할 수 있습니다.")
        if (child.staysOnboard) return reject("이미 동승 유지로 기록했습니다.")
        if (childId in state.handoverChildIds) return reject("보호자 인계 확인을 이미 기록했습니다. 하차 기록을 하거나 정정하세요.")
        val label = StayOnboardReasons.label(reasonCode)
        val event = LedgerEntry(id("stay", now), child.id, Attendance.ONBOARD, Attendance.ONBOARD, now, "보호자 미도착 · 동승 유지($label)", stayOnboard = true)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(stayOnboardReason = reasonCode) else it },
            ledger = state.ledger + event,
        ), "${child.name}: 동승 유지($label) 저장 · 복귀 후 기관에 인계해 주세요.")
    }
    /** V-21 동승 유지 취소. 같은 정류장(하차 단계)·정차 중에만. 아이는 다시 인계 확인 단계로 돌아간다. */
    fun undoStayOnboard(childId: String, now: Long): Boolean {
        if (state.isPickup) return reject(PICKUP_NO_STAY_ONBOARD)
        if (state.phase != TripPhase.ALIGHTING) return reject("동승 유지 취소는 그 정류장에 정차 중일 때만 할 수 있습니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.stopId != state.currentStop?.id) return reject("현재 하차 지점의 어린이가 아닙니다.")
        if (child.status != Attendance.ONBOARD || !child.staysOnboard) return reject("동승 유지 기록이 없습니다.")
        val event = LedgerEntry(id("stay", now), child.id, Attendance.ONBOARD, Attendance.ONBOARD, now, "동승 유지 취소", stayOnboard = false)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(stayOnboardReason = null) else it },
            ledger = state.ledger + event,
        ), "${child.name}: 동승 유지를 취소했습니다. 보호자 인계를 확인해 주세요.")
    }
    /**
     * V-22: 동승 유지로 복귀한 아이를 선생님이 기관 담당자에게 직접 인계하고 하차 기록한다. 종료 확인(복귀·정차) 단계에서만.
     * 서버에는 CHILD_RETURNED_TO_ORG(기관 복귀)로 보낸다(보호자 하차 알림 CHILD_DROPPED_OFF가 아님).
     */
    fun returnStayOnboardToOrg(childId: String, now: Long): Boolean {
        if (state.isPickup) return reject(PICKUP_NO_STAY_ONBOARD)
        if (state.phase != TripPhase.END_REVIEW) return reject("기관 인계는 복귀 후 종료 확인 단계에서 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.ONBOARD || !child.staysOnboard) return reject("동승 유지 중인 어린이만 기관 인계로 기록합니다.")
        return changeAttendance(child, Attendance.ALIGHTED, "동승 유지 · 선생님 기관 직접 인계 후 하차 기록", now, stayOnboard = true)
    }
    fun completeCurrentStop(now: Long): Boolean {
        if (state.phase != TripPhase.ALIGHTING || state.currentStop == null) return reject("현재 하차 지점이 없습니다.")
        if (state.isPickup) {
            // V-27: 등원 정류장은 그 정류장 아이가 모두 탑승 또는 미탑승 확인이어야 [다음 정류장으로].
            if (state.currentStopChildren.any { it.status == Attendance.NOT_BOARDED }) return reject(PICKUP_STOP_PENDING)
            // VS-18 [기관으로 출발]: 마지막 정류장이면 운행의 모든 아이가 탑승 또는 미탑승 확인이어야 한다(V-26).
            if (PickupFlow.isLastStop(state) && state.undecidedChildren.isNotEmpty()) return reject(PICKUP_INSTITUTION_PENDING)
        } else if (state.currentStopChildren.any { it.awaitingAlighting }) {
            // V-21: 동승 유지 아이는 처리된 것으로 본다(내리지 않고 계속 타고 감).
            return reject("현재 지점의 인계·하차 기록이 남아 있습니다.")
        }
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (surroundings.kind == SurroundingKind.WARNING) return reject("주변 위험 경고를 먼저 확인하세요.")
        if (state.unlockAttempt?.let { it.completedAtMillis == null } == true) return reject("문 잠금 해제 요청의 응답을 기다리고 있습니다.")
        diagnostic(DiagnosticEntry(now, "지점 기록 완료", ResultStatus.TARGET_OBSERVED,
            "현재 지점의 인계·하차 기록 완료로 다음 목적지 표시. 차량 출발 승인 또는 문·주변 안전 확인이 아님. 주변=${surroundings.kind}, 문요청=${state.unlockAttempt?.status}"))
        invalidate(now, "지점 기록 완료 후 다음 목적지 표시")
        return update(state.copy(phase = TripPhase.DRIVING, currentStopIndex = state.currentStopIndex + 1, handoverChildIds = emptySet()),
            if (state.isPickup) (if (state.currentStopIndex + 1 >= state.routeOrder.size) "정류장 탑승 기록 완료 · 기관으로 출발합니다." else "정류장 탑승 기록 완료 · 다음 지점을 안내합니다.") else "하차 기록 완료 · 다음 지점을 안내합니다.")
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
        if (state.isPickup) {
            // V-28: 처리 안 된 아이가 남은 채 떠나면 다음 정차 화면에서 선생님이 미탑승 확인한다(자동 처리 없음).
            if (state.currentStopChildren.any { it.status == Attendance.NOT_BOARDED }) add("현재 정류장에 탑승·미탑승 확인이 안 된 어린이가 있음")
        } else if (state.currentStopChildren.any { it.awaitingAlighting }) add("현재 지점에 미하차 기록이 남아 있음")
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
        // V-29: 등원의 종료 확인 단계는 [기관 도착 확인]으로만 들어간다.
        if (state.isPickup) return arriveAtInstitution(now)
        if (state.phase == TripPhase.PREPARING || state.phase == TripPhase.ENDED) return reject("운행 중인 기록의 종료를 확인합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        if (state.unlockAttempt != null && state.unlockAttempt?.completedAtMillis == null) return reject("문 요청 결과를 먼저 확인하세요.")
        invalidate(now, "운행 종료 검토")
        return update(state.copy(phase = TripPhase.END_REVIEW, directCabinCheckAtMillis = null), "명단과 선생님 직접 실내 확인을 각각 검토하세요.")
    }
    fun confirmCabinInspection(now: Long): Boolean {
        if (state.phase != TripPhase.END_REVIEW) return reject("종료 확인 단계가 아닙니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("정차 상태를 먼저 확인하세요.")
        return update(state.copy(directCabinCheckAtMillis = now), "선생님 직접 실내 확인 기록.")
    }
    fun finishTrip(now: Long): Boolean {
        if (state.phase != TripPhase.END_REVIEW) return reject("종료 확인 단계가 아닙니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject("종료 기록 전 정차 상태를 다시 확인하세요.")
        // V-29·S-19: 등원은 모든 아이가 기관 인계(하차 기록) 또는 미탑승 확인이어야 종료한다.
        if (state.isPickup && state.undecidedChildren.isNotEmpty()) return reject("탑승도 미탑승 확인도 없는 어린이가 있습니다. 미탑승 확인해 주세요.")
        if (state.onboardChildren.isNotEmpty()) return reject("기록상 미하차 어린이가 남아 있습니다. 기록을 확인·정정하세요.")
        val inspected = state.directCabinCheckAtMillis ?: return reject("선생님의 직접 실내 확인이 필요합니다.")
        if (now < inspected || now - inspected > INSPECTION_TTL) return reject("직접 실내 확인이 오래되어 다시 확인해야 합니다.")
        // V-07 삭제(D39): 좌석 점유 관측은 종료 기록에 넣지 않는다. 점유 필드는 저장 호환용으로 빈 값만 채운다.
        val record = EndRecord(now, inspected, state.onboardChildren.map { it.id }, occupiedAreas = emptyList(), unknownOrStaleAreas = emptyList(),
            vehicleConnected = vehicle.connected, sensorCoverageKnown = false, observedVehicle = vehicle)
        return update(state.copy(phase = TripPhase.ENDED, endRecord = record), "운행 종료 기록 저장. 선생님 직접 실내 확인을 남기며 안전을 자동 판정하지 않습니다.")
    }

    // ---------------- 등원(V-25~V-29). 하원 흐름은 위 함수들 그대로이며, 아래는 state.isPickup일 때만 동작한다. ----------------

    /** 등원 정류장 기록 공통 조건: 등원 · 정류장 도착·정차 확인(ALIGHTING) · 정차 확인(V-11) · 현재 정류장 아이. */
    private fun pickupStopChild(childId: String, now: Long, what: String): ChildRecord? {
        if (!state.isPickup) { reject("$what 등원 운행에서 기록합니다."); return null }
        if (state.phase != TripPhase.ALIGHTING) { reject("$what 정류장 도착·정차 확인 뒤 기록합니다."); return null }
        val blockers = stationaryBlockers(now)
        if (blockers.isNotEmpty()) { reject(blockers.joinToString(" · ")); return null }
        val child = state.children.find { it.id == childId }
        if (child == null) { reject("명단에 없는 어린이입니다."); return null }
        if (child.stopId != state.currentStop?.id) { reject("현재 정류장의 어린이가 아닙니다."); return null }
        return child
    }

    private fun receivedEntry(child: ChildRecord, receiverType: String, now: Long, reason: String) =
        LedgerEntry(id("pickup", now), child.id, child.status, child.status, now, reason,
            pickupAction = PickupActions.RECEIVED, receivedFromType = receiverType)

    /**
     * V-27 [인계받음]: 정류장에서 아이를 넘겨준 사람 유형(보호자 / 대리 / 기타)을 기록한다. 이름은 받지 않는다(7절).
     * 출결 상태는 탑승 전 그대로이고, 이 기록 뒤에만 [탑승]([board])을 기록할 수 있다.
     */
    fun recordReceived(childId: String, receiverType: String, now: Long): Boolean {
        val child = pickupStopChild(childId, now, "인계받음은") ?: return false
        if (receiverType !in PickupReceivers.ALL) return reject("아이를 넘겨준 사람 유형을 선택해 주세요.")
        if (child.status != Attendance.NOT_BOARDED) return reject("탑승 전인 어린이만 인계받음을 기록합니다. 미탑승 확인한 어린이는 탑승으로 정정하세요.")
        if (child.receivedAtStop) return reject("이미 인계받음을 기록했습니다.")
        val label = PickupReceivers.label(receiverType)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(receivedFromType = receiverType) else it },
            ledger = state.ledger + receivedEntry(child, receiverType, now, "정류장 인계받음 · $label"),
        ), "${child.name}: 인계받음($label) 저장 · 탑승을 기록해 주세요.")
    }

    /** V-27 [탑승]: 인계받음 뒤에만. 벨트는 미확인에서 시작한다(V-05). */
    private fun boardAtPickupStop(childId: String, now: Long): Boolean {
        val child = pickupStopChild(childId, now, "등원 탑승은") ?: return false
        if (child.status != Attendance.NOT_BOARDED) return reject("이미 탑승 또는 미탑승 확인 기록이 있습니다. 정정 기능을 이용하세요.")
        if (!child.receivedAtStop) return reject("인계받음을 먼저 기록해야 탑승을 기록할 수 있습니다.")
        return changeAttendance(child, Attendance.ONBOARD, "등원 정류장 인계받음 뒤 선생님 탑승 기록", now,
            pickupAction = PickupActions.BOARDED, receivedFromType = child.receivedFromType)
    }

    /**
     * V-28 [보호자에게 도착 알림]: 호출 시각만 기록한다(서버 guardian-calls로 전송). 재호출·대기 한도는 없다(D30).
     * 보호자 응답("오늘 안 타요" 포함)은 미탑승을 확정하지 않는다(7절).
     */
    fun callGuardian(childId: String, now: Long): Boolean {
        val child = pickupStopChild(childId, now, "보호자 도착 알림은") ?: return false
        if (child.status != Attendance.NOT_BOARDED) return reject("탑승 전인 어린이만 보호자에게 도착 알림을 보냅니다.")
        if (child.receivedAtStop) return reject("이미 인계받은 어린이입니다. 탑승을 기록해 주세요.")
        if (child.guardianCalled) return reject("이미 도착 알림을 보냈습니다(재호출 없음).")
        val event = LedgerEntry(id("call", now), child.id, child.status, child.status, now, "보호자에게 도착 알림",
            pickupAction = PickupActions.GUARDIAN_CALLED)
        return update(state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(guardianCalledAtMillis = now) else it },
            ledger = state.ledger + event,
        ), "${child.name}: 보호자에게 도착 알림을 보냈습니다.")
    }

    /**
     * V-28 정류장 [미탑승 확인]. [notifyGuardian]이면 보호자 미탑승 안내 전송을 함께 선택한 것(D30). 기관 웹에는 자동 기록(D31).
     * 정류장 도착·정차 확인 중(그 정류장 또는 이미 지난 정류장 아이 — 처리 안 된 채 떠난 아이는 다음 정차에서 확인)과
     * 기관 도착 확인 뒤(종료 확인 단계)에만, 정차 확인(V-11) 중에만. 호출하지 않았어도 막지 않는다(경고는 [shouldSuggestGuardianCall]).
     */
    fun confirmNotBoardedAtStop(childId: String, notifyGuardian: Boolean, now: Long): Boolean {
        if (!state.isPickup) return reject("정류장 미탑승 확인은 등원 운행에서 기록합니다.")
        if (state.phase != TripPhase.ALIGHTING && state.phase != TripPhase.END_REVIEW) return reject("정류장 미탑승 확인은 정차 확인 뒤 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (state.phase == TripPhase.ALIGHTING && state.routeOrder.indexOf(child.stopId) > state.currentStopIndex)
            return reject("아직 도착하지 않은 정류장의 어린이입니다.")
        if (child.status == Attendance.CONFIRMED_NOT_BOARDED) return reject("이미 미탑승 확인한 어린이입니다.")
        if (child.status != Attendance.NOT_BOARDED) return reject("탑승 또는 하차 기록이 있습니다. 정정 기능을 이용하세요.")
        if (child.receivedAtStop) return reject("인계받은 어린이입니다. 탑승을 기록해 주세요.")
        val reason = buildString {
            append("등원 정류장 미탑승 확인")
            if (!child.guardianCalled) append(" · 도착 알림 없이")
            append(if (notifyGuardian) " · 보호자 미탑승 안내 보냄" else " · 보호자 미탑승 안내 안 보냄")
        }
        return changeAttendance(child, Attendance.CONFIRMED_NOT_BOARDED, reason, now,
            pickupAction = if (notifyGuardian) PickupActions.NOT_BOARDED_NOTIFY else PickupActions.NOT_BOARDED_SILENT)
    }

    /**
     * V-28 [탑승으로 정정]: 정류장 미탑승 확인 뒤 그 정류장을 떠나기 전에 아이가 나오면, 넘겨준 사람 유형과 함께
     * 인계받음 → 탑승 순서로 기록한다(3.5절 정정 규칙).
     */
    fun correctNotBoardedToBoarded(childId: String, receiverType: String, now: Long): Boolean {
        val child = pickupStopChild(childId, now, "탑승으로 정정은") ?: return false
        if (receiverType !in PickupReceivers.ALL) return reject("아이를 넘겨준 사람 유형을 선택해 주세요.")
        if (child.status != Attendance.CONFIRMED_NOT_BOARDED) return reject("미탑승 확인한 어린이만 탑승으로 정정합니다.")
        val label = PickupReceivers.label(receiverType)
        state = state.copy(
            children = state.children.map { if (it.id == child.id) it.copy(receivedFromType = receiverType) else it },
            ledger = state.ledger + receivedEntry(child, receiverType, now, "정정: 정류장 인계받음 · $label"),
        )
        val received = state.children.first { it.id == child.id }
        return changeAttendance(received, Attendance.ONBOARD, "정정: 미탑승 확인 뒤 정류장에서 탑승", now,
            pickupAction = PickupActions.BOARDED, receivedFromType = receiverType)
    }

    /**
     * V-29 [기관 도착 확인]: 정류장 기록을 모두 마치고(남은 정류장 없음) 기관 앞에 정차했을 때. 종료 확인 단계(V-22)로 들어간다.
     * 처리 안 된 아이가 남아 있어도 막지 않는다(이 화면에서 미탑승 확인, 종료는 [finishTrip]이 막음).
     */
    fun arriveAtInstitution(now: Long): Boolean {
        if (!state.isPickup) return reject("기관 도착 확인은 등원 운행에서 기록합니다.")
        if (state.phase != TripPhase.DRIVING) return reject("정류장 기록을 마치고 기관으로 출발한 뒤 기관 도착을 확인합니다.")
        if (state.currentStop != null) return reject("남은 정류장이 있습니다. 정류장 기록을 먼저 마치세요.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        if (state.unlockAttempt != null && state.unlockAttempt?.completedAtMillis == null) return reject("문 요청 결과를 먼저 확인하세요.")
        invalidate(now, "기관 도착 확인")
        return update(state.copy(phase = TripPhase.END_REVIEW, directCabinCheckAtMillis = null),
            "기관 도착 확인. 아이마다 기관 담당자 인계를 확인하고 하차를 기록하세요.")
    }

    /** V-29 아이별 기관 담당자 [인계 확인] → [하차 기록](D38 아이마다 따로). 기관 도착 확인 뒤 · 정차 중에만. */
    fun handoverToInstitution(childId: String, now: Long): Boolean {
        if (!state.isPickup) return reject("기관 인계는 등원 운행에서 기록합니다.")
        if (state.phase != TripPhase.END_REVIEW) return reject("기관 도착 확인 뒤 기관 인계를 기록합니다.")
        if (stationaryBlockers(now).isNotEmpty()) return reject(stationaryBlockers(now).joinToString(" · "))
        val child = state.children.find { it.id == childId } ?: return reject("명단에 없는 어린이입니다.")
        if (child.status != Attendance.ONBOARD) return reject("탑승 중인 어린이만 기관 인계로 하차 기록합니다.")
        return changeAttendance(child, Attendance.ALIGHTED, "기관 담당자 인계 확인 후 하차 기록", now,
            pickupAction = PickupActions.INSTITUTION_HANDOVER)
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
        val before = state
        update(state.copy(routeOrder = order.toList(), routeVersion = state.routeVersion + 1, routeProposal = null, routeIsExample = false), "방문 순서를 적용했습니다. 새 하차 지점은 추가하지 않았습니다.")
        // 예시 순서를 [운행 시작]에서 자동 적용하는 경우는 보이던 순서 그대로라 해제하지 않는다(departureRouteOrder 비교).
        clearDriverCheckIfChanged(before, "방문 순서 변경", now)
        return true
    }
    fun applyPreview(now: Long): Boolean {
        val proposal = state.routeProposal ?: return reject("적용할 계산 결과가 없습니다.")
        if (proposal.routeVersion != state.routeVersion) return reject("계산 후 경로 조건이 바뀌었습니다. 다시 계산하세요.")
        return applyRoute(proposal.stopOrder, now)
    }
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
        val hadDriverCheck = state.driverCheckedAtMillis != null
        if (state.phase == TripPhase.PREPARING) {
            if (!previewRoute(proposal) || !applyPreview(now)) return false
        } else {
            // 지금 정류장의 정차·직접 확인은 그대로 둔다. 바뀌는 것은 이후 목적지뿐이다.
            update(state.copy(routeOrder = order.toList(), routeVersion = state.routeVersion + 1, routeProposal = null, routeIsExample = false), "")
        }
        diagnostic(DiagnosticEntry(now, "방문 순서 변경", ResultStatus.TARGET_OBSERVED,
            "선생님이 정한 순서(고정 순서): ${before.joinToString(" → ")} ⇒ ${order.joinToString(" → ")}. 이유: ${reason ?: "선택 안 함"}. 이동 시간은 합성 값이며 실제 도로 시간이 아님"))
        val cleared = if (hadDriverCheck && state.driverCheckedAtMillis == null) " 기사 확인이 해제되어 다시 확인이 필요합니다." else ""
        return update(state.copy(routeObjective = RouteObjective.FIXED), (if (locked == 0) "정한 순서로 방문합니다." else "남은 방문 순서를 바꿨습니다. 다음 정류장부터 새 순서로 안내합니다.") + cleared)
    }
    fun invalidateConfirmation(reason: String, now: Long): Boolean { invalidate(now, reason); return true }
    fun editBlockReasons(now: Long): List<String> = buildList {
        if (state.phase == TripPhase.DRIVING) add("운행 화면에서는 명단과 상세 설정을 편집할 수 없음")
        if (state.phase == TripPhase.ENDED) add("종료된 운행 기록은 편집할 수 없음")
        addAll(stationaryBlockers(now))
    }
    fun unlockBlockReasons(now: Long): List<String> = unlockBlockers(now)

    /** 방문 순서는 승인된 정류장 중 뺀 정류장(V-09)을 제외한 전체를 중복 없이 포함해야 한다. */
    private fun validOrder(order: List<String>): Boolean {
        val approved = state.stops.map { it.id }.toSet() - state.excludedStops.toSet()
        return order.size == approved.size && order.toSet().size == order.size && order.toSet() == approved
    }

    companion object {
        const val CONFIRMATION_TTL = 5_000L
        const val MANUAL_CHECK_TTL = 30_000L
        const val REQUEST_TIMEOUT = 8_000L
        const val INSPECTION_TTL = 60_000L
        /** D34: 탈 아이 0명이면 운행하지 않음. */
        const val NO_TRIP_TODAY = "오늘 탈 어린이가 없어 운행하지 않습니다."
        const val PICKUP_NO_STOP_ALIGHTING = "등원에서는 정류장 하차가 없습니다. 기관 도착 확인 뒤 기관 인계로 기록하세요."
        const val PICKUP_NO_STAY_ONBOARD = "등원의 기관 담당자 부재 처리는 아직 정해지지 않았습니다(OQ-29)."
        const val PICKUP_STOP_PENDING = "정류장의 어린이가 모두 탑승 또는 미탑승 확인이어야 다음 정류장으로 갈 수 있습니다."
        const val PICKUP_INSTITUTION_PENDING = "운행의 어린이가 모두 탑승 또는 미탑승 확인이어야 기관으로 출발할 수 있습니다. 앞 정류장 어린이를 먼저 확인해 주세요."
        private val REAL_SOURCES = setOf(Source.SDK, Source.EMULATOR_INPUT)
    }
}
