package com.hanhyo.pleosshuttle.domain

import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.departureCue
import com.hanhyo.pleosshuttle.ui.routeChanges
import com.hanhyo.pleosshuttle.ui.routeTimeline
import com.hanhyo.pleosshuttle.ui.timelineStops
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** V-09·D17 빈 정류장 제외 제안: 제안 규칙, 선생님 확인 빼기·다시 넣기, 자동 재포함, 경유지·출발 요약, 저장, 서버 skip 시점. */
class StopExclusionTest {
    private val now = 1_000_000L
    private fun <T> observed(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
    private fun parked() = VehicleSnapshot(connected = true, speedKph = observed(0.0), gear = observed(Gear.PARK),
        parkingBrake = observed(true), doorLocked = observed(true), connectionGeneration = 1)
    private fun moving() = parked().copy(speedKph = observed(30.0), gear = observed(Gear.DRIVE))

    /** 서울숲: 가·나, 박물관: 다, 올림픽공원(park): 라. 가·나·다 탑승, 라 미탑승 확인 → 올림픽공원 0명. 고정 순서 forest→museum→park. */
    private fun ready(): ShuttleEngine = ShuttleEngine(SampleTrip.create().copy(routeIsExample = false)).apply {
        updateVehicle(parked(), now)
        assertTrue(board("child-a", now)); assertTrue(board("child-b", now)); assertTrue(board("child-c", now))
        assertTrue(confirmNotBoarded("child-d", now))
        assertTrue(state.awaitingDeparture)
    }
    private fun ShuttleEngine.stopRecords() = state.ledger.filter { it.isStopExclusionRecord }

    @Test fun `선생님 미탑승 확인만 남은 정류장만 제외를 제안한다`() {
        val e = ShuttleEngine(SampleTrip.create()).apply { updateVehicle(parked(), now) }
        assertTrue(e.board("child-a", now)); assertTrue(e.board("child-b", now)); assertTrue(e.board("child-c", now))
        // 라는 아직 결정 전 → 들를 수 있으므로 제안하지 않는다.
        assertTrue(StopExclusions.suggestions(e.state).isEmpty())
        assertTrue(e.confirmNotBoarded("child-d", now))
        assertEquals(listOf("park"), StopExclusions.suggestions(e.state).map { it.id })
        // 기본값은 순서에 남음.
        assertTrue("park" in e.state.routeOrder)
        assertTrue(e.state.excludedStops.isEmpty())
    }

    @Test fun `선생님이 빼면 방문 순서·도착 예정·출발 요약·음성에서 빠지고 원장에만 남는다`() {
        val e = ready()
        assertTrue(e.setDriverChecked(true, now))
        val version = e.state.routeVersion
        assertTrue(e.excludeStop("park", now))
        assertEquals(listOf("forest", "museum"), e.state.routeOrder)
        assertEquals(listOf("park"), e.state.excludedStops)
        assertTrue(e.state.routeVersion > version)
        assertNull("순서가 바뀌었으므로 기사 확인은 풀린다(D15)", e.state.driverCheckedAtMillis)
        val record = e.stopRecords().single()
        assertEquals(true, record.stopExcluded); assertEquals("park", record.stopId)
        assertTrue(InstitutionTrips.serverLedger(e.state.ledger).none { it.isStopExclusionRecord })

        val summary = departureSummary(e.state)
        assertEquals(listOf("서울숲", "국립중앙박물관"), summary.stopNames)
        assertFalse(departureSpeech(summary).contains("올림픽공원"))
        assertFalse(departureCue(e.state)!!.text.contains("올림픽공원"))
        assertNull(routeTimeline(e.state, departureRouteOrder(e.state)).arrivalOf("park"))
        assertTrue(timelineStops(e.state).none { it.name.contains("올림픽공원") })
        assertTrue(StopExclusions.suggestions(e.state).isEmpty())
        assertTrue(routeChanges(e.state).any { it.kind == "뺀 정류장" && it.text.startsWith("올림픽공원") })
        // 출발해도 자동 계산·검증이 뺀 정류장 없이 통과한다.
        assertTrue(e.startDriving(now))
        assertEquals(listOf("forest", "museum"), navigationWaypoints(e.state).map { it.id })
    }

    @Test fun `예시 순서도 뺀 정류장 없이 자동 계산해 출발한다`() {
        val e = ShuttleEngine(SampleTrip.create()).apply { updateVehicle(parked(), now) }
        assertTrue(e.board("child-a", now)); assertTrue(e.board("child-c", now)); assertTrue(e.board("child-b", now))
        assertTrue(e.confirmNotBoarded("child-d", now))
        assertTrue(e.excludeStop("park", now))
        assertFalse("park" in departureRouteOrder(e.state))
        assertTrue(e.startOperationalTrip(now))
        assertEquals(setOf("forest", "museum"), e.state.routeOrder.toSet())
    }

    @Test fun `내릴 아이가 있거나 이동 중이면 빼지 않고 다시 넣기는 출발 전에 할 수 있다`() {
        val e = ready()
        assertFalse(e.excludeStop("forest", now))
        assertFalse(e.excludeStop("nowhere", now))
        e.updateVehicle(moving(), now)
        assertFalse(e.excludeStop("park", now))
        e.updateVehicle(parked(), now)
        assertTrue(e.excludeStop("park", now))
        assertFalse(e.excludeStop("park", now))
        assertTrue(e.includeStop("park", now))
        assertEquals(setOf("forest", "museum", "park"), e.state.routeOrder.toSet())
        assertTrue(e.state.excludedStops.isEmpty())
        assertEquals(listOf(true, false), e.stopRecords().map { it.stopExcluded })
        assertFalse(e.includeStop("park", now))
    }

    @Test fun `출발 전 미탑승 확인을 취소하면 뺀 정류장을 자동으로 다시 넣는다`() {
        val e = ready()
        assertTrue(e.excludeStop("park", now))
        assertTrue(e.undoNotBoarded("child-d", now))
        assertTrue("park" in e.state.routeOrder)
        assertTrue(e.state.excludedStops.isEmpty())
        assertEquals(StopExclusions.Records.AUTO_INCLUDED, e.stopRecords().last().reason)
        assertTrue(e.lastMessage.contains("다시 넣었어요"))
    }

    @Test fun `정차 중 정정으로 다시 타게 되면 지금 정류장 뒤에 자동으로 다시 넣고 경유지에 포함한다`() {
        val e = ready()
        assertTrue(e.excludeStop("park", now))
        assertTrue(e.startDriving(now))
        assertTrue(e.chooseAlighting(now))
        val version = e.state.routeVersion
        assertTrue(e.correct("child-d", Attendance.ONBOARD, "실제로 탑승", now))
        assertEquals("forest", e.state.routeOrder.first())
        assertTrue(e.state.routeOrder.indexOf("park") > e.state.currentStopIndex)
        assertTrue(e.state.excludedStops.isEmpty())
        assertTrue(e.state.routeVersion > version)
        assertTrue(e.lastMessage.contains("경로를 다시 안내"))
        // 서울숲 기록을 마치면 다음 경유지 묶음(최대 3곳)에 올림픽공원이 다시 들어간다.
        listOf("child-a", "child-b").forEach { assertTrue(e.confirmHandover(it, now)); assertTrue(e.recordAlightingAndContinue(it, now)) }
        assertEquals(TripPhase.DRIVING, e.state.phase)
        assertTrue("park" in navigationWaypoints(e.state).map { it.id })
    }

    @Test fun `운행 중 아직 들르지 않은 정류장이 빈 정류장이 되면 정차 중에 제안하고 확인하면 경유지에서 뺀다`() {
        val e = ShuttleEngine(SampleTrip.create().copy(routeIsExample = false)).apply { updateVehicle(parked(), now) }
        listOf("child-a", "child-b", "child-c", "child-d").forEach { assertTrue(e.board(it, now)) }
        assertTrue(e.startDriving(now))
        assertTrue(StopExclusions.suggestions(e.state).isEmpty()) // 운행 중에는 제안하지 않음(V-11)
        assertTrue(e.chooseAlighting(now))
        assertTrue(StopExclusions.suggestions(e.state).isEmpty())
        assertTrue(e.correct("child-d", Attendance.NOT_BOARDED, "실제로 안 탐", now))
        assertEquals(listOf("park"), StopExclusions.suggestions(e.state).map { it.id })
        assertTrue("자동으로 빼지 않는다", "park" in e.state.routeOrder)
        assertTrue(e.excludeStop("park", now))
        listOf("child-a", "child-b").forEach { assertTrue(e.confirmHandover(it, now)); assertTrue(e.recordAlightingAndContinue(it, now)) }
        assertEquals(listOf("museum"), navigationWaypoints(e.state).map { it.id })
        // 지나온 정류장(서울숲, 모두 하차)은 제안하지 않는다.
        assertTrue(e.chooseAlighting(now))
        assertFalse(StopExclusions.suggestions(e.state).any { it.id == "forest" })
        assertFalse(e.excludeStop("forest", now))
        assertFalse("지금 정류장도 뺄 수 없다", e.excludeStop("museum", now))
    }

    @Test fun `동승 유지 아이는 원래 정류장을 다시 들를 정류장으로 만들지 않는다`() {
        val e = ready()
        assertTrue(e.startDriving(now)); assertTrue(e.chooseAlighting(now))
        assertTrue(e.markStayOnboard("child-b", StayOnboardReasons.GUARDIAN_ABSENT, now))
        val stay = e.state.children.first { it.id == "child-b" }
        assertFalse(StopExclusions.needsStop(e.state, stay))
        assertTrue(e.confirmHandover("child-a", now)); assertTrue(e.recordAlightingAndContinue("child-a", now))
        assertTrue(e.completeCurrentStop(now)); assertTrue(e.chooseAlighting(now))
        assertEquals("museum", e.state.currentStop?.id)
        assertEquals(listOf("park"), StopExclusions.suggestions(e.state).map { it.id })
        assertFalse(StopExclusions.hasRiders(e.state, "forest"))
    }

    @Test fun `서버 skip은 복귀 뒤 끝까지 빠진 정류장만 보낸다`() {
        val e = ready()
        assertTrue(e.excludeStop("park", now))
        assertTrue(InstitutionTrips.stopsToSkip(e.state).isEmpty())
        assertTrue(e.startDriving(now))
        assertTrue(InstitutionTrips.stopsToSkip(e.state).isEmpty())
        assertTrue(e.beginEndReview(now))
        assertEquals(listOf("park"), InstitutionTrips.stopsToSkip(e.state))
    }

    @Test fun `뺀 정류장은 저장·복원되고 이전 파일의 null은 빈 목록으로 읽는다`() {
        val e = ready()
        assertTrue(e.excludeStop("park", now))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(e.state) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as TripState }
        assertEquals(e.state, restored)
        assertEquals(listOf("park"), restored.excludedStops)
        assertEquals(emptyList<String>(), SampleTrip.create().copy(excludedStopIds = null).excludedStops)
    }
}
