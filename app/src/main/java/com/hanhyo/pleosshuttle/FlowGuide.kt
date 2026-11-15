package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

/** Presentation only: selecting the next visible control never performs a trip action. */
internal data class FlowGuide(
    val actor: String,
    val title: String,
    val detail: String,
    val targetTag: String?,
    /** [탑승 마침] 뒤 남은 아이마다 미탑승 확인을 받는 중(V-02). [탑승 마침] 버튼을 다시 보이지 않는다. */
    val confirmingRemaining: Boolean = false,
)

internal const val NO_TRIP_TODAY_TAG = "no-trip-today"
/** 불러온 운행이 없음(운행 없음 자리 표시 상태, [TripState.hasTrip] false). 기관 연결 → 오늘 운행 불러오기로 안내한다. */
internal const val NO_TRIP_LOADED_TAG = "no-trip-loaded"
/** 운행 준비에서 모든 아이가 미탑승 확인되어 탈 아이가 없는 상태(출발 불가, 취소는 명단에서). */
internal const val NO_RIDERS_TAG = "no-riders"
/** VS-17 등원 운행 준비(명단 확인 → [준비 마침]). */
internal const val PICKUP_PREPARE_TAG = "pickup-prepare"
/** VS-18 등원 정류장 인계받음·탑승(이 정류장 아이 행 + [다음 정류장으로]/[기관으로 출발]). */
internal const val PICKUP_STOP_TAG = "pickup-stop"
/** VS-20 기관 도착 뒤 한 화면: 처리 안 된 아이 [미탑승 확인] → 아이마다 기관 인계 → 실내 확인 → [운행 종료]. */
internal const val PICKUP_INSTITUTION_TAG = "pickup-institution"

internal fun nextFlowGuide(
    trip: TripState,
    boardingReviewed: Boolean = false,
    stationaryReady: Boolean = true,
    uxRestricted: Boolean = false,
    surroundingsWarning: Boolean = false,
): FlowGuide {
    if (!trip.hasTrip) return FlowGuide("선생님", "오늘 운행 불러오기", "불러온 운행 없음", NO_TRIP_LOADED_TAG)
    // Keep the upcoming destination visible while moving; arrival confirmation remains disabled.
    if (trip.phase == TripPhase.DRIVING) return FlowGuide("선생님", "도착·정차 확인", "도착 확인은 자동 위치 판정이 아닙니다.", if(trip.currentStop == null) "arrive-origin" else "arrive-stop")
    if (uxRestricted) return FlowGuide("선생님", "정차 후 확인하세요", "차량 조작 제한 중", null)
    // D34: 탈 아이 0명이면 탑승·출발 단계 대신 "오늘 운행 없음"만 보여준다(정차 여부와 무관).
    if (trip.noTripToday) return FlowGuide("선생님", "오늘 운행 없음", "탈 어린이 0명", NO_TRIP_TODAY_TAG)
    if (trip.phase != TripPhase.ENDED && !stationaryReady) return FlowGuide("선생님", "정차 상태 확인", "최신 정차 정보 필요", "stationary-status")
    if (trip.isPickup) return pickupFlowGuide(trip, boardingReviewed, surroundingsWarning)
    return when(trip.phase) {
        TripPhase.PREPARING -> {
            // 미탑승 확인한 아이(CONFIRMED_NOT_BOARDED)는 건너뛴다. 남은 아이는 탑승 기록도 미탑승 확인도 없는 아이뿐.
            val child = trip.undecidedChildren.firstOrNull()
            val reviewed = boardingReviewed && trip.onboardChildren.isNotEmpty()
            when {
                child == null && trip.onboardChildren.isEmpty() -> FlowGuide("선생님", "탑승한 어린이 없음", "모두 미탑승 확인", NO_RIDERS_TAG)
                child == null -> FlowGuide("선생님", "운행 시작", "방문 순서는 자동 계산", "start-trip")
                // V-02: 탑승 마침 뒤 탑승 기록이 없는 아이마다 선생님이 미탑승을 확인한다. 미탑승 예정 아이는 처음부터 미탑승 확인이 먼저.
                reviewed -> FlowGuide("선생님", "미탑승 확인", "탑승 기록 없는 어린이", "not-boarded-${child.id}", confirmingRemaining = true)
                !child.expectedToBoard -> FlowGuide("선생님", "미탑승 확인", "오늘 미탑승 예정", "not-boarded-${child.id}")
                else -> FlowGuide("선생님", "탑승 기록", "한 명씩 확인", "board-${child.id}")
            }
        }
        TripPhase.ALIGHTING -> {
            // V-21: 동승 유지 아이는 인계·하차 대상이 아니다(처리됨). 모두 처리되면 [다음 지점으로] 단계에서 동승 유지 취소를 함께 보여 준다.
            val child = trip.currentStopChildren.firstOrNull { it.awaitingAlighting }
            when {
                surroundingsWarning -> FlowGuide("운전자", "주변 위험 확인", "경고 유지", "surroundings-status")
                child != null && child.id !in trip.handoverChildIds -> FlowGuide("선생님", "보호자 인계 확인", "직접 인계", "handover-${child.id}")
                child != null -> FlowGuide("선생님", "하차 기록", "직접 하차 확인", "alight-${child.id}")
                else -> FlowGuide("선생님", "다음 지점", "기록 완료", "complete-stop")
            }
        }
        TripPhase.END_REVIEW -> {
            val child = trip.onboardChildren.firstOrNull()
            when {
                // V-22: 동승 유지로 돌아온 아이는 기관 담당자에게 직접 인계하고 하차 기록한다.
                child != null && child.staysOnboard -> FlowGuide("선생님", "동승 유지 어린이 기관 인계", "기관 직접 인계", "return-org-${child.id}")
                child != null && child.id !in trip.handoverChildIds -> FlowGuide("선생님", "미하차 기록 확인", "인계 확인", "end-handover-${child.id}")
                child != null -> FlowGuide("선생님", "기록 정정", "사유 기록", "correct-${child.id}")
                else -> FlowGuide("선생님", "실내 확인 후 종료", "직접 확인과 종료 저장", "confirm-finish")
            }
        }
        TripPhase.ENDED -> FlowGuide("완료", "하원 기록 저장", "기록 확인", "view-history")
        TripPhase.DRIVING -> error("Handled above")
    }
}

