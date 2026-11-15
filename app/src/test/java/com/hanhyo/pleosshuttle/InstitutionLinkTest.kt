package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.NoTrip
import com.hanhyo.pleosshuttle.domain.SampleTrip
import com.hanhyo.pleosshuttle.domain.Stop
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.domain.TripState
import com.hanhyo.shuttle.net.ApiFailure
import org.junit.Assert.*
import org.junit.Test

/** 페어링 SDK 버전 · 단계 기록 키 · 이벤트 묶음 전송(가짜 전송 계층) · 차량 연결 해제의 판단 로직(HTTP·JSON 없이). */
class InstitutionLinkTest {
    @Test fun pleosSdkVersionFitsServerColumn() {
        val v = InstitutionTrips.pleosSdkVersion(BuildConfig.PLEOS_VEHICLE_SDK, BuildConfig.PLEOS_NAVI_SDK, BuildConfig.PLEOS_TTS_SDK)
        assertEquals("V2.3.2;N3.0.5;T3.3.1", v)
        assertTrue(v.length <= InstitutionTrips.PLEOS_SDK_VERSION_MAX)
        assertEquals("V12.13.14;N13.10.11", InstitutionTrips.pleosSdkVersion("12.13.14", "13.10.11", "3.3.1"))
        assertEquals(20, InstitutionTrips.pleosSdkVersion("1".repeat(40), "x", "y").length)
    }

    @Test fun onlyAFullyAcceptedBatchIsMarkedSent() {
        val keys = listOf("a", "b", "c")
        assertEquals(keys, InstitutionTrips.batchAcceptedKeys(keys, 3)) // 중복 clientEventId도 acceptedCount에 포함된다
        assertTrue(InstitutionTrips.batchAcceptedKeys(keys, 2).isEmpty())
        assertTrue(InstitutionTrips.batchAcceptedKeys(keys, null).isEmpty())
        assertTrue(InstitutionTrips.batchAcceptedKeys(keys, -1).isEmpty())
    }

    @Test fun rejectedBatchFallsBackToSingleEventsExceptAuthFailures() {
        assertTrue(InstitutionTrips.fallBackToSingleEvents(400))
        assertTrue(InstitutionTrips.fallBackToSingleEvents(404))
        assertFalse(InstitutionTrips.fallBackToSingleEvents(500)) // 서버 오류는 다음 동기화로 미룬다
        assertFalse(InstitutionTrips.fallBackToSingleEvents(401))
        assertFalse(InstitutionTrips.fallBackToSingleEvents(403))
    }

    @Test fun disconnectIsBlockedDuringATripOrWithUnsentRecords() {
        fun blocker(phase: TripPhase = TripPhase.ENDED, events: Int = 0, steps: Int = 0, hasTrip: Boolean = true, connected: Boolean = true,
                    busy: Boolean = false, storageError: Boolean = false, ledgerEmpty: Boolean = true, onboardEmpty: Boolean = true) =
            InstitutionTrips.disconnectBlocker(InstitutionTrips.DisconnectState(connected, hasTrip, phase, ledgerEmpty, onboardEmpty, events, steps, busy, storageError))
        assertNull(blocker())
        assertNull(blocker(TripPhase.PREPARING))
        assertNull(blocker(TripPhase.DRIVING, hasTrip = false))
        for (phase in listOf(TripPhase.DRIVING, TripPhase.ALIGHTING, TripPhase.END_REVIEW))
            assertTrue(blocker(phase)!!.contains("진행 중인 운행"))
        // 운행 바꾸기(canReplaceInstitutionTrip)와 같은 기준: 기록이 있는 운행 준비는 막는다.
        assertNotNull(blocker(TripPhase.PREPARING, ledgerEmpty = false))
        assertNotNull(blocker(TripPhase.PREPARING, onboardEmpty = false))
        assertTrue(blocker(events = 2)!!.contains("2건"))
        // 종료했는데 /complete 등 단계 기록이 아직 안 갔으면 막는다(해제 후 서버 운행이 IN_PROGRESS로 남지 않게).
        assertTrue(blocker(steps = 1)!!.contains("1건"))
        assertTrue(blocker(events = 1, steps = 2)!!.contains("3건"))
        assertNotNull(blocker(busy = true))
        assertNotNull(blocker(storageError = true))
        assertNotNull(blocker(connected = false))
    }

    @Test fun revokeFailureClearsLocalOnlyWhenCredentialIsAlreadyInvalid() {
        assertTrue(InstitutionTrips.revokeClearsLocal(401))
        for (status in listOf(400, 403, 404, 500, 502)) assertFalse(InstitutionTrips.revokeClearsLocal(status))
    }

