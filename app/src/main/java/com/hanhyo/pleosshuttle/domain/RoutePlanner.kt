package com.hanhyo.pleosshuttle.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Directed costs. No road-routing service is implied by this matrix. */
data class CostMatrix(
    val costs: Map<Pair<String, String>, Double>,
    val unit: String,
    val sourceDescription: String,
    val synthetic: Boolean,
) {
    fun cost(from: String, to: String): Double {
        if (from == to) return 0.0
        val value = requireNotNull(costs[from to to]) { "누락된 비용: $from → $to" }
        require(value.isFinite() && value >= 0.0) { "비용은 0 이상의 유한값이어야 합니다." }
        return value
    }
}

object RoutePlanner {
    /** Exhaustive permutations, at most 6 stops (720 candidates); optimal only for supplied costs. */
    fun optimize(
        originId: String,
        approvedStopIds: List<String>,
        matrix: CostMatrix,
        objective: RouteObjective,
        returnToOrigin: Boolean = true,
        routeVersion: Long = 0,
        fixedPositions: Map<Int, String> = emptyMap(),
    ): RouteProposal {
        require(approvedStopIds.size <= 6) { "이 PoC의 완전탐색은 최대 6개 지점만 지원합니다." }
        require(approvedStopIds.toSet().size == approvedStopIds.size) { "승인 지점 ID는 중복될 수 없습니다." }
        require(originId !in approvedStopIds) { "출발지는 방문 지점과 다른 ID를 사용합니다." }
        require(fixedPositions.all { it.key in approvedStopIds.indices && it.value in approvedStopIds }) { "고정 순서가 승인 지점 범위를 벗어났습니다." }
        require(fixedPositions.values.toSet().size == fixedPositions.size) { "동일 지점을 여러 위치에 고정할 수 없습니다." }
        var count = 0
        var bestOrder: List<String>? = null
        var bestCost = Double.POSITIVE_INFINITY
        fun evaluate(order: List<String>) {
            if (fixedPositions.any { order[it.key] != it.value }) return
            count++
            val cost = totalCost(originId, order, matrix, returnToOrigin)
            if (cost < bestCost) { bestCost = cost; bestOrder = order.toList() }
        }
        fun enumerate(prefix: List<String>, left: List<String>) {
            if (left.isEmpty()) { evaluate(prefix); return }
            left.forEach { id ->
                val required = fixedPositions[prefix.size]
                if (required == null || required == id) enumerate(prefix + id, left - id)
            }
        }
        if (objective == RouteObjective.FIXED) evaluate(approvedStopIds) else enumerate(emptyList(), approvedStopIds)
        val order = requireNotNull(bestOrder) { "고정 순서를 만족하는 방문 순서가 없습니다." }
        return RouteProposal(
            stopOrder = order, cost = bestCost, costUnit = matrix.unit,
            description = "${matrix.sourceDescription} · ${if (objective == RouteObjective.FIXED) "고정 순서 평가" else "모든 후보 $count 개 완전탐색"} · ${if (returnToOrigin) "출발지 복귀 포함" else "마지막 지점에서 종료"}. 실제 도로 최단 경로가 아닙니다.",
            objective = objective, returnToOrigin = returnToOrigin, routeVersion = routeVersion,
            evaluatedOrders = count, synthetic = matrix.synthetic,
        )
    }

    fun totalCost(originId: String, order: List<String>, matrix: CostMatrix, returnToOrigin: Boolean): Double {
        if (order.isEmpty()) return 0.0
        val path = listOf(originId) + order + if (returnToOrigin) listOf(originId) else emptyList()
        return path.zipWithNext().sumOf { (from, to) -> matrix.cost(from, to) }
    }

    fun syntheticMatrix(origin: Stop, stops: List<Stop>, objective: RouteObjective): CostMatrix {
        val all = listOf(origin) + stops
        val costs = all.flatMap { from -> all.map { to ->
            val km = straightLineKm(from, to)
            val cost = if (objective == RouteObjective.TIME && from.id != to.id) km / 20.0 * 60.0 + 1.0 else km
            (from.id to to.id) to cost
        } }.toMap()
        return CostMatrix(
            costs = costs,
            unit = if (objective == RouteObjective.TIME) "모의 분" else "직선 km",
            sourceDescription = if (objective == RouteObjective.TIME) "합성 시간 비용: 직선거리·가정 속도 20km/h·지점당 1분" else "직선거리 기반 합성 비용 행렬",
            synthetic = true,
        )
    }

    fun plan(trip: TripState): RouteProposal {
        val objective = trip.routeObjective ?: RouteObjective.DISTANCE
        return optimize(trip.origin.id, trip.routeOrder, syntheticMatrix(trip.origin, trip.stops, objective), objective, trip.returnToOrigin, trip.routeVersion)
    }

    private fun straightLineKm(a: Stop, b: Stop): Double {
        fun rad(degrees: Double) = Math.toRadians(degrees)
        val latitude = rad(b.latitude - a.latitude)
        val longitude = rad(b.longitude - a.longitude)
        val term = sin(latitude / 2) * sin(latitude / 2) + cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(longitude / 2) * sin(longitude / 2)
        return 6_371.0 * 2 * asin(sqrt(term.coerceIn(0.0, 1.0)))
    }
}
