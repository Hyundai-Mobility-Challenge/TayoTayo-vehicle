package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.ChildRecord
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.Stop
import com.hanhyo.pleosshuttle.domain.TripState

/**
 * 기기 테스트 전용 운행(명단 4명 · 정류장 3곳). 출시 앱에는 가상 운행이 없으므로, 저장 복원·화면 테스트는
 * 이 운행을 trip-v1.bin에 직접 저장해 준비한다. 연동 방식은 SDK(기관 운행과 같음).
 */
object DeviceTrips {
    /** [routeIsExample]=true면 [운행 시작] 때 방문 순서를 자동 계산한다(거리 기준 forest → park → museum). */
    fun sdkTrip(tripId: String, routeIsExample: Boolean = false): TripState {
        val origin = Stop("origin", "출발지 · 서울시청", 37.5665, 126.9780)
        val stops = listOf(
            Stop("forest", "정류장 A · 서울숲", 37.5445, 127.0374),
            Stop("museum", "정류장 B · 국립중앙박물관", 37.5239, 126.9805),
            Stop("park", "정류장 C · 올림픽공원", 37.5207, 127.1214),
        )
        return TripState(
            tripId = tripId, integrationMode = IntegrationMode.SDK, origin = origin, stops = stops, routeIsExample = routeIsExample,
            children = listOf(
                ChildRecord("child-a", "어린이 가", "forest", ""),
                ChildRecord("child-b", "어린이 나", "forest", ""),
                ChildRecord("child-c", "어린이 다", "museum", ""),
                ChildRecord("child-d", "어린이 라", "park", ""),
            ),
        )
    }

    /** 이전 빌드가 저장했을 법한 모의 운행(연동 방식 SIMULATION). 앱은 이 운행을 버리고 "운행 없음"으로 시작해야 한다. */
    fun legacySimulationTrip(): TripState = sdkTrip("fictional-afternoon-001").copy(integrationMode = IntegrationMode.SIMULATION)
}