    @Test fun disconnectClearsConnectionAndSentKeysButKeepsServerAddressAndInstallId() {
        val keys = listOf("base", "installId", "direction", "credential", "token", "vehicleId", "orgId", "name", "trip", "pendingTrip",
            "42:start", "42:stop-101-arrive", "sentEvents:42")
        assertEquals(keys.drop(3).toSet(), InstitutionTrips.connectionKeysToClear(keys).toSet())
    }

    // ---- 단계 기록 키(sync와 연결 해제 판단이 같이 쓰는 규칙) ----
    private val trip = SampleTrip.create().copy(tripId = "42", routeIsExample = false,
        stops = listOf(Stop("101", "A", 37.5, 127.0), Stop("102", "B", 37.51, 127.01), Stop("103", "C", 37.52, 127.02)),
        routeOrder = listOf("101", "102", "103"))
    private fun keys(t: TripState) = InstitutionTrips.lifecycleSteps(t).map { it.key }

    @Test fun lifecycleKeysFollowTripPhase() {
        assertTrue(keys(trip).isEmpty())
        assertTrue(keys(NoTrip.state()).isEmpty())
        assertEquals(listOf("start"), keys(trip.copy(phase = TripPhase.DRIVING)))
        assertEquals(listOf("start", "stop-101-arrive"), keys(trip.copy(phase = TripPhase.ALIGHTING)))
        assertEquals(listOf("start", "stop-101-arrive", "stop-101-complete", "stop-102-arrive"),
            keys(trip.copy(phase = TripPhase.ALIGHTING, currentStopIndex = 1)))
        val excluded = trip.copy(routeOrder = listOf("101", "103"), excludedStopIds = listOf("102"))
        assertEquals(listOf("start", "stop-101-arrive", "stop-101-complete", "stop-103-arrive", "stop-103-complete", "stop-102-skip", "return"),
            keys(excluded.copy(phase = TripPhase.END_REVIEW, currentStopIndex = 2)))
        assertEquals(listOf("start", "stop-101-arrive", "stop-101-complete", "stop-102-arrive", "stop-102-complete", "stop-103-arrive",
            "stop-103-complete", "return", "interior", "complete"), keys(trip.copy(phase = TripPhase.ENDED, currentStopIndex = 3)))
        // 등원은 복귀(return)를 보내지 않는다.
        assertFalse("return" in keys(trip.copy(phase = TripPhase.ENDED, currentStopIndex = 3, direction = "PICK_UP")))
        assertTrue(InstitutionTrips.lifecycleSteps(trip.copy(phase = TripPhase.ENDED, currentStopIndex = 3)).single { it.key == "interior" }.checkedBody)
    }

    @Test fun unsentLifecycleKeysAreTheOnesNotMarked() {
        val ended = trip.copy(phase = TripPhase.ENDED, currentStopIndex = 3)
        val sent = keys(ended).toSet() - "complete"
        assertEquals(listOf("complete"), InstitutionTrips.unsentLifecycleKeys(ended) { it in sent })
        assertTrue(InstitutionTrips.unsentLifecycleKeys(ended) { true }.isEmpty())
    }

    // ---- 이벤트 묶음 나누기·전송(가짜 전송 계층) ----
    private fun ev(key: String, type: String = "CHILD_BOARDED", child: String? = "c$key", call: Boolean = false) =
        PendingEvent(key, type, child, "body-$key", call)

    private class FakeTransport(
        val failCallFor: Set<String> = emptySet(), val batchResult: (List<String>) -> Int? = { it.size },
        val batchFailure: Int? = null, val failSingle: Set<String> = emptySet(),
    ) {
        val log = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val transport = EventTransport<String>(
            guardianCall = { e -> log += "call:${e.key}"; if (e.key in failCallFor) throw IllegalStateException("call failed") },
            single = { e -> log += "single:${e.key}"; if (e.key in failSingle) throw ApiFailure(400, "bad") },
            batch = { items ->
                log += "batch:${items.joinToString(",") { it.key }}"
                batchFailure?.let { throw ApiFailure(it, "rejected") }
                batchResult(items.map { it.key })
            },
            markSent = { marked += it },
        )
    }

