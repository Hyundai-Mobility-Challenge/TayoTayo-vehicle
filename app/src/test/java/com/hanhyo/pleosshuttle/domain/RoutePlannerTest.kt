package com.hanhyo.pleosshuttle.domain

import org.junit.Assert.*
import org.junit.Test

class RoutePlannerTest {
    private val matrix = CostMatrix(
        mapOf(
            ("o" to "a") to 1.0, ("o" to "b") to 9.0, ("o" to "c") to 9.0,
            ("a" to "b") to 1.0, ("a" to "c") to 9.0, ("a" to "o") to 9.0,
            ("b" to "c") to 1.0, ("b" to "a") to 9.0, ("b" to "o") to 9.0,
            ("c" to "o") to 1.0, ("c" to "a") to 9.0, ("c" to "b") to 9.0,
        ), "합성 km", "검증용 합성 비용", true,
    )

    @Test fun `완전탐색은 모든 여섯 순서를 평가해 합성 비용의 최소 순서를 찾는다`() {
        val result = RoutePlanner.optimize("o", listOf("c", "b", "a"), matrix, RouteObjective.DISTANCE)
        assertEquals(listOf("a", "b", "c"), result.stopOrder)
        assertEquals(4.0, result.cost, 0.0)
        assertEquals(6, result.evaluatedOrders)
        assertTrue(result.synthetic)
        assertTrue(result.description.contains("실제 도로 최단 경로가 아닙니다"))
    }

    @Test fun `출발지 복귀 비용을 포함하는 선택을 구분한다`() {
        assertEquals(4.0, RoutePlanner.totalCost("o", listOf("a", "b", "c"), matrix, true), 0.0)
        assertEquals(3.0, RoutePlanner.totalCost("o", listOf("a", "b", "c"), matrix, false), 0.0)
    }

    @Test fun `고정 위치 제약을 지키며 승인 지점만 재정렬한다`() {
        val result = RoutePlanner.optimize("o", listOf("a", "b", "c"), matrix, RouteObjective.DISTANCE, fixedPositions = mapOf(0 to "b"))
        assertEquals("b", result.stopOrder.first())
        assertEquals(setOf("a", "b", "c"), result.stopOrder.toSet())
        assertEquals(2, result.evaluatedOrders)
    }

    @Test fun `고정 순서 모드는 입력 순서를 유지한다`() {
        val result = RoutePlanner.optimize("o", listOf("c", "b", "a"), matrix, RouteObjective.FIXED)
        assertEquals(listOf("c", "b", "a"), result.stopOrder)
        assertEquals(1, result.evaluatedOrders)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `누락된 비용은 영 거리로 바꾸지 않는다`() {
        RoutePlanner.optimize("o", listOf("a", "b"), CostMatrix(emptyMap(), "km", "누락", true), RouteObjective.DISTANCE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `음수 비용은 최적화 입력으로 허용하지 않는다`() {
        RoutePlanner.optimize("o", listOf("a"), CostMatrix(mapOf(("o" to "a") to -1.0, ("a" to "o") to 1.0), "km", "오류", true), RouteObjective.DISTANCE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `일곱 지점은 최적해 보장이 없는 근사 계산으로 몰래 전환하지 않는다`() {
        RoutePlanner.optimize("o", (1..7).map { "$it" }, matrix, RouteObjective.DISTANCE)
    }

    @Test fun `허용되지 않은 지점과 중복 지점은 수동 순서에 적용하지 않는다`() {
        val engine = ShuttleEngine()
        val time = 1_000L
        fun <T> obs(value: T) = Observation(value, Source.SIMULATED, time, ResultStatus.TARGET_OBSERVED)
        engine.updateVehicle(VehicleSnapshot(true, obs(0.0), obs(Gear.PARK), obs(true), obs(true)), time)
        assertFalse(engine.reorderStops(listOf("forest", "museum", "unknown"), time))
        assertFalse(engine.reorderStops(listOf("forest", "forest", "park"), time))
        assertEquals(listOf("forest", "museum", "park"), engine.state.routeOrder)
    }

    @Test fun `계산 뒤 기준을 바꾸면 이전 미리보기를 적용하지 않는다`() {
        val engine = ShuttleEngine()
        val time = 1_000L
        fun <T> obs(value: T) = Observation(value, Source.SIMULATED, time, ResultStatus.TARGET_OBSERVED)
        engine.updateVehicle(VehicleSnapshot(true, obs(0.0), obs(Gear.PARK), obs(true), obs(true)), time)
        assertTrue(engine.calculateRoute(time))
        val previous = engine.state.routeProposal!!
        assertTrue(engine.setRoutePolicy(RouteObjective.TIME, true, time))
        assertFalse(engine.previewRoute(previous))
        assertFalse(engine.applyRouteProposal(time))
    }

    @Test fun `직선거리 계산은 미터와 도로 거리로 오인되지 않게 단위를 표시한다`() {
        val trip = SampleTrip.create()
        val matrix = RoutePlanner.syntheticMatrix(trip.origin, trip.stops, RouteObjective.DISTANCE)
        assertTrue(matrix.synthetic)
        assertEquals("직선 km", matrix.unit)
        assertTrue(matrix.cost(trip.origin.id, trip.stops.first().id) > 1.0)
        assertEquals(matrix.cost("origin", "forest"), matrix.cost("forest", "origin"), 0.00001)
    }
}
