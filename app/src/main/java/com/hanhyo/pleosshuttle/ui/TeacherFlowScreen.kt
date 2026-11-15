package com.hanhyo.pleosshuttle.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.hanhyo.pleosshuttle.FlowGuide
import com.hanhyo.pleosshuttle.NO_RIDERS_TAG
import com.hanhyo.pleosshuttle.NO_TRIP_LOADED_TAG
import com.hanhyo.pleosshuttle.NO_TRIP_TODAY_TAG
import com.hanhyo.pleosshuttle.PICKUP_INSTITUTION_TAG
import com.hanhyo.pleosshuttle.PICKUP_PREPARE_TAG
import com.hanhyo.pleosshuttle.PICKUP_STOP_TAG
import com.hanhyo.pleosshuttle.pickupBoardingChildren
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.StayOnboardReasons
import com.hanhyo.pleosshuttle.domain.notBoardingBadge
import com.hanhyo.pleosshuttle.sdk.SdkOutcome
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.LocalCompactLayout

/** Teacher actions are explicit; destination and guidance advance after the last child record. */
@Composable internal fun TeacherFlow(vm: ShuttleViewModel, guide: FlowGuide, onBoardingReviewed: () -> Unit) {
    val tag = guide.targetTag
    val child = vm.trip.children.firstOrNull { tag in listOf("board-${it.id}", "not-boarded-${it.id}", "handover-${it.id}", "alight-${it.id}", "end-handover-${it.id}", "correct-${it.id}", "return-org-${it.id}") }
    val pickup = vm.trip.isPickup
    val stationary = vm.engine.stationaryBlockers(vm.now).isEmpty()
    val uxLocked = vm.sdkVehicle.uxRestricted.value == true
    val type = AppTheme.typography
    val question = type.titleBold.copy(fontWeight = FontWeight.Medium)
    val hint = type.body2Regular
    val warning = AppTheme.colors.primaryContent
    // V-21: [보호자 미도착 · 동승 유지]를 누르면 같은 카드에서 고정 사유를 고른다(단계가 바뀌면 닫힘).
    var choosingStay by remember(tag) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("flow-guide"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xl)) {
        Text("동승 선생님 · ${flowTitle(vm.trip)}", style = type.body1SemiBold, color = AppTheme.colors.infoContent)
        val title = when {
            tag == NO_TRIP_LOADED_TAG -> "오늘 운행을 불러와 주세요"
            tag == NO_TRIP_TODAY_TAG -> "오늘 ${vm.tripDirectionLabel} 운행 없음"
            tag == NO_RIDERS_TAG -> "탑승한 어린이가 없어요"
            child != null -> child.name
            tag == PICKUP_PREPARE_TAG -> "등원 명단을 확인해 주세요"
            tag == PICKUP_STOP_TAG -> "인계받고 태워 주세요"
            tag == PICKUP_INSTITUTION_TAG -> "기관 담당자에게 인계해 주세요"
            tag == "start-trip" -> "${vm.tripDirectionLabel} 준비가 끝났어요"
            tag == "arrive-stop" -> "도착하면 확인해 주세요"
            tag == "arrive-origin" -> if (pickup) "기관으로 가요" else "${withRo(vm.trip.origin.shortName)} 돌아가요"
            tag == "complete-stop" -> "이 지점의 기록을 마쳤어요"
            tag == "confirm-finish" -> "차량 실내를 확인해 주세요"
            tag == "view-history" -> "오늘의 ${vm.tripDirectionLabel}을 마쳤어요"
            tag == "surroundings-status" -> "주변 위험 경고가 있어요"
            else -> "정차 후 확인해 주세요"
        }
        Text(title, style = if (LocalCompactLayout.current) type.head3Bold else type.head2Bold, modifier = Modifier.testTag("guide-next-action"))
        when {
            tag == NO_TRIP_LOADED_TAG -> NoTripLoadedBody(vm)
            tag == NO_TRIP_TODAY_TAG -> {
                // D34: 탈 어린이 0명 → 탑승 기록·[운행 시작] 없이 안내만 한다.
                Text("오늘 탈 어린이가 없어요 · 운행 없음", style = question, modifier = Modifier.testTag(tag))
                Text(if (vm.trip.children.isEmpty()) "배정된 어린이가 없어요." else "명단 ${vm.trip.children.size}명 모두 오늘 미탑승 예정이에요.", style = hint, color = AppTheme.colors.textSecondary)
                Text("명단이 바뀌었다면 기관 연결에서 명단을 다시 불러와 주세요.", style = hint, color = AppTheme.colors.textSecondary)
                Action("기관 연결 열기", "open-institution", secondary = true) { vm.panel = "institution" }
            }
            tag == NO_RIDERS_TAG -> {
                // 모두 미탑승 확인 → 출발하지 않음. 되돌리기는 명단의 [취소](출발 전·정차 중).
                Text("명단 ${vm.trip.children.size}명 모두 미탑승 확인했어요.", style = question, modifier = Modifier.testTag(tag))
                Text("다시 태우려면 명단에서 미탑승 확인을 취소해 주세요.", style = hint, color = AppTheme.colors.textSecondary)
                Action("명단 열기", "open-roster", secondary = true) { vm.panel = "details" }
            }
            tag == PICKUP_PREPARE_TAG -> PickupPrepareBody(vm, onBoardingReviewed)
            tag == PICKUP_STOP_TAG -> PickupStopBody(vm)
            tag == PICKUP_INSTITUTION_TAG -> PickupInstitutionBody(vm)
            (tag?.startsWith("board-") == true || tag?.startsWith("not-boarded-") == true) && child != null -> {
                // V-02: 미탑승 예정 배지는 표시만 하고, 탑승·미탑승은 선생님이 정한다. 미탑승 예정이거나 탑승 마침 뒤면 [미탑승 확인]이 먼저.
                notBoardingBadge(child)?.let { StatusChip(it, tone = ChipTone.ACCENT, modifier = Modifier.testTag("not-expected-badge-${child.id}")) }
                val confirmFirst = tag.startsWith("not-boarded-")
                Text(when {
                    !confirmFirst -> "차에 탔나요?"
                    child.expectedToBoard -> "탑승 기록이 없어요 · 오늘 안 탔나요?"
                    else -> "오늘 안 타나요?"
                }, style = question)
                if (confirmFirst) {
                    Action("미탑승 확인", "not-boarded-${child.id}", prominent = true) { vm.perform { confirmNotBoarded(child.id, it) } }
                    Action("탑승 기록", "board-${child.id}", secondary = true) { vm.perform { board(child.id, it) } }
                } else {
                    Action("탑승 기록", "board-${child.id}", prominent = true) { vm.perform { board(child.id, it) } }
                    Action("미탑승 확인", "not-boarded-${child.id}", secondary = true) { vm.perform { confirmNotBoarded(child.id, it) } }
                }
                if (vm.trip.onboardChildren.isNotEmpty() && !guide.confirmingRemaining) Action("탑승 마침 · ${vm.trip.onboardChildren.size}명", "review-boarding", secondary = true, action = onBoardingReviewed)
                if (guide.confirmingRemaining) Text("탑승 기록이 없는 어린이 ${vm.trip.undecidedChildren.size}명 · 한 명씩 미탑승을 확인해 주세요.", style = hint, color = AppTheme.colors.textSecondary)
                val confirmed = vm.trip.confirmedNotBoardedChildren.size
                if (confirmed > 0) Text("미탑승 확인 ${confirmed}명 · 취소는 명단에서", style = hint, color = AppTheme.colors.textSecondary)
            }
            (tag?.startsWith("handover-") == true || tag?.startsWith("end-handover-") == true) && child != null -> {
                val atStop = tag?.startsWith("handover-") == true
                if (atStop && choosingStay) {
                    // V-21: 사유는 고정 선택지만(자유 입력 없음 → 보호자·대리인 개인정보를 남기지 않음, 7절).
                    Text("동승 유지 사유를 골라 주세요", style = question)
                    Text("아이는 내리지 않고 계속 타고 가요. 복귀 후 기관에 인계해요.", style = hint, color = AppTheme.colors.textSecondary)
                    StayOnboardReasons.ALL.forEach { code ->
                        Action(StayOnboardReasons.label(code), "stay-reason-$code-${child.id}", enabled = stationary) {
                            vm.perform { markStayOnboard(child.id, code, it) }
                        }
                    }
                    Action("돌아가기", "stay-cancel-${child.id}", secondary = true) { choosingStay = false }
                } else {
                    Text("보호자에게 직접 인계했나요?", style = question)
                    Action("인계 확인", tag, prominent = true) { vm.perform { confirmHandover(child.id, it) } }
                    if (atStop) {
                        Action("보호자 미도착 · 동승 유지", "stay-onboard-${child.id}", secondary = true, enabled = stationary) { choosingStay = true }
                    }
                }
            }
            tag?.startsWith("alight-") == true && child != null -> {
                Text("인계 확인 완료 · 차에서 내렸나요?", style = question)
                Action("하차 기록", tag, prominent = true) { vm.perform { recordAlightingAndContinue(child.id, it) } }
            }
            tag?.startsWith("return-org-") == true && child != null -> {
                // V-22: 동승 유지(V-21)로 돌아온 아이. 선생님이 기관 담당자에게 직접 인계한 뒤 하차 기록한다.
                StatusChip("동승 유지 · ${StayOnboardReasons.label(child.stayOnboardReason)}", tone = ChipTone.ACCENT)
                Text("기관 담당자에게 직접 인계했나요?", style = question)
                Action("기관 인계 확인 · 하차 기록", tag, prominent = true) { vm.perform { returnStayOnboardToOrg(child.id, it) } }
            }
            tag?.startsWith("correct-") == true && child != null -> {
                Text("실제 상태와 다른 기록을 정정해 주세요.", style = question)
                Correction(child, vm, allowAlight = true)
            }
            tag == "start-trip" -> {
                // 인원·벨트·경로 요약은 [운행 시작] 바로 위 "출발 전 확인" 블록(V-08)에 모아, 여기서는 중복하지 않는다.
                if (vm.trip.confirmedNotBoardedChildren.isNotEmpty()) Text("미탑승 확인 ${vm.trip.confirmedNotBoardedChildren.size}명 · 출발 전에는 명단에서 취소할 수 있어요", style = hint, color = AppTheme.colors.textSecondary)
                if (pickup) Text("$PICKUP_PREPARE_NOTE · 탑승은 정류장에서 인계받은 뒤 기록해요", style = hint, color = AppTheme.colors.textSecondary)
                RouteCheckBody(vm)
            }
            tag in setOf("arrive-stop", "arrive-origin") -> {
                Text(when {
                    pickup && vm.trip.currentStop == null -> "기관에 도착하면 기관 도착을 확인해요."
                    pickup -> "탈 어린이: " + pickupBoardingChildren(vm.trip, vm.trip.currentStop).joinToString { it.name }.ifBlank { "없음" }
                    vm.trip.currentStop == null -> "복귀 후 차량 실내를 확인해요."
                    else -> "내릴 어린이: " + vm.trip.currentStopChildren.filter { it.awaitingAlighting }.joinToString { it.name }.ifBlank { "없음" }
                }, style = question)
                // VS-09 등원: 앞 정류장(지나친 정류장 포함)에서 처리 안 된 아이는 다음 정차 화면에서 미탑승 확인한다.
                if (pickup && vm.trip.currentStop != null) {
                    val earlier = vm.trip.undecidedChildren.count { it.stopId != vm.trip.currentStop?.id && vm.trip.routeOrder.indexOf(it.stopId) < vm.trip.currentStopIndex }
                    if (earlier > 0) Text("앞 정류장에서 확인하지 못한 어린이 ${earlier}명 · 정차 후 확인해요", style = hint, color = warning)
                }
                if (vm.trip.currentStop == null && vm.trip.stayOnboardChildren.isNotEmpty())
                    Text("동승 유지 ${vm.trip.stayOnboardChildren.size}명 · 복귀 후 기관에 인계해요", style = hint, color = AppTheme.colors.textSecondary)
                vm.consoleDriveLine?.let { Text(it, style = hint, color = AppTheme.colors.infoContent, modifier = Modifier.testTag("console-drive-card-line")) }
                Text("운전자에게 도착과 정차를 확인해 주세요.", style = hint, color = AppTheme.colors.textSecondary)
                if (!stationary || uxLocked) Text(if (uxLocked) "차량 조작 제한 중" else vm.engine.stationaryBlockers(vm.now).first(), color = warning, style = type.body2SemiBold)
                if (vm.navi.outcome in setOf(SdkOutcome.ERROR, SdkOutcome.TIMEOUT, SdkOutcome.PERMISSION_REQUIRED)) Text("길 안내 연결 실패 · 상세에서 확인해 주세요.", color = warning, style = type.body2SemiBold)
                Action(if (tag == "arrive-origin") (if (pickup) "기관 도착 확인" else "복귀·정차 확인") else "도착·정차 확인", tag!!, prominent = true, enabled = stationary && !uxLocked) {
                    vm.perform { if (tag == "arrive-origin") beginEndReview(it) else chooseAlighting(it) }
                }
            }
            tag == "complete-stop" -> {
                // V-21: 이 정류장에서 동승 유지로 남긴 아이. 정류장을 떠나기 전(하차 단계)에는 취소하고 인계로 돌아갈 수 있다.
                vm.trip.currentStopChildren.filter { it.status == Attendance.ONBOARD && it.staysOnboard }.forEach { stay ->
                    Text("${stay.name} · 동승 유지(${StayOnboardReasons.label(stay.stayOnboardReason)})", style = hint, color = AppTheme.colors.textSecondary)
                    Action("동승 유지 취소 · 인계로", "stay-undo-${stay.id}", secondary = true, enabled = stationary) { vm.perform { undoStayOnboard(stay.id, it) } }
                }
                Action("다음 지점으로", tag, prominent = true) { vm.perform { completeCurrentStop(it) } }
            }
            tag == "confirm-finish" -> {
                Text("남아 있는 어린이가 없는지 직접 살펴보세요.", style = question)
                Action("실내 확인 완료 · 운행 종료", tag, prominent = true) { vm.perform { inspectAndFinish(it) } }
            }
            tag == "view-history" -> {
                Text(if (pickup) "탑승·기관 인계 기록과 실내 확인을 저장했어요." else "하차 기록과 실내 확인을 저장했어요.", style = question)
                Action("완료 기록 보기", tag, prominent = true) { vm.panel = "history" }
                Action("다음 운행 불러오기", "next-trip", secondary = true) { vm.panel = "institution" }
            }
            tag == "surroundings-status" -> Text("운전자가 주변 위험을 확인해야 해요.", style = question, modifier = Modifier.testTag(tag))
            tag == "stationary-status" -> Text(vm.engine.stationaryBlockers(vm.now).firstOrNull() ?: "차량 상태 확인 중", style = question, modifier = Modifier.testTag(tag))
            else -> Text("지금은 조작할 수 없어요.", style = question)
        }
        // V-09·D17: 정차 중(하차 단계)에 아직 들르지 않은 정류장이 빈 정류장이 되면(정정 등) 여기서 제외를 제안한다.
        if (vm.trip.phase == com.hanhyo.pleosshuttle.domain.TripPhase.ALIGHTING) StopExclusionSuggestions(vm)
        if (vm.trip.hasTrip) VehicleTrackingSummary(vm)
    }
}

