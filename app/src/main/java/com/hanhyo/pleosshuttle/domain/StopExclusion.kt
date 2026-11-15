package com.hanhyo.pleosshuttle.domain

/**
 * V-09·D17 빈 정류장 제외 제안. 빼기는 "제안"만 하고 선생님이 확인해야 빠진다(기본값은 순서에 남음).
 * 넣기는 자동: 실제 상태 전이(정정·미탑승 확인 취소)로 그 정류장에 다시 내릴 아이가 생기면 방문 순서에 다시 넣는다.
 * 순수 함수만 둔다(엔진 상태는 바꾸지 않음).
 */
object StopExclusions {
    /**
     * 이 아이 때문에 정류장을 들러야 하는지.
     * - 운행 준비: 선생님이 미탑승 확인한 아이만 "안 탐"으로 본다(탑승 기록·아직 결정 전은 들를 수 있음 → 제외 제안 안 함).
     * - 출발 뒤: 지금 탑승 중이고 동승 유지(V-21)가 아닌 아이만. 동승 유지 아이는 원래 정류장을 이미 지났으므로 셈하지 않는다.
     * - 등원(V-25) 출발 뒤: 아직 탑승도 미탑승 확인도 없는 아이(정류장에서 태우거나 미탑승 확인해야 함).
     */
    fun needsStop(trip: TripState, child: ChildRecord): Boolean = when {
        trip.phase == TripPhase.PREPARING -> child.status != Attendance.CONFIRMED_NOT_BOARDED
        trip.isPickup -> child.status == Attendance.NOT_BOARDED
        else -> child.awaitingAlighting
    }

    fun hasRiders(trip: TripState, stopId: String): Boolean = trip.children.any { it.stopId == stopId && needsStop(trip, it) }

    /** 순서를 바꾸거나 정류장을 뺄 수 없는 앞부분 길이(운행 준비 0, 정차 중이면 지나온 지점 + 지금 정류장). */
    fun lockedCount(trip: TripState): Int = when (trip.phase) {
        TripPhase.PREPARING -> 0
        TripPhase.ALIGHTING -> trip.currentStopIndex + 1
        else -> trip.routeOrder.size
    }

    /** 제외 제안 대상: 아직 들르지 않은 정류장 중 내릴 아이가 0명인 곳. 운행 준비와 정차 중(V-11)에만 제안한다. */
    fun suggestions(trip: TripState): List<Stop> {
        if (trip.phase != TripPhase.PREPARING && trip.phase != TripPhase.ALIGHTING) return emptyList()
        if (trip.phase == TripPhase.PREPARING && trip.noTripToday) return emptyList()
        return trip.routeOrder.drop(lockedCount(trip))
            .filter { id -> !hasRiders(trip, id) }
            .mapNotNull { id -> trip.stops.find { it.id == id } }
    }

    /** 뺀 정류장 중 다시 내릴 아이가 생겨 자동으로 다시 넣어야 하는 곳(운행 준비·정차 중에만). */
    fun autoReinclude(trip: TripState): List<String> {
        if (trip.phase != TripPhase.PREPARING && trip.phase != TripPhase.ALIGHTING) return emptyList()
        return trip.excludedStops.filter { hasRiders(trip, it) }
    }

    /**
     * [stopId]를 다시 넣을 방문 순서. 고정 앞부분(지난 정류장 · 정차 중인 지금 정류장) 뒤에서, 원래 정류장 순서
     * ([TripState.stops] = 서버 seq 순서) 기준 자리에 넣는다: 원래 순서가 [stopId]보다 뒤인 첫 정류장 바로 앞, 없으면 맨 뒤.
     * 다른 정류장의 순서는 바꾸지 않는다. 기관 운행에서 앱이 서버 순서와 다른 순서를 만들지 않게 하기 위해서다.
     */
    fun insertOrder(trip: TripState, stopId: String): List<String> {
        val order = trip.routeOrder.filter { it != stopId }
        val locked = lockedCount(trip).coerceIn(0, order.size)
        val seq = trip.stops.indexOfFirst { it.id == stopId }
        fun seqOf(id: String) = trip.stops.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }
        val after = (locked until order.size).firstOrNull { seqOf(order[it]) > seq } ?: order.size
        return order.toMutableList().apply { add(if (seq < 0) order.size else after, stopId) }
    }

    /**
     * 서버 route-revisions 차량 허용 후 재연결 예정(지금은 쓰지 않음): [stopId]를 고정 앞부분 뒤 가장 싼 위치
     * (합성 직선거리, 출발지 복귀 반영)에 끼워 넣는다.
     */
    fun insertOrderByDistance(trip: TripState, stopId: String): List<String> {
        val order = trip.routeOrder.filter { it != stopId }
        val locked = lockedCount(trip).coerceIn(0, order.size)
        val matrix = RoutePlanner.syntheticMatrix(trip.origin, trip.stops, RouteObjective.DISTANCE)
        return (locked..order.size).map { index -> order.toMutableList().apply { add(index, stopId) } }
            .minByOrNull { runCatching { RoutePlanner.totalCost(trip.origin.id, it, matrix, trip.returnToOrigin) }.getOrDefault(Double.MAX_VALUE) }
            ?: (order + stopId)
    }

    object Records {
        const val EXCLUDED = "정류장 제외 · 내릴 어린이 없음 · 선생님 확인"
        const val INCLUDED = "정류장 다시 넣기 · 선생님"
        const val AUTO_INCLUDED = "정류장 자동 다시 넣기 · 다시 내릴 어린이 있음"
    }
}

/** V-12·V-14: Pleos에 요청할 남은 경유지(현재 목적지부터 최대 [max]곳, 최대 3지점 묶음). 뺀 정류장(V-09)은 순서에 없어 들어가지 않는다. */
fun navigationWaypoints(trip: TripState, max: Int = 3): List<Stop> =
    trip.routeOrder.drop(trip.currentStopIndex).take(max).mapNotNull { id -> trip.stops.find { it.id == id } }
