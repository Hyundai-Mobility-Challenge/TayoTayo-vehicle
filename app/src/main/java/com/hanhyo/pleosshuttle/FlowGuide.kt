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
/** 운행 준비에서 모든 아이가 미탑승 확인되어 탈 아이가 없는 상태(출발 불가, 취소는 명단에서). */
internal const val NO_RIDERS_TAG = "no-riders"

internal fun nextFlowGuide(
    trip: TripState,
    boardingReviewed: Boolean = false,
    stationaryReady: Boolean = true,
    uxRestricted: Boolean = false,
    surroundingsWarning: Boolean = false,
): FlowGuide {
    // Keep the upcoming destination visible while moving; arrival confirmation remains disabled.
    if (trip.phase == TripPhase.DRIVING) return FlowGuide("선생님", "도착·정차 확인", "도착 확인은 자동 위치 판정이 아닙니다.", if(trip.currentStop == null) "arrive-origin" else "arrive-stop")
    if (uxRestricted) return FlowGuide("선생님", "정차 후 확인하세요", "차량 조작 제한 중", null)
    // D34: 탈 아이 0명이면 탑승·출발 단계 대신 "오늘 운행 없음"만 보여준다(정차 여부와 무관).
    if (trip.noTripToday) return FlowGuide("선생님", "오늘 운행 없음", "탈 어린이 0명", NO_TRIP_TODAY_TAG)
    if (trip.phase != TripPhase.ENDED && !stationaryReady) return FlowGuide("선생님", "정차 상태 확인", "최신 정차 정보 필요", "stationary-status")
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
            val child = trip.currentStopChildren.firstOrNull { it.status == Attendance.ONBOARD }
            when {
                surroundingsWarning -> FlowGuide("운전자", "주변 위험 확인", "경고 유지", "surroundings-status")
                child != null && child.id !in trip.handoverChildIds -> FlowGuide("선생님", "보호자 인계 확인", "직접 인계", "handover-${child.id}")
                child != null -> FlowGuide("선생님", "하차 기록", "직접 하차 확인", "alight-${child.id}")
                trip.unlockAttempt?.let { it.completedAtMillis == null } == true -> FlowGuide("운전자", "별도 문 시험 응답 대기", "시험 진행 중", "door-result")
                else -> FlowGuide("선생님", "다음 지점", "기록 완료", "complete-stop")
            }
        }
        TripPhase.END_REVIEW -> {
            val child = trip.onboardChildren.firstOrNull()
            when {
                child != null && child.id !in trip.handoverChildIds -> FlowGuide("선생님", "미하차 기록 확인", "인계 확인", "end-handover-${child.id}")
                child != null -> FlowGuide("선생님", "기록 정정", "사유 기록", "correct-${child.id}")
                else -> FlowGuide("선생님", "실내 확인 후 종료", "직접 확인과 종료 저장", "confirm-finish")
            }
        }
        TripPhase.ENDED -> FlowGuide("완료", "하원 기록 저장", "기록 확인", "view-history")
        TripPhase.DRIVING -> error("Handled above")
    }
}

/** Excludes clock ticks, observation timestamps and diagnostic revisions to avoid scroll jumps. */
internal fun flowProgressKey(trip: TripState, boardingReviewed: Boolean): List<Any?> = listOf(
    trip.tripId, trip.phase, trip.integrationMode, trip.currentStopIndex,
    trip.children.map { it.id to it.status }, trip.routeIsExample, trip.routeProposal, trip.routeVersion,
    trip.manualSurroundingsCheckAtMillis, trip.unlockConfirmation?.id,
    trip.unlockAttempt?.status, trip.unlockAttempt?.completedAtMillis, trip.unlockAttempt?.invalidated,
    trip.manualDoorAtMillis, trip.handoverChildIds, trip.directCabinCheckAtMillis,
    boardingReviewed,
)