/**
 * 불러온 운행이 없을 때(앱 첫 실행, 이전 빌드의 모의 운행을 버린 뒤). 운행 조작 버튼은 보이지 않고
 * 1) 기관 연결(차량 연결 코드) → 2) 오늘 운행 불러오기(여럿이면 고르기)로만 안내한다.
 */
@Composable private fun NoTripLoadedBody(vm: ShuttleViewModel) {
    val hint = AppTheme.typography.body2Regular
    Column(Modifier.fillMaxWidth().testTag(NO_TRIP_LOADED_TAG), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        if (!vm.institutionConnected) {
            Text("1. 기관에서 받은 차량 연결 코드로 차량을 연결해 주세요.", style = AppTheme.typography.titleBold.copy(fontWeight = FontWeight.Medium))
            Text("연결한 뒤 오늘 배정된 운행을 불러올 수 있어요.", style = hint, color = AppTheme.colors.textSecondary)
            Action("기관 연결 열기", "no-trip-open-institution", prominent = true) { vm.panel = "institution" }
        } else {
            Text("2. 오늘 ${com.hanhyo.pleosshuttle.domain.TripDirections.label(vm.institutionDirection)} 운행을 불러와 주세요.", style = AppTheme.typography.titleBold.copy(fontWeight = FontWeight.Medium))
            Text("${vm.institutionName} · 방향은 기관 연결에서 바꿀 수 있어요.", style = hint, color = AppTheme.colors.textSecondary)
            Action("오늘 운행 불러오기", "no-trip-load", prominent = true, enabled = !vm.institutionBusy) { vm.loadInstitutionTrip() }
            Action("기관 연결 열기", "no-trip-open-institution", secondary = true) { vm.panel = "institution" }
            if (vm.tripChoices.isNotEmpty()) TripChoiceList(vm)
        }
        Text(vm.institutionStatus, style = hint, color = AppTheme.colors.textSecondary, modifier = Modifier.testTag("no-trip-status"))
    }
}