/**
 * 등원(V-25~V-29) 단계 안내. 하원 단계(nextFlowGuide)는 그대로 두고 등원만 여기서 정한다.
 * [boardingReviewed]는 VS-17 [준비 마침]을 누른 뒤(순서 확인·출발 전 확인 단계)를 뜻한다.
 */
private fun pickupFlowGuide(trip: TripState, boardingReviewed: Boolean, surroundingsWarning: Boolean): FlowGuide = when (trip.phase) {
    TripPhase.PREPARING ->
        if (boardingReviewed) FlowGuide("선생님", "운행 시작", "방문 순서 확인", "start-trip")
        else FlowGuide("선생님", "등원 준비", "알림 없는 아이는 정류장에서 확정", PICKUP_PREPARE_TAG)
    TripPhase.ALIGHTING -> when {
        surroundingsWarning -> FlowGuide("운전자", "주변 위험 확인", "경고 유지", "surroundings-status")
        else -> FlowGuide("선생님", "인계받음·탑승", "정류장 아이마다 기록", PICKUP_STOP_TAG)
    }
    // VS-20: 처리 안 된 아이 미탑승 확인 → 탄 아이마다 기관 인계(하차 기록) → 실내 확인 → [운행 종료](S-19). 한 화면에서 처리한다.
    TripPhase.END_REVIEW -> FlowGuide("선생님", "기관 인계", "아이마다 기관 담당자 인계", PICKUP_INSTITUTION_TAG)
    TripPhase.ENDED -> FlowGuide("완료", "등원 기록 저장", "기록 확인", "view-history")
    TripPhase.DRIVING -> error("Handled above")
}

/** Excludes clock ticks, observation timestamps and diagnostic revisions to avoid scroll jumps. */
internal fun flowProgressKey(trip: TripState, boardingReviewed: Boolean): List<Any?> = listOf(
    trip.tripId, trip.phase, trip.integrationMode, trip.currentStopIndex,
    trip.children.map { Triple(it.id, it.status, it.stayOnboardReason) }, trip.routeIsExample, trip.routeProposal, trip.routeVersion,
    trip.manualSurroundingsCheckAtMillis, trip.unlockConfirmation?.id,
    trip.unlockAttempt?.status, trip.unlockAttempt?.completedAtMillis, trip.unlockAttempt?.invalidated,
    trip.manualDoorAtMillis, trip.handoverChildIds, trip.directCabinCheckAtMillis,
    boardingReviewed,
)
