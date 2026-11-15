package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

/** 기관 운행은 서버 정류장 순서(seq = stops 순서)를 지킨다: 다시 넣기는 원래 자리, 이전 빌드에서 바꾼 순서는 복원 때 되돌림. */
class ServerOrderTest {
    private val now = 1_000_000L
    private val a = Stop("101", "정류장 A · 가", 37.5445, 127.0374)
    private val b = Stop("102", "정류장 B · 나", 37.5239, 126.9805)
    private val c = Stop("103", "정류장 C · 다", 37.5207, 127.1214)
    private val d = Stop("104", "정류장 D · 라", 37.5512, 126.9882)
    private fun trip(stops: List<Stop> = listOf(a, b, c)) = TripState(
        tripId = "8", origin = Stop("origin", "기관", 37.5665, 126.9780), stops = stops, routeIsExample = false,
        children = stops.mapIndexed { i, s -> ChildRecord("child-$i", "어린이 $i", s.id, "") },
    )

    @Test fun `빼었다가 다시 넣으면 서버 순서 자리로 돌아간다`() {
        val excluded = trip().copy(routeOrder = listOf("101", "103"), excludedStopIds = listOf("102"))
        assertEquals(listOf("101", "102", "103"), StopExclusions.insertOrder(excluded, "102"))
        assertEquals(listOf("101", "102", "103"), StopExclusions.insertOrder(trip().copy(routeOrder = listOf("102", "103")), "101"))
        assertEquals(listOf("101", "102", "103"), StopExclusions.insertOrder(trip().copy(routeOrder = listOf("101", "102")), "103"))
    }

    @Test fun `정차 중에는 고정 구간 뒤에서 서버 순서 자리에 넣는다`() {
        // A·B·C·D에서 A를 뺀 채 B에 정차 중(고정 구간 = B까지). A는 이미 지난 자리라 고정 구간 바로 뒤에 들어간다.
        val atB = trip(listOf(a, b, c, d)).copy(phase = TripPhase.ALIGHTING, routeOrder = listOf("102", "103", "104"), currentStopIndex = 0, excludedStopIds = listOf("101"))
        assertEquals(listOf("102", "101", "103", "104"), StopExclusions.insertOrder(atB, "101"))
        // C를 뺀 채 B에 정차 중이면 C는 B 뒤, D 앞(서버 순서 자리)으로 돌아간다.
        val noC = trip(listOf(a, b, c, d)).copy(phase = TripPhase.ALIGHTING, routeOrder = listOf("101", "102", "104"), currentStopIndex = 1, excludedStopIds = listOf("103"))
        assertEquals(listOf("101", "102", "103", "104"), StopExclusions.insertOrder(noC, "103"))
    }

    @Test fun `엔진의 다시 넣기도 서버 순서를 따른다`() {
        fun <T> seen(value: T) = Observation(value, Source.SDK, now, ResultStatus.TARGET_OBSERVED)
        val engine = ShuttleEngine(trip().copy(children = listOf(ChildRecord("child-0", "어린이 0", "101", ""), ChildRecord("child-2", "어린이 2", "103", ""))))
        engine.updateVehicle(VehicleSnapshot(true, seen(0.0), seen(Gear.PARK), seen(true), seen(true), connectionGeneration = 1), now)
        assertTrue(engine.excludeStop("102", now))
        assertEquals(listOf("101", "103"), engine.state.routeOrder)
        assertTrue(engine.includeStop("102", now))
        assertEquals(listOf("101", "102", "103"), engine.state.routeOrder)
    }

    @Test fun `이전 빌드에서 바꾼 운행 준비 순서는 복원 때 서버 순서로 되돌린다`() {
        val reordered = trip().copy(routeOrder = listOf("103", "101", "102"), routeObjective = RouteObjective.FIXED)
        val restored = StoredTrips.restorable(reordered)!!
        assertEquals(listOf("101", "102", "103"), restored.routeOrder)
        assertEquals(reordered.routeVersion + 1, restored.routeVersion)
        // 뺀 정류장은 빼고 정규화한다.
        val withExcluded = trip().copy(routeOrder = listOf("103", "101"), excludedStopIds = listOf("102"))
        assertEquals(listOf("101", "103"), StoredTrips.restorable(withExcluded)!!.routeOrder)
        // 이미 서버 순서면 그대로(같은 객체 값).
        assertEquals(trip(), StoredTrips.restorable(trip()))
        // 출발 뒤에는 진행 위치가 순서에 묶여 있어 바꾸지 않는다.
        val driving = reordered.copy(phase = TripPhase.DRIVING, currentStopIndex = 1)
        assertEquals(driving.routeOrder, StoredTrips.restorable(driving)!!.routeOrder)
    }

    @Test fun `복원 때 출발 전 순서가 바뀌면 기사 확인을 다시 받는다`() {
        val checked = trip().copy(routeOrder = listOf("103", "101", "102"), driverCheckedAtMillis = now)
        assertNull(StoredTrips.restorable(checked)!!.driverCheckedAtMillis)
        val example = trip().copy(routeIsExample = true, driverCheckedAtMillis = now)
        assertNull(StoredTrips.restorable(example)!!.driverCheckedAtMillis)
        // 순서가 그대로면 기사 확인을 유지한다.
        val same = trip().copy(driverCheckedAtMillis = now)
        assertEquals(now, StoredTrips.restorable(same)!!.driverCheckedAtMillis)
    }
}
