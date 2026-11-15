package com.hanhyo.pleosshuttle.domain

/**
 * 단위 테스트 전용 고정 운행(명단 4명 · 정류장 3곳). 출시 앱에는 들어가지 않는다(앱은 기관에서 불러온 운행만 쓴다).
 * 연동 방식은 TripState 기본값(SDK)이다.
 */
object SampleTrip {
    /** 같은 명단·정류장의 등원(PICK_UP) 운행. 어린이 나만 결석 알림(미탑승 예정)이다. */
    fun createPickup(): TripState = create().let { trip ->
        trip.copy(tripId = "fictional-morning-001", direction = TripDirections.PICK_UP,
            children = trip.children.map { if (it.id == "child-b") it.copy(notExpectedToBoard = true, notBoardingReason = "ABSENT") else it })
    }
    fun create(): TripState {
        val origin = Stop("origin", "출발지 · 서울시청", 37.5665, 126.9780)
        val stops = listOf(
            Stop("forest", "정류장 A · 서울숲", 37.5445, 127.0374),
            Stop("museum", "정류장 B · 국립중앙박물관", 37.5239, 126.9805),
            Stop("park", "정류장 C · 올림픽공원", 37.5207, 127.1214),
        )
        return TripState(
            tripId = "fictional-afternoon-001", origin = origin, stops = stops,
            children = listOf(
                ChildRecord("child-a", "어린이 가", "forest", "가상 좌석 1"),
                ChildRecord("child-b", "어린이 나", "forest", "가상 좌석 2"),
                ChildRecord("child-c", "어린이 다", "museum", "가상 좌석 3"),
                ChildRecord("child-d", "어린이 라", "park", "가상 좌석 4"),
            ),
        )
    }
}

/** 단위 테스트 전용 경로 규칙(출발 15:30 · 정차 3분 · 약속·진입 방향 규칙). */
object SampleRoutePlan {
    val departureMinute: Int = RouteTimelines.parseClock("15:30")
    const val DWELL_MINUTES = 3
    val returnDeadlineMinute: Int = RouteTimelines.parseClock("17:30")
    val rules = listOf(
        StopRule("forest", allowedPreviousIds = setOf("origin", "museum"), approachMessage = "올림픽공원 쪽에서 오면 길 건너편에 서게 돼요",
            memo = "가상 메모 · 정문 앞 버스 정류장 뒤에 정차 · 시청 쪽에서 진입"),
        StopRule("museum", earliestArrivalMinute = RouteTimelines.parseClock("16:30"), earliestLabel = "다 친구 보호자와의 약속(16:30 이후)",
            memo = "가상 메모 · 주차장 입구 옆 하차 구역 · 약속 16:30 이후"),
        StopRule("park", memo = "가상 메모 · 평화의 문 앞 하차 구역"),
    )
}
