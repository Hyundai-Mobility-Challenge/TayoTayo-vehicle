package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

class ShuttleEngineTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T, time: Long = now, source: Source = Source.SDK) =
        Observation(value, source, time, ResultStatus.TARGET_OBSERVED)
    private fun parked(time: Long = now, locked: Boolean = true, source: Source = Source.SDK) = VehicleSnapshot(
        connected = true, speedKph = observed(0.0, time, source), gear = observed(Gear.PARK, time, source),
        parkingBrake = observed(true, time, source), doorLocked = observed(locked, time, source),
        seats = listOf(SeatObservation(1, observed(false, time, source), observed(false, time, source))), connectionGeneration = 1,
    )
    private fun ready(): ShuttleEngine = ShuttleEngine(SampleTrip.create()).also { it.updateVehicle(parked(), now) }
    private fun driving(): ShuttleEngine = ready().also {
        assertTrue(it.board("child-a", now)); assertTrue(it.startDriving(now))
    }
    private fun alighting(): ShuttleEngine = driving().also {
        assertTrue(it.chooseAlighting(now)); assertTrue(it.confirmSurroundings(now))
    }
    private fun requested(): Pair<ShuttleEngine, UnlockRequest> {
        val engine = alighting()
        assertTrue(engine.prepareUnlock(now))
        return engine to requireNotNull(engine.consumeUnlock(now))
    }

    @Test fun `정차 관측만으로 하차 모드나 문 요청을 생성하지 않는다`() {
        val engine = driving()
        engine.updateVehicle(parked(now + 10), now + 10)
        assertEquals(TripPhase.DRIVING, engine.state.phase)
        assertNull(engine.state.unlockConfirmation)
        assertNull(engine.state.unlockAttempt)
        assertNull(engine.consumeUnlock(now + 10))
    }

    @Test fun `탑승 전 하차와 중복 탑승은 추가 기록을 만들지 않는다`() {
        val engine = ready()
        assertFalse(engine.alight("child-a", now))
        assertTrue(engine.state.ledger.isEmpty())
        assertTrue(engine.board("child-a", now))
        assertFalse(engine.board("child-a", now + 1))
        assertEquals(1, engine.state.ledger.size)
        assertEquals(Attendance.ONBOARD, engine.state.children.first().status)
    }

    @Test fun `탑승 정정은 원래 기록과 사유를 이력에 남긴다`() {
        val engine = ready()
        engine.board("child-a", now)
        assertFalse(engine.correct("child-a", Attendance.NOT_BOARDED, "", now + 1))
        assertTrue(engine.correct("child-a", Attendance.NOT_BOARDED, "다른 가상 어린이를 선택함", now + 2))
        assertEquals(2, engine.state.ledger.size)
        assertEquals(Attendance.ONBOARD, engine.state.ledger.first().after)
        assertEquals(Attendance.NOT_BOARDED, engine.state.ledger.last().after)
        assertTrue(engine.state.ledger.last().reason.contains("다른 가상 어린이"))
    }

    @Test fun `좌석 벨트 관측은 어린이 기록을 바꾸지 않는다`() {
        val engine = ready()
        engine.updateVehicle(parked().copy(seats = listOf(SeatObservation(33, beltFastened = observed(true)))), now)
        assertTrue(engine.state.children.all { it.status == Attendance.NOT_BOARDED })
        assertTrue(engine.state.ledger.isEmpty())
        assertEquals(33, engine.vehicle.seats.single().areaId)
    }

    @Test fun `이동 중이거나 기어를 모르면 하차 선택을 거절한다`() {
        val moving = driving()
        moving.updateVehicle(parked().copy(speedKph = observed(15.0), gear = observed(Gear.DRIVE)), now)
        assertFalse(moving.chooseAlighting(now))
        assertEquals(TripPhase.DRIVING, moving.state.phase)
        val unknown = driving()
        unknown.updateVehicle(parked().copy(gear = Observation()), now)
        assertFalse(unknown.chooseAlighting(now))
        assertTrue(unknown.lastMessage.contains("기어 미확인"))
    }

    @Test fun `오래된 속도와 음수 시간 차이를 신선한 입력으로 보지 않는다`() {
        val engine = driving()
        assertFalse(engine.chooseAlighting(now + 5_001))
        assertFalse(observed(0.0, now + 1).isFresh(now))
        assertFalse(observed(Double.NaN).value!!.isFinite())
    }

    @Test fun `주변 위험 정보가 있으면 직접 확인과 최종 확인을 거절한다`() {
        val engine = alighting()
        engine.updateSurroundings(Surroundings(SurroundingKind.WARNING, Source.SIMULATED, now), now)
        assertFalse(engine.confirmSurroundings(now))
        assertFalse(engine.prepareUnlock(now))
        assertFalse(engine.manualDoorHandling("수동 대안", now))
        assertNull(engine.state.unlockAttempt)
    }

    @Test fun `주변 정보가 없어도 수동 확인은 센서 출처와 미확인 상태를 바꾸지 않는다`() {
        val engine = alighting()
        assertEquals(SurroundingKind.UNKNOWN, engine.surroundings.kind)
        assertEquals(Source.SDK, engine.surroundings.source)
        assertNotNull(engine.state.manualSurroundingsCheckAtMillis)
        assertTrue(engine.prepareUnlock(now))
        assertEquals(SurroundingKind.UNKNOWN, engine.surroundings.kind)
    }

    @Test fun `최종 확인과 요청 연타는 하나의 문 요청만 만든다`() {
        val engine = alighting()
        assertTrue(engine.prepareUnlock(now))
        assertFalse(engine.prepareUnlock(now))
        val request = engine.consumeUnlock(now)
        assertNotNull(request)
        assertNull(engine.consumeUnlock(now))
        assertEquals(request!!.id, engine.state.unlockAttempt!!.request.id)
        assertEquals(1, engine.state.diagnostics.count { it.feature == "문 해제" && it.requested == "false" })
    }

    @Test fun `이동과 연결 해제와 만료는 준비한 해제 확인을 폐기한다`() {
        val moving = alighting().also { assertTrue(it.prepareUnlock(now)) }
        moving.updateVehicle(parked().copy(speedKph = observed(1.0)), now + 1)
        assertNull(moving.consumeUnlock(now + 1))
        val disconnected = alighting().also { assertTrue(it.prepareUnlock(now)) }
        disconnected.updateVehicle(VehicleSnapshot(), now + 1)
        assertNull(disconnected.consumeUnlock(now + 1))
        val expired = alighting().also { assertTrue(it.prepareUnlock(now)) }
        expired.tick(now + ShuttleEngine.CONFIRMATION_TTL + 1)
        assertNull(expired.consumeUnlock(now + ShuttleEngine.CONFIRMATION_TTL + 1))
    }

    @Test fun `연결이 다시 살아나도 이전 최종 확인으로 요청하지 않는다`() {
        val engine = alighting()
        engine.prepareUnlock(now)
        engine.updateVehicle(VehicleSnapshot(), now + 1)
        engine.updateVehicle(parked(now + 2).copy(connectionGeneration = 2), now + 2)
        assertNull(engine.consumeUnlock(now + 2))
        assertNull(engine.state.manualSurroundingsCheckAtMillis)
    }

    @Test fun `요청 수락 후 잠금 유지 관측은 해제 완료가 아니다`() {
        val (engine, request) = requested()
        assertTrue(engine.onUnlockResult(request.id, ResultStatus.ACCEPTED, true, null, now + 1))
        assertEquals(ResultStatus.ACCEPTED, engine.state.unlockAttempt!!.status)
        assertNull(engine.state.unlockAttempt!!.completedAtMillis)
        engine.tick(now + ShuttleEngine.REQUEST_TIMEOUT + 1)
        assertEquals(ResultStatus.TIMEOUT, engine.state.unlockAttempt!!.status)
        assertEquals(Attendance.ONBOARD, engine.state.children.first().status)
    }

    @Test fun `해제 완료 응답도 관측값이 잠금이면 부분 반영으로 남긴다`() {
        val (engine, request) = requested()
        engine.onUnlockResult(request.id, ResultStatus.TARGET_OBSERVED, true, null, now + 1)
        assertEquals(ResultStatus.PARTIAL, engine.state.unlockAttempt!!.status)
        assertTrue(engine.state.unlockAttempt!!.observedLocked!!)
    }

    @Test fun `확인 무효화 뒤 늦은 해제 관측은 현재 완료로 사용하지 않는다`() {
        val (engine, request) = requested()
        engine.updateVehicle(parked().copy(speedKph = observed(2.0)), now + 1)
        assertFalse(engine.onUnlockResult(request.id, ResultStatus.TARGET_OBSERVED, false, null, now + 2))
        assertNotEquals(ResultStatus.TARGET_OBSERVED, engine.state.unlockAttempt!!.status)
        assertTrue(engine.state.unlockAttempt!!.invalidated)
        assertTrue(engine.state.diagnostics.any { it.feature == "늦은 문 응답" })
    }

    @Test fun `차량에서 받지 않은 입력은 정차 확인과 해제 요청을 만들지 못한다`() {
        val engine = ShuttleEngine(SampleTrip.create().copy(integrationMode = IntegrationMode.SDK, phase = TripPhase.DRIVING))
        engine.updateVehicle(parked(source = Source.SIMULATED), now)
        assertFalse(engine.chooseAlighting(now))
        assertTrue(engine.stationaryBlockers(now).any { it.contains("차량에서 받은 속도·기어가 아니어서") })
        assertNull(engine.consumeUnlock(now))
    }

    @Test fun `해제 요청은 운행의 연동 방식을 그대로 기록한다`() {
        val (engine, request) = requested()
        assertEquals(IntegrationMode.SDK, request.mode)
        assertEquals(engine.state.integrationMode, request.mode)
    }

    @Test fun `문 해제 관측은 어린이를 자동 하차 처리하지 않는다`() {
        val (engine, request) = requested()
        engine.onUnlockResult(request.id, ResultStatus.TARGET_OBSERVED, false, null, now + 1)
        assertEquals(Attendance.ONBOARD, engine.state.children.first().status)
        assertFalse(engine.alight("child-a", now + 1))
        assertTrue(engine.confirmHandover("child-a", now + 1))
        assertTrue(engine.alight("child-a", now + 1))
        assertFalse(engine.alight("child-a", now + 1))
        assertEquals(Attendance.ALIGHTED, engine.state.children.first().status)
    }

    @Test fun `복원 후에도 실제 인계와 하차는 문 시험 결과와 독립적으로 기록한다`() {
        val (engine, request) = requested()
        engine.onUnlockResult(request.id, ResultStatus.TARGET_OBSERVED, false, null, now)
        engine.restore(engine.state, now + 1)
        engine.updateVehicle(parked(now + 2, locked = false), now + 2)
        assertTrue(engine.confirmHandover("child-a", now + 2))
        assertTrue(engine.state.unlockAttempt!!.invalidated)
        assertNull(engine.state.manualDoorReason)
        assertTrue(engine.alight("child-a", now + 2))
    }

    @Test fun `문 시험 오류를 성공으로 바꾸지 않고 실제 인계와 하차를 기록한다`() {
        val (engine, request) = requested()
        engine.onUnlockResult(request.id, ResultStatus.ERROR, true, "EMULATOR_ERROR", now)
        assertTrue(engine.confirmHandover("child-a", now))
        assertEquals(ResultStatus.ERROR, engine.state.unlockAttempt!!.status)
        assertNull(engine.state.manualDoorReason)
        assertTrue(engine.alight("child-a", now))
    }

    @Test fun `이미 열린 문에서는 수동 대안과 보호자 인계로 하차를 기록한다`() {
        val engine = alighting()
        engine.updateVehicle(parked(locked = false), now)
        assertFalse(engine.prepareUnlock(now))
        assertTrue(engine.manualDoorHandling("이미 잠금 해제된 문을 직접 확인함", now))
        assertNull(engine.state.unlockAttempt)
        assertTrue(engine.confirmHandover("child-a", now))
        assertTrue(engine.alight("child-a", now))
        assertTrue(engine.state.diagnostics.any { it.feature == "문 수동 대안" && it.status == ResultStatus.UNVERIFIED })
    }

    @Test fun `미하차 기록이 남은 복귀는 사유를 요구하고 기록을 보존한다`() {
        val engine = alighting()
        assertFalse(engine.resumeDriving("", now))
        assertTrue(engine.resumeDriving("이 지점 인계를 보류하고 종료 확인에서 재검토", now))
        assertEquals(TripPhase.DRIVING, engine.state.phase)
        assertEquals(1, engine.state.currentStopIndex)
        assertEquals(Attendance.ONBOARD, engine.state.children.first().status)
        assertTrue(engine.state.diagnostics.any { it.feature == "운행 복귀" && it.message.contains("보류") })
    }

    @Test fun `복원은 미완료 운행과 기록을 유지하고 새 확인을 요구한다`() {
        val engine = alighting()
        engine.prepareUnlock(now)
        val saved = engine.state
        val restored = ShuttleEngine(SampleTrip.create())
        restored.restore(saved, now + 100)
        assertEquals(TripPhase.ALIGHTING, restored.state.phase)
        assertEquals(saved.ledger, restored.state.ledger)
        assertEquals(saved.children, restored.state.children)
        assertNull(restored.state.unlockConfirmation)
        assertNull(restored.state.manualSurroundingsCheckAtMillis)
        assertFalse(restored.vehicle.connected)
        assertNull(restored.consumeUnlock(now + 100))
    }

    @Test fun `백그라운드 복귀에 기존 허가와 차량 신선도를 재사용하지 않는다`() {
        val engine = alighting()
        engine.prepareUnlock(now)
        engine.background(now + 1)
        assertNull(engine.state.unlockConfirmation)
        assertFalse(engine.vehicle.connected)
        assertEquals(TripPhase.ALIGHTING, engine.state.phase)
    }

    @Test fun `비어 있는 좌석은 기록상 미하차나 직접 실내 확인을 대체하지 않는다`() {
        val engine = driving()
        assertTrue(engine.beginEndReview(now))
        assertFalse(engine.finishTrip(now))
        assertTrue(engine.confirmCabinInspection(now))
        assertFalse(engine.finishTrip(now))
        assertEquals(TripPhase.END_REVIEW, engine.state.phase)
    }

    @Test fun `종료 기록은 좌석 관측으로 어린이 잔류를 판정하지 않는다(V-07 삭제)`() {
        val initial = SampleTrip.create().copy(phase = TripPhase.END_REVIEW, children = SampleTrip.create().children.map { it.copy(status = Attendance.ALIGHTED) })
        val engine = ShuttleEngine(initial)
        engine.updateVehicle(parked().copy(seats = listOf(SeatObservation(1, observed(true), areaLabel = "ROW_1_LEFT"), SeatObservation(2, areaLabel = "ROW_1_RIGHT"))), now)
        assertFalse(engine.finishTrip(now))
        assertTrue(engine.confirmCabinInspection(now))
        assertTrue(engine.finishTrip(now))
        // 예전 형식의 좌석 관측이 들어와도 저장 호환용 필드는 비워 둔다(D39).
        assertEquals(emptyList<Int>(), engine.state.endRecord!!.occupiedAreas)
        assertEquals(emptyList<Int>(), engine.state.endRecord!!.unknownOrStaleAreas)
        assertEquals(emptyList<String>(), engine.state.endRecord!!.occupiedAreaLabels)
        assertEquals(emptyList<String>(), engine.state.endRecord!!.unknownOrStaleAreaLabels)
        assertFalse(engine.state.endRecord!!.statement.contains("잔류 아동 없음"))
    }

    @Test fun `차량 이동과 확인 만료는 이전 실내 확인으로 종료하지 못하게 한다`() {
        val initial = SampleTrip.create().copy(phase = TripPhase.END_REVIEW)
        val engine = ShuttleEngine(initial)
        engine.updateVehicle(parked(), now)
        engine.confirmCabinInspection(now)
        engine.updateVehicle(parked().copy(speedKph = observed(1.0)), now + 1)
        engine.updateVehicle(parked(now + 2), now + 2)
        assertNull(engine.state.directCabinCheckAtMillis)
        assertFalse(engine.finishTrip(now + 2))
        engine.confirmCabinInspection(now + 2)
        engine.tick(now + ShuttleEngine.INSPECTION_TTL + 3)
        assertNull(engine.state.directCabinCheckAtMillis)
    }

    @Test fun `정상 흐름은 순서 적용부터 인계와 직접 확인을 거쳐 종료된다`() {
        val engine = ready()
        engine.state.children.forEach { assertTrue(engine.board(it.id, now)) }
        assertTrue(engine.calculateRoute(now))
        assertTrue(engine.state.routeProposal!!.synthetic)
        assertTrue(engine.applyRouteProposal(now))
        assertTrue(engine.startDriving(now))
        repeat(engine.state.stops.size) {
            assertTrue(engine.chooseAlighting(now))
            assertTrue(engine.confirmSurroundings(now))
            assertTrue(engine.manualDoorHandling("모의 시나리오의 수동 문 확인", now))
            engine.state.currentStopChildren.filter { it.status == Attendance.ONBOARD }.forEach { child ->
                assertTrue(engine.confirmHandover(child.id, now))
                assertTrue(engine.alight(child.id, now))
            }
            assertTrue(engine.resumeDriving("주변 API 미확인 상태를 직접 확인하고 다음 지점으로 복귀", now))
        }
        assertNull(engine.state.currentStop)
        assertTrue(engine.state.onboardChildren.isEmpty())
        assertTrue(engine.beginEndReview(now))
        assertTrue(engine.confirmCabinInspection(now))
        assertTrue(engine.finishTrip(now))
        assertEquals(TripPhase.ENDED, engine.state.phase)
        // 출결 8건 + V-08 "기사 확인 없음" 1건(체크 없이 출발, 서버 출결 이벤트 아님)
        assertEquals(8, engine.state.ledger.count { !it.isDriverCheckRecord })
        assertEquals(listOf(DriverCheckRecords.MISSING), engine.state.ledger.filter { it.isDriverCheckRecord }.map { it.reason })
    }
    @Test fun `마지막 아이 하차만 다음 지점으로 자동 전환하며 문 확인을 만들지 않는다`() {
        val engine = ready()
        engine.state.children.forEach { assertTrue(engine.board(it.id, now)) }
        assertTrue(engine.startOperationalTrip(now))
        val order = engine.state.routeOrder
        assertFalse(engine.startOperationalTrip(now))
        repeat(order.size) { index ->
            assertTrue(engine.chooseAlighting(now))
            val children = engine.state.currentStopChildren.filter { it.status == Attendance.ONBOARD }
            assertFalse(engine.completeCurrentStop(now))
            children.forEachIndexed { childIndex, child ->
                assertFalse(engine.recordAlightingAndContinue(child.id, now))
                assertTrue(engine.confirmHandover(child.id, now))
                assertTrue(engine.recordAlightingAndContinue(child.id, now))
                assertFalse(engine.recordAlightingAndContinue(child.id, now))
                assertEquals(if(childIndex == children.lastIndex) index + 1 else index, engine.state.currentStopIndex)
            }
            assertEquals(TripPhase.DRIVING, engine.state.phase)
            assertNull(engine.state.manualDoorReason)
            assertNull(engine.state.manualSurroundingsCheckAtMillis)
            assertNull(engine.state.unlockAttempt)
        }
        assertNull(engine.state.currentStop)
        // 출결 8건 + V-08 "기사 확인 없음" 1건(체크 없이 출발, 서버 출결 이벤트 아님)
        assertEquals(8, engine.state.ledger.count { !it.isDriverCheckRecord })
        assertEquals(listOf(DriverCheckRecords.MISSING), engine.state.ledger.filter { it.isDriverCheckRecord }.map { it.reason })
        assertTrue(engine.beginEndReview(now))
        assertNull(engine.state.endRecord)
        assertTrue(engine.inspectAndFinish(now))
        assertEquals(TripPhase.ENDED, engine.state.phase)
        assertFalse(engine.state.endRecord!!.sensorCoverageKnown)
    }

    @Test fun `자동 전환에서도 이동과 미인계와 위험 경고는 하차 기록을 막는다`() {
        val engine = driving()
        assertTrue(engine.chooseAlighting(now))
        assertTrue(engine.confirmHandover("child-a", now))
        engine.updateVehicle(parked().copy(speedKph = observed(10.0)), now)
        assertFalse(engine.recordAlightingAndContinue("child-a", now))
        assertEquals(Attendance.ONBOARD, engine.state.children.first().status)
        engine.updateVehicle(parked(), now)
        engine.updateSurroundings(Surroundings(SurroundingKind.WARNING, Source.SIMULATED, now), now)
        assertFalse(engine.confirmHandover("child-a", now))
        assertFalse(engine.recordAlightingAndContinue("child-a", now))
        assertFalse(engine.completeCurrentStop(now))
    }

    @Test fun `미하차가 남으면 실내 확인과 종료 저장을 함께 실행하지 않는다`() {
        val engine = driving()
        assertTrue(engine.beginEndReview(now))
        assertFalse(engine.inspectAndFinish(now))
        assertNull(engine.state.directCabinCheckAtMillis)
        assertNull(engine.state.endRecord)
    }

    @Test fun `운행 전에 정한 순서는 고정 순서로 적용하고 바꾼 이유를 진단에 남긴다`() {
        val engine = ready()
        assertTrue(engine.board("child-a", now))
        assertTrue(engine.applyManualRoute(listOf("park", "forest", "museum"), "보호자 요청", now))
        assertEquals(listOf("park", "forest", "museum"), engine.state.routeOrder)
        assertEquals(RouteObjective.FIXED, engine.state.routeObjective)
        assertFalse(engine.state.routeIsExample)
        assertNull(engine.state.routeProposal)
        assertTrue(engine.state.diagnostics.last().message.contains("보호자 요청"))
        // 직접 정한 순서는 운행 시작 때 자동 계산으로 덮어쓰지 않는다.
        assertTrue(engine.startOperationalTrip(now))
        assertEquals("park", engine.state.currentStop?.id)
    }

    @Test fun `주행 중이거나 정차 신호가 없으면 순서를 바꾸지 않는다`() {
        val moving = driving()
        val before = moving.state.routeOrder
        assertFalse(moving.applyManualRoute(before.reversed(), null, now))
        assertEquals(before, moving.state.routeOrder)
        val unknown = ShuttleEngine(SampleTrip.create())
        assertFalse(unknown.applyManualRoute(listOf("park", "forest", "museum"), null, now))
        assertEquals(listOf("forest", "museum", "park"), unknown.state.routeOrder)
    }

    @Test fun `정차 중에는 남은 지점만 바꾸고 지금 정류장의 확인 기록은 유지한다`() {
        val engine = alighting()
        assertEquals("forest", engine.state.currentStop?.id)
        val checkedAt = engine.state.manualSurroundingsCheckAtMillis
        assertFalse(engine.applyManualRoute(listOf("museum", "forest", "park"), null, now))
        assertTrue(engine.applyManualRoute(listOf("forest", "park", "museum"), "도로 상황", now))
        assertEquals(TripPhase.ALIGHTING, engine.state.phase)
        assertEquals("forest", engine.state.currentStop?.id)
        assertEquals(checkedAt, engine.state.manualSurroundingsCheckAtMillis)
        // 이 정류장 기록을 마치면 바뀐 순서의 다음 지점으로 넘어간다.
        assertTrue(engine.confirmHandover("child-a", now))
        assertTrue(engine.recordAlightingAndContinue("child-a", now))
        assertEquals(TripPhase.DRIVING, engine.state.phase)
        assertEquals("park", engine.state.currentStop?.id)
    }

    @Test fun `일부 지점이 빠지거나 중복된 순서는 거부한다`() {
        val engine = ready()
        assertFalse(engine.applyManualRoute(listOf("forest", "park"), null, now))
        assertFalse(engine.applyManualRoute(listOf("forest", "park", "park"), null, now))
        assertEquals(listOf("forest", "museum", "park"), engine.state.routeOrder)
        assertTrue(engine.state.routeIsExample)
    }
}