    @Test fun eventsAreSplitIntoServerSizedBatchesAndPushEventsGoAlone() {
        val items = (1..120).map { ev("e$it") }
        assertEquals(listOf(50, 50, 20), InstitutionTrips.eventSegments(items).map { it.size })
        val withPush = listOf(ev("a"), ev("b"), ev("d", "CHILD_DROPPED_OFF"), ev("e"), ev("f", "CHILD_RECEIVED_BY_ORG"), ev("g"))
        assertEquals(listOf(listOf("a", "b"), listOf("d"), listOf("e"), listOf("f"), listOf("g")),
            InstitutionTrips.eventSegments(withPush).map { s -> s.map { it.key } })
        assertTrue(InstitutionTrips.sendsPushOnServer("CHILD_DROPPED_OFF"))
        assertTrue(InstitutionTrips.sendsPushOnServer("CHILD_RECEIVED_BY_ORG"))
        assertFalse(InstitutionTrips.sendsPushOnServer("CHILD_BOARDED"))
        assertTrue(InstitutionTrips.eventSegments(emptyList<PendingEvent<String>>()).isEmpty())
        // 푸시 이벤트는 묶음으로 보내지 않는다(묶음 롤백 뒤 단건 재전송 때 푸시가 두 번 가지 않게).
        val f = FakeTransport()
        InstitutionTrips.sendPendingEvents(withPush, f.transport)
        assertEquals(listOf("batch:a,b", "single:d", "single:e", "single:f", "single:g"), f.log)
    }

    @Test fun guardianCallAfterAClosingEventForTheSameChildStartsANewBatch() {
        // 같은 아이(x)의 호출을 닫는 이벤트(탑승) 뒤에 보호자 호출이 오면, 호출이 먼저 나가 바로 닫히지 않게 끊는다.
        val items = listOf(ev("1", "CHILD_BOARDED", "x"), ev("2", "GUARDIAN_CALL_REQUESTED", "x", call = true), ev("3", "CHILD_BOARDED", "y"))
        assertEquals(listOf(listOf("1"), listOf("2", "3")), InstitutionTrips.eventSegments(items).map { s -> s.map { it.key } })
        // 다른 아이의 닫는 이벤트는 끊지 않는다.
        val other = listOf(ev("1", "CHILD_BOARDED", "y"), ev("2", "GUARDIAN_CALL_REQUESTED", "x", call = true))
        assertEquals(1, InstitutionTrips.eventSegments(other).size)
        val f = FakeTransport()
        InstitutionTrips.sendPendingEvents(items, f.transport)
        assertEquals(listOf("single:1", "call:2", "batch:2,3"), f.log)
        assertEquals(listOf("1", "2", "3"), f.marked)
    }

    @Test fun guardianCallFailureSendsOnlyTheEventsBeforeIt() {
        val items = listOf(ev("1"), ev("2"), ev("3", "GUARDIAN_CALL_REQUESTED", "z", call = true), ev("4"))
        val f = FakeTransport(failCallFor = setOf("3"))
        val error = runCatching { InstitutionTrips.sendPendingEvents(items, f.transport) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf("call:3", "batch:1,2"), f.log)
        assertEquals(listOf("1", "2"), f.marked)
    }

    @Test fun rejectedBatchFallsBackToSinglesAndMarksOnlyWhatWasSent() {
        val f = FakeTransport(batchFailure = 400, failSingle = setOf("2"))
        val error = runCatching { InstitutionTrips.sendPendingEvents(listOf(ev("1"), ev("2"), ev("3")), f.transport) }.exceptionOrNull()
        assertTrue(error is ApiFailure)
        assertEquals(listOf("batch:1,2,3", "single:1", "single:2"), f.log)
        assertEquals(listOf("1"), f.marked) // 2에서 멈추고 2·3은 다음 동기화 때 다시 보낸다
    }

    @Test fun unauthorizedBatchIsNotRetriedOneByOne() {
        for (status in listOf(401, 403)) {
            val f = FakeTransport(batchFailure = status)
            val error = runCatching { InstitutionTrips.sendPendingEvents(listOf(ev("1"), ev("2")), f.transport) }.exceptionOrNull()
            assertEquals(status, (error as ApiFailure).status)
            assertEquals(listOf("batch:1,2"), f.log)
            assertTrue(f.marked.isEmpty())
        }
    }

    @Test fun mismatchedAcceptedCountMarksNothingAndTheNextSyncResendsTheSameBatch() {
        val items = listOf(ev("1"), ev("2"))
        val first = FakeTransport(batchResult = { 1 })
        assertNotNull(runCatching { InstitutionTrips.sendPendingEvents(items, first.transport) }.exceptionOrNull())
        assertTrue(first.marked.isEmpty())
        // 다음 동기화: 같은 묶음(같은 clientEventId)을 다시 보낸다. 서버는 중복도 acceptedCount에 센다.
        val retry = FakeTransport()
        InstitutionTrips.sendPendingEvents(items, retry.transport)
        assertEquals(listOf("batch:1,2"), retry.log)
        assertEquals(listOf("1", "2"), retry.marked)
    }
}
