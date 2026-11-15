package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.institutionArrivalSpeech
import com.hanhyo.pleosshuttle.pickupArrivalSpeech
import com.hanhyo.pleosshuttle.pickupBoardingChildren
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** 등원 운행(V-25~V-29) 도메인: 인계받음 → 탑승, 보호자 호출·정류장 미탑승 확인, 기관 도착·아이별 기관 인계, 저장·서버 이벤트. */
class PickupFlowTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun moving() = parked().copy(speedKph = observed(30.0), gear = observed(Gear.DRIVE))

    /** 서울숲(forest): 가·나, 박물관(museum): 다, 올림픽공원(park): 라. 고정 순서 forest → museum → park. */
    private fun pickupTrip() = SampleTrip.create().copy(direction = TripDirections.PICK_UP, routeIsExample = false)
    private fun engine(trip: TripState = pickupTrip()) = ShuttleEngine(trip).also { it.updateVehicle(parked(), now) }
    private fun ShuttleEngine.child(id: String) = state.children.first { it.id == id }
    private fun departed(): ShuttleEngine = engine().also { assertTrue(it.startDriving(now)) }
    private fun atForest(): ShuttleEngine = departed().also { assertTrue(it.arriveAtPickupStop(now)); assertEquals("forest", it.state.currentStop?.id) }

    @Test fun `등원은 출발 때 탑승 기록 없이 출발하고 출발 전에는 탑승을 기록하지 않는다`() {
        val e = engine()
        assertTrue(e.state.isPickup)
        assertFalse(e.board("child-a", now))
        assertTrue(e.setDriverChecked(true, now))
        assertTrue(e.startDriving(now))
        assertEquals(TripPhase.DRIVING, e.state.phase)
        assertTrue(e.state.onboardChildren.isEmpty())
    }

    @Test fun `등원 전체 흐름 - 인계받음 탑승 미탑승 확인 기관 인계 종료`() {
        val e = atForest()
        // 서울숲: 가는 보호자에게서 인계받고 탑승, 나는 도착 알림 뒤 미탑승 확인(보호자 안내 보냄).
        assertEquals(PickupFlow.AT_STOP, PickupFlow.stateKey(e.state, e.child("child-a")))
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now))
        assertEquals(PickupFlow.HANDED_OVER, PickupFlow.stateKey(e.state, e.child("child-a")))
        val received = e.state.ledger.last()
        assertEquals(PickupActions.RECEIVED, received.pickupAction)
        assertEquals(Attendance.NOT_BOARDED, received.before); assertEquals(Attendance.NOT_BOARDED, received.after)
        assertEquals(PickupReceivers.GUARDIAN, received.receivedFromType)
        assertFalse(received.reason.contains("가상 어린이"))
        assertTrue(e.board("child-a", now))
        assertEquals(Attendance.ONBOARD, e.child("child-a").status)
        assertFalse(e.child("child-a").beltConfirmed)
        assertTrue(e.confirmBelt("child-a", now))
        assertEquals(PickupActions.BOARDED, e.state.ledger.last { it.belt == null }.pickupAction)

        assertFalse(e.completeCurrentStop(now)) // 나가 아직 결정 전
        assertTrue(e.callGuardian("child-b", now))
        assertEquals(now, e.child("child-b").guardianCalledAtMillis)
        assertFalse(e.callGuardian("child-b", now)) // 재호출 없음(D30)
        assertFalse(PickupFlow.shouldSuggestGuardianCall(e.child("child-b")))
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        assertEquals(Attendance.CONFIRMED_NOT_BOARDED, e.child("child-b").status)
        assertEquals(PickupActions.NOT_BOARDED_NOTIFY, e.state.ledger.last().pickupAction)
        assertTrue(PickupFlow.stopPendingChildren(e.state).isEmpty())
        assertTrue(e.completeCurrentStop(now))

        // 박물관: 다는 대리인에게서 인계받고 탑승.
        assertTrue(e.arriveAtPickupStop(now))
        assertTrue(e.recordReceived("child-c", PickupReceivers.PROXY, now))
        assertTrue(e.board("child-c", now))
        assertTrue(e.completeCurrentStop(now))

        // 올림픽공원: 라는 호출 없이 미탑승 확인(경고만, 막지 않음 · 안내 안 보냄).
        assertTrue(e.arriveAtPickupStop(now))
        assertTrue(PickupFlow.shouldSuggestGuardianCall(e.child("child-d")))
        assertTrue(e.confirmNotBoardedAtStop("child-d", notifyGuardian = false, now))
        assertEquals(PickupActions.NOT_BOARDED_SILENT, e.state.ledger.last().pickupAction)
        assertTrue(e.state.ledger.last().reason.contains("도착 알림 없이"))
        assertTrue(e.completeCurrentStop(now))
        assertNull(e.state.currentStop)

        // 기관 도착 확인 → 아이마다 기관 인계 → 실내 확인 → 종료(V-22와 같음).
        assertTrue(e.arriveAtInstitution(now))
        assertEquals(TripPhase.END_REVIEW, e.state.phase)
        assertEquals(listOf("child-a", "child-c"), PickupFlow.awaitingInstitutionHandover(e.state).map { it.id })
        assertTrue(e.handoverToInstitution("child-a", now))
        assertEquals(Attendance.ALIGHTED, e.child("child-a").status)
        assertEquals(PickupActions.INSTITUTION_HANDOVER, e.state.ledger.last().pickupAction)
        assertTrue(e.confirmCabinInspection(now))
        assertFalse(e.finishTrip(now)) // 다가 아직 탑승 중
        assertTrue(e.handoverToInstitution("child-c", now))
        assertEquals(PickupFlow.DROPPED, PickupFlow.stateKey(e.state, e.child("child-c")))
        assertTrue(e.inspectAndFinish(now))
        assertEquals(TripPhase.ENDED, e.state.phase)

        // 서버 이벤트(벨트 기록 제외) 순서.
        val types = InstitutionTrips.serverLedger(e.state.ledger).map { InstitutionTrips.ledgerEventType(it) }
        assertEquals(listOf(
            "CHILD_HANDED_OVER", "CHILD_BOARDED", "GUARDIAN_CALL_REQUESTED", "TEACHER_CONFIRMED_NOT_BOARDED",
            "CHILD_HANDED_OVER", "CHILD_BOARDED", "TEACHER_CONFIRMED_NOT_BOARDED",
            "CHILD_RECEIVED_BY_ORG", "CHILD_RECEIVED_BY_ORG",
        ), types)
        assertEquals(1, e.state.ledger.count { InstitutionTrips.guardianCallRequest(e.state.tripId, it) != null })
    }

    @Test fun `탑승은 인계받음 뒤에만 기록하고 유형은 고정 선택지만 받는다`() {
        val e = atForest()
        assertFalse(e.board("child-a", now))
        assertFalse(e.recordReceived("child-a", "엄마 김OO", now)) // 이름·자유 입력 없음
        assertFalse(e.recordReceived("child-c", PickupReceivers.GUARDIAN, now)) // 다른 정류장 아이
        assertTrue(e.recordReceived("child-a", PickupReceivers.OTHER, now))
        assertFalse(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)) // 중복 없음
        assertFalse(e.callGuardian("child-a", now)) // 인계받은 아이는 호출 대상 아님
        assertFalse(e.confirmNotBoardedAtStop("child-a", notifyGuardian = false, now)) // 인계받았으면 탑승을 기록
        assertFalse(e.correct("child-b", Attendance.ONBOARD, "정정", now)) // 정정으로도 인계받음 없이 탑승 없음
        assertTrue(e.board("child-a", now))
        assertFalse(e.board("child-a", now))
    }

    @Test fun `등원 기록은 모두 정차 확인 중에만 한다`() {
        val e = atForest()
        e.updateVehicle(moving(), now)
        assertFalse(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now))
        assertFalse(e.callGuardian("child-b", now))
        assertFalse(e.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        e.updateVehicle(parked(), now)
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now))
        e.updateVehicle(moving(), now)
        assertFalse(e.board("child-a", now))
        assertEquals(Attendance.NOT_BOARDED, e.child("child-a").status)

        // 기관 도착·기관 인계도 정차 중에만.
        val f = departed()
        f.updateVehicle(parked(), now)
        listOf("forest", "museum", "park").forEach { _ ->
            assertTrue(f.arriveAtPickupStop(now))
            f.state.currentStopChildren.forEach { c ->
                assertTrue(f.recordReceived(c.id, PickupReceivers.GUARDIAN, now)); assertTrue(f.board(c.id, now))
            }
            assertTrue(f.completeCurrentStop(now))
        }
        f.updateVehicle(moving(), now)
        assertFalse(f.arriveAtInstitution(now))
        f.updateVehicle(parked(), now)
        assertTrue(f.arriveAtInstitution(now))
        f.updateVehicle(moving(), now)
        assertFalse(f.handoverToInstitution("child-a", now))
        assertEquals(Attendance.ONBOARD, f.child("child-a").status)
    }

    @Test fun `정류장 아이가 모두 탑승 또는 미탑승 확인이어야 다음 정류장으로 간다`() {
        val e = atForest()
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now))
        assertFalse(e.completeCurrentStop(now)) // 인계받음만으로는 안 됨
        assertTrue(e.board("child-a", now))
        assertFalse(e.completeCurrentStop(now)) // 나 미결정
        assertEquals(listOf("child-b"), PickupFlow.stopPendingChildren(e.state).map { it.id })
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertTrue(e.completeCurrentStop(now))
    }

    @Test fun `미탑승 확인 뒤 같은 정류장에서 나오면 인계받음과 함께 탑승으로 정정한다`() {
        val e = atForest()
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        assertFalse(e.recordReceived("child-b", PickupReceivers.GUARDIAN, now))
        assertFalse(e.correctNotBoardedToBoarded("child-b", "이름", now))
        assertTrue(e.correctNotBoardedToBoarded("child-b", PickupReceivers.PROXY, now))
        assertEquals(Attendance.ONBOARD, e.child("child-b").status)
        assertEquals(PickupReceivers.PROXY, e.child("child-b").receivedFromType)
        val last2 = e.state.ledger.takeLast(2)
        assertEquals(listOf(PickupActions.RECEIVED, PickupActions.BOARDED), last2.map { it.pickupAction })
        assertEquals(listOf("CHILD_HANDED_OVER", "CHILD_BOARDED"), last2.map { InstitutionTrips.ledgerEventType(it) })
    }

    @Test fun `처리 안 된 채 떠난 아이는 다음 정차나 기관에서 미탑승 확인하고 그 전에는 종료하지 않는다`() {
        val e = atForest()
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-a", now))
        assertFalse(e.confirmNotBoardedAtStop("child-c", notifyGuardian = false, now)) // 아직 도착 안 한 정류장
        assertTrue(e.resumeWarnings(now).any { it.contains("탑승·미탑승 확인이 안 된") })
        assertTrue(e.resumeDriving("나 안 나옴 · 다음 정차에서 확인", now))
        assertEquals(Attendance.NOT_BOARDED, e.child("child-b").status) // 자동 미탑승 처리 없음
        assertTrue(e.arriveAtPickupStop(now))
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now)) // 지난 정류장 아이
        assertTrue(e.resumeDriving("다 확인 미룸", now))
        assertTrue(e.arriveAtPickupStop(now))
        assertEquals("park", e.state.currentStop?.id)
        assertTrue(e.resumeDriving("라 확인 미룸", now))
        assertNull(e.state.currentStop)
        assertTrue(e.arriveAtInstitution(now))
        assertEquals(listOf("child-c", "child-d"), PickupFlow.unresolvedAtInstitution(e.state).map { it.id })
        assertTrue(e.handoverToInstitution("child-a", now))
        assertTrue(e.confirmCabinInspection(now))
        assertFalse(e.finishTrip(now)) // 미결정 아이 남음
        assertTrue(e.confirmNotBoardedAtStop("child-c", notifyGuardian = true, now))
        assertTrue(e.confirmNotBoardedAtStop("child-d", notifyGuardian = false, now))
        assertTrue(e.inspectAndFinish(now))
    }

    @Test fun `기관 도착은 남은 정류장이 없을 때만 확인하고 기관 인계는 아이마다 따로 한다`() {
        val e = atForest()
        assertFalse(e.arriveAtInstitution(now)) // 정류장 정차 중
        assertFalse(e.handoverToInstitution("child-a", now))
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-a", now))
        assertTrue(e.recordReceived("child-b", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-b", now))
        assertTrue(e.completeCurrentStop(now))
        assertFalse(e.arriveAtInstitution(now)) // 남은 정류장 있음
        assertFalse(e.beginEndReview(now)) // 등원 종료 확인은 기관 도착 확인으로만
        listOf("museum", "park").forEach { _ ->
            assertTrue(e.arriveAtPickupStop(now))
            e.state.currentStopChildren.forEach { assertTrue(e.confirmNotBoardedAtStop(it.id, notifyGuardian = false, now)) }
            assertTrue(e.completeCurrentStop(now))
        }
        assertTrue(e.beginEndReview(now)) // = 기관 도착 확인
        assertTrue(e.handoverToInstitution("child-a", now))
        assertFalse(e.handoverToInstitution("child-a", now))
        assertEquals(Attendance.ONBOARD, e.child("child-b").status) // 한 번에 인계 없음(D38)
        assertTrue(e.handoverToInstitution("child-b", now))
        assertFalse(e.handoverToInstitution("child-d", now)) // 미탑승 확인한 아이
    }

    @Test fun `등원에서는 정류장 하차와 동승 유지를 기록하지 않는다`() {
        val e = atForest()
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-a", now))
        assertFalse(e.confirmHandover("child-a", now))
        assertFalse(e.alight("child-a", now))
        assertFalse(e.recordAlightingAndContinue("child-a", now))
        assertFalse(e.markStayOnboard("child-a", StayOnboardReasons.GUARDIAN_ABSENT, now))
        assertEquals(Attendance.ONBOARD, e.child("child-a").status)
        // 하원 운행에서는 등원 함수가 동작하지 않는다.
        val dropOff = ShuttleEngine(SampleTrip.create()).also { it.updateVehicle(parked(), now) }
        assertFalse(dropOff.state.isPickup)
        assertTrue(dropOff.board("child-a", now))
        assertTrue(dropOff.startDriving(now)); assertTrue(dropOff.chooseAlighting(now))
        assertFalse(dropOff.recordReceived("child-b", PickupReceivers.GUARDIAN, now))
        assertFalse(dropOff.callGuardian("child-b", now))
        assertFalse(dropOff.confirmNotBoardedAtStop("child-b", notifyGuardian = true, now))
        assertFalse(dropOff.arriveAtPickupStop(now))
        assertFalse(dropOff.arriveAtInstitution(now))
        assertFalse(dropOff.handoverToInstitution("child-a", now))
    }

    @Test fun `등원 출발 전 미탑승 확인은 미탑승 예정 아이만`() {
        val trip = pickupTrip().let { t -> t.copy(children = t.children.map { if (it.id == "child-d") it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") else it }) }
        val e = engine(trip)
        assertEquals(PickupFlow.ABSENT_NOTICE, PickupFlow.stateKey(e.state, e.child("child-d")))
        assertEquals(PickupFlow.SCHEDULED, PickupFlow.stateKey(e.state, e.child("child-a")))
        assertFalse(e.confirmNotBoarded("child-a", now)) // 알림 없는 아이는 정류장에서
        assertTrue(e.confirmNotBoarded("child-d", now))
        assertTrue(e.undoNotBoarded("child-d", now))
        assertTrue(e.confirmNotBoarded("child-d", now))
        // 라만 타는 정류장(park)은 제외 제안(V-09)을 그대로 쓴다.
        assertEquals(listOf("park"), StopExclusions.suggestions(e.state).map { it.id })
        assertEquals("TEACHER_CONFIRMED_NOT_BOARDED", InstitutionTrips.ledgerEventType(e.state.ledger.last()))
    }

    @Test fun `등원도 탈 아이가 0명이면 운행하지 않는다`() {
        val trip = pickupTrip().let { t -> t.copy(children = t.children.map { it.copy(notExpectedToBoard = true) }) }
        val e = engine(trip)
        assertTrue(e.state.noTripToday)
        assertFalse(e.startDriving(now))
        assertFalse(e.setDriverChecked(true, now))
        assertTrue(engine(pickupTrip().copy(children = emptyList())).state.noTripToday)
    }

    @Test fun `출발 뒤 등원 정류장은 아직 결정 전 아이가 있을 때만 들러야 한다`() {
        val e = atForest()
        assertTrue(StopExclusions.hasRiders(e.state, "museum"))
        assertTrue(e.recordReceived("child-a", PickupReceivers.GUARDIAN, now)); assertTrue(e.board("child-a", now))
        assertTrue(e.confirmNotBoardedAtStop("child-b", notifyGuardian = false, now))
        assertFalse(StopExclusions.hasRiders(e.state, "forest"))
    }

    @Test fun `등원 운행 저장 복원은 방향과 등원 필드를 유지한다`() {
        val e = atForest()
        assertTrue(e.recordReceived("child-a", PickupReceivers.PROXY, now))
        assertTrue(e.callGuardian("child-b", now + 1))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(e.state, restored)
        assertEquals(TripDirections.PICK_UP, restored.direction)
        assertTrue(restored.isPickup)
        assertEquals(PickupReceivers.PROXY, restored.children.first { it.id == "child-a" }.receivedFromType)
        assertEquals(now + 1, restored.children.first { it.id == "child-b" }.guardianCalledAtMillis)
        assertEquals(PickupActions.GUARDIAN_CALLED, restored.ledger.last().pickupAction)
    }

    @Test fun `이전 저장 파일은 방향 null이라 하원으로 읽는다`() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/trip-v1-3c27718.bin"))
        val state = ObjectInputStream(stream).use { it.readObject() as TripState }
        assertNull(state.direction)
        assertEquals(TripDirections.DROP_OFF, state.tripDirection)
        assertFalse(state.isPickup)
        assertTrue(state.children.none { it.receivedAtStop || it.guardianCalled })
        assertTrue(state.ledger.none { it.isPickupRecord || it.receivedFromType != null })
        assertTrue(state.ledger.all { InstitutionTrips.eventPayload(it).isEmpty() })
        assertTrue(state.ledger.none { InstitutionTrips.guardianCallRequest(state.tripId, it) != null })
    }

    @Test fun `등원 원장 이벤트 매핑과 보호자 호출 요청`() {
        fun entry(action: String?, before: Attendance = Attendance.NOT_BOARDED, after: Attendance = Attendance.NOT_BOARDED, type: String? = null) =
            LedgerEntry("e1", "41", before, after, now, "r", pickupAction = action, receivedFromType = type)
        assertEquals("CHILD_HANDED_OVER", InstitutionTrips.ledgerEventType(entry(PickupActions.RECEIVED, type = PickupReceivers.GUARDIAN)))
        assertEquals("CHILD_BOARDED", InstitutionTrips.ledgerEventType(entry(PickupActions.BOARDED, after = Attendance.ONBOARD)))
        assertEquals("GUARDIAN_CALL_REQUESTED", InstitutionTrips.ledgerEventType(entry(PickupActions.GUARDIAN_CALLED)))
        assertEquals("TEACHER_CONFIRMED_NOT_BOARDED", InstitutionTrips.ledgerEventType(entry(PickupActions.NOT_BOARDED_NOTIFY, after = Attendance.CONFIRMED_NOT_BOARDED)))
        assertEquals("TEACHER_CONFIRMED_NOT_BOARDED", InstitutionTrips.ledgerEventType(entry(PickupActions.NOT_BOARDED_SILENT, after = Attendance.CONFIRMED_NOT_BOARDED)))
        assertEquals("CHILD_RECEIVED_BY_ORG", InstitutionTrips.ledgerEventType(entry(PickupActions.INSTITUTION_HANDOVER, Attendance.ONBOARD, Attendance.ALIGHTED)))
        // 하원 기록은 그대로(인계받음과 같은 before/after라도 pickupAction이 없으면 기존 규칙).
        assertEquals("CHILD_DROPPED_OFF", InstitutionTrips.ledgerEventType(entry(null, Attendance.ONBOARD, Attendance.ALIGHTED)))
        assertEquals("CHILD_NOT_BOARDED", InstitutionTrips.ledgerEventType(entry(null)))

        assertEquals(mapOf("receivedFromType" to PickupReceivers.GUARDIAN), InstitutionTrips.eventPayload(entry(PickupActions.RECEIVED, type = PickupReceivers.GUARDIAN)))
        assertEquals(mapOf("notifyGuardian" to true), InstitutionTrips.eventPayload(entry(PickupActions.NOT_BOARDED_NOTIFY)))
        assertEquals(mapOf("notifyGuardian" to false), InstitutionTrips.eventPayload(entry(PickupActions.NOT_BOARDED_SILENT)))
        assertTrue(InstitutionTrips.eventPayload(entry(null)).isEmpty())

        assertEquals("/api/v1/trips/9/children/41/guardian-calls" to mapOf("callType" to "CHILD_NOT_AT_STOP"),
            InstitutionTrips.guardianCallRequest("9", entry(PickupActions.GUARDIAN_CALLED)))
        assertNull(InstitutionTrips.guardianCallRequest("9", entry(PickupActions.RECEIVED)))
        assertNull(InstitutionTrips.guardianCallRequest("9", entry(null)))
        // 등원 기록은 서버 전송 대상(전송 대기 건수에 포함)이다.
        assertEquals(1, InstitutionTrips.pendingCount(listOf(entry(PickupActions.GUARDIAN_CALLED)), emptySet()))
    }

    @Test fun `방향 기본값과 표기`() {
        assertEquals(TripDirections.PICK_UP, TripDirections.defaultFor(8))
        assertEquals(TripDirections.DROP_OFF, TripDirections.defaultFor(15))
        assertEquals(TripDirections.DROP_OFF, TripDirections.of(null))
        assertEquals(TripDirections.DROP_OFF, TripDirections.of("weird"))
        assertEquals(TripDirections.PICK_UP, TripDirections.of("pick_up"))
        assertEquals("등원", TripDirections.label("PICK_UP")); assertEquals("하원", TripDirections.label(null))
    }

    @Test fun `등원 음성 문구`() {
        val e = atForest()
        assertEquals(listOf("child-a", "child-b"), pickupBoardingChildren(e.state, e.state.currentStop).map { it.id })
        assertTrue(pickupBoardingChildren(SampleTrip.create(), SampleTrip.create().stops.first()).isEmpty())
        assertEquals("서울숲 정류장 도착, 어린이 가, 어린이 나 탑승 예정. 인계받음과 탑승을 기록해 주세요.",
            pickupArrivalSpeech("서울숲", listOf("어린이 가", "어린이 나")))
        assertEquals("서울숲 정류장 도착, 탈 어린이 없음.", pickupArrivalSpeech("서울숲", emptyList()))
        assertEquals("기관 도착. 어린이 가 기관 담당자에게 인계해 주세요.", institutionArrivalSpeech(listOf("어린이 가")))
    }
}
