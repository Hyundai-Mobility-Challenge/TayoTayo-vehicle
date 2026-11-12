package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.PickupFlow
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.GuideReasonField
import com.hanhyo.pleosshuttle.ui.component.Notice
import com.hanhyo.pleosshuttle.ui.component.Section
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.BlockReasons

// 명단 · 상세의 '명단·방문 순서 조작'에서 여는 단계별 직접 조작. 기본 운행 화면(TeacherFlow)과 같은 엔진 동작을 쓴다.
// 등원(V-25) 운행에서는 엔진이 거절할 하원 전용 버튼(출발 전 탑승, 정류장 하차·보호자 인계, 정정)을 보이지 않고 등원 동작으로 바꾼다.

@Composable internal fun Preparation(vm: ShuttleViewModel) {
    Section("탑승 기록 수정", "변경 전 기록과 정정 사유는 보관됩니다") {
        StationaryStatus(vm)
        vm.trip.children.forEach { child ->
            ChildCard(child, vm) {
                if (vm.trip.isPickup) {
                    // VS-17: 출발 전 탑승 없음. 미탑승 예정 아이만 미탑승 확인, 확인한 아이는 취소(결정 31).
                    if (PickupFlow.canConfirmBeforeDeparture(vm.trip, child) || PickupFlow.canUndoBeforeDeparture(vm.trip, child)) NotBoardedActions(child, vm)
                } else {
                    Action("탑승 기록", "board-${child.id}", enabled = child.status == Attendance.NOT_BOARDED && !vm.trip.noTripToday && vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { board(child.id, it) } }
                    NotBoardedActions(child, vm)
                    Correction(child, vm)
                }
            }
        }
    }
    Section("방문 순서 조정", "직선거리 기준 · 운행 시작 전 변경 가능") {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Action("방문 순서 계산", "calculate-route") { vm.perform { calculateRoute(it) } }
            StatusChip(if (vm.trip.routeIsExample) "현재: 고정 예제 순서" else "현재: 검토 후 적용한 순서", tone = ChipTone.INFO)
        }
        vm.trip.routeProposal?.let { proposal ->
            Notice("미리보기  ${proposal.stopOrder.joinToString(" → ") { id -> vm.trip.stops.first { it.id == id }.shortName }} → 서울시청\n합성 비용 ${"%.1f".format(proposal.cost)} ${proposal.costUnit} · ${proposal.evaluatedOrders}개 순서 비교")
            Action("이 순서 적용", "apply-route") { vm.perform { applyRouteProposal(it) } }
        }
        vm.trip.routeOrder.forEachIndexed { index, id ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text("${index + 1}. ${vm.trip.stops.first { it.id == id }.name}", Modifier.weight(1f))
                Action("위로", "route-up-$id", enabled = index > 0, secondary = true) {
                    val order = vm.trip.routeOrder.toMutableList(); val prev = order[index - 1]; order[index - 1] = id; order[index] = prev
                    vm.perform { reorderStops(order, it) }
                }
            }
        }
        // 등원은 정류장에서 태우므로 탑승 기록 없이 출발한다(탈 아이 0명이면 엔진이 막음, D34).
        Action("운행 화면으로", "start-driving", enabled = (vm.trip.onboardChildren.isNotEmpty() || (vm.trip.isPickup && !vm.trip.noTripToday)) && vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { startDriving(it) } }
    }
}

@Composable internal fun Driving(vm: ShuttleViewModel) {
    Section("03  운행", "운전자 · 다음 지점 안내") {
        Text((vm.trip.currentStop ?: vm.trip.origin).name, style = AppTheme.typography.titleBold)
        Text("좌표 ${(vm.trip.currentStop ?: vm.trip.origin).latitude}, ${(vm.trip.currentStop ?: vm.trip.origin).longitude}", style = AppTheme.typography.body3Regular)
        Text(when {
            vm.trip.isPickup && vm.trip.currentStop == null -> "정류장을 모두 들렀습니다. 기관에 도착하면 기관 도착을 확인하세요."
            vm.trip.isPickup -> "탈 어린이: ${com.hanhyo.pleosshuttle.pickupBoardingChildren(vm.trip, vm.trip.currentStop).joinToString { it.name }.ifBlank { "없음" }}"
            vm.trip.currentStop == null -> "하차 지점을 모두 방문했습니다. 출발지로 복귀한 뒤 직접 실내 확인을 진행하세요."
            else -> "내릴 어린이: ${vm.trip.currentStopChildren.filter { it.awaitingAlighting }.joinToString { it.name }.ifBlank { "미하차 기록 없음" }}"
        })
        NavigationStatus(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Action("다음 지점 안내", "navigate-next") { vm.navigateNext() }
            if (vm.trip.integrationMode == IntegrationMode.SDK) Action("순정 내비 보기", "show-native", secondary = true) { vm.showNativeNavigation() }
        }
        Notice(if (vm.trip.isPickup) "잠깐 멈춰도 정류장 단계가 되지 않습니다. 정류장에서 정차를 확인한 뒤 직접 선택합니다."
            else "잠깐 멈춰도 하차 모드가 되지 않습니다. 하차 지점에서 정차를 확인한 뒤 운전자가 직접 선택합니다.")
        StationaryStatus(vm)
        if (vm.trip.isPickup) {
            // V-27 정류장 도착·정차 확인 / V-29 기관 도착 확인(남은 정류장이 없을 때만, 엔진 조건과 같음).
            if (vm.trip.currentStop != null) Action("이 정류장 도착·정차 확인", "choose-alighting", enabled = vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { arriveAtPickupStop(it) } }
            else Action("기관 도착 확인", "begin-end", enabled = vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { arriveAtInstitution(it) } }
        } else {
            if (vm.trip.currentStop != null) Action("운전자 · 이 지점 하차 선택", "choose-alighting", enabled = vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { chooseAlighting(it) } }
            Action("복귀 후 운행 종료 확인", "begin-end", secondary = true, enabled = vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { beginEndReview(it) } }
        }
    }
}

@Composable internal fun Alighting(vm: ShuttleViewModel, reason: String, onReasonChange: (String) -> Unit) {
    if (vm.trip.isPickup) Section("정류장 인계받음·탑승", "기록은 운행 화면의 정류장 행에서 해요") {
        vm.trip.currentStopChildren.forEach { child ->
            ChildCard(child, vm) { StatusChip(PickupFlow.statusLabel(vm.trip, child)) }
        }
        Text("인계받음·탑승·보호자 도착 알림·미탑승 확인은 ‘등원으로’ 운행 화면에서 기록합니다.")
        PickupResume(vm, reason, onReasonChange)
    } else Section("보호자 인계와 하차 기록", "선생님 · 보호자와 대면 인계 후 직접 기록") {
        vm.trip.currentStopChildren.forEach { child ->
            ChildCard(child, vm) {
                Action("보호자 대면 인계 확인", "handover-${child.id}", enabled = child.status == Attendance.ONBOARD && child.id !in vm.trip.handoverChildIds) { vm.perform { confirmHandover(child.id, it) } }
                Action("하차 기록", "alight-${child.id}", enabled = child.status == Attendance.ONBOARD && child.id in vm.trip.handoverChildIds) { vm.perform { alight(child.id, it) } }
                Correction(child, vm)
            }
        }
        BlockReasons(vm.engine.resumeWarnings(vm.now), "현재 지점 기록 확인 완료")
        Text("미완료·미확인이 남으면 사유를 기록하고 운전자가 복귀를 확인합니다. 실제 차량 출발은 앱이 막지 않습니다.")
        GuideReasonField(reason, onReasonChange, "미완료·미확인 항목을 남기는 사유", "resume-reason")
        Action("운전자 확인 · 운행 화면 복귀", "resume-driving") { vm.perform { resumeDriving(reason, it) } }
    }
}

/** 등원 정류장의 운행 복귀(처리 안 된 아이를 남기고 떠남, V-28). 하원과 같은 엔진 resumeDriving·경고를 쓴다. */
@Composable private fun PickupResume(vm: ShuttleViewModel, reason: String, onReasonChange: (String) -> Unit) {
    BlockReasons(vm.engine.resumeWarnings(vm.now), "현재 정류장 기록 확인 완료")
    Text("처리 안 된 어린이를 남기고 떠나면 다음 정차 화면에서 미탑승 확인해요. 사유를 기록하고 운전자가 복귀를 확인합니다.")
    GuideReasonField(reason, onReasonChange, "미완료·미확인 항목을 남기는 사유", "resume-reason")
    Action("운전자 확인 · 운행 화면 복귀", "resume-driving") { vm.perform { resumeDriving(reason, it) } }
}

@Composable internal fun EndReview(vm: ShuttleViewModel) {
    Section("06  운행 종료 전 직접 확인", "명단 · 센서 · 미확인 정보 · 직접 확인을 따로 검토합니다") {
        StationaryStatus(vm)
        Text(if (vm.trip.isPickup) "기관 인계 전 ${vm.trip.onboardChildren.size}명" else "기록상 미하차 ${vm.trip.onboardChildren.size}명", style = AppTheme.typography.titleBold)
        if (vm.trip.isPickup) {
            // V-29: 탄 아이마다 기관 담당자 인계(하차 기록). 처리 안 된 아이 미탑승 확인은 운행 화면(시트)에서 한다.
            val unresolved = PickupFlow.unresolvedAtInstitution(vm.trip)
            if (unresolved.isNotEmpty()) Notice("처리 안 된 어린이 ${unresolved.size}명 · ‘등원으로’ 운행 화면에서 미탑승 확인해 주세요.")
            vm.trip.onboardChildren.forEach { child ->
                ChildCard(child, vm) {
                    Action("기관 인계 확인 · 하차 기록", "institution-handover-${child.id}", enabled = vm.engine.stationaryBlockers(vm.now).isEmpty()) { vm.perform { handoverToInstitution(child.id, it) } }
                }
            }
        } else vm.trip.onboardChildren.forEach { child ->
            ChildCard(child, vm) {
                Action("보호자 대면 인계 확인", "end-handover-${child.id}") { vm.perform { confirmHandover(child.id, it) } }
                Correction(child, vm, allowAlight = true)
            }
        }
        Notice("남아 있는 어린이가 없는지 직접 살펴보세요. 앱은 잔류 아동 없음이나 안전을 자동 확정하지 않습니다.")
        Action("선생님 · 실내를 직접 확인했음", "confirm-cabin") { vm.perform { confirmCabinInspection(it) } }
        Text("직접 확인: ${timeText(vm.trip.directCabinCheckAtMillis)}")
        Action("종료 기록 저장", "finish-trip", enabled = vm.trip.onboardChildren.isEmpty() && vm.trip.directCabinCheckAtMillis != null &&
            !(vm.trip.isPickup && vm.trip.undecidedChildren.isNotEmpty())) { vm.perform { finishTrip(it) } }
    }
}

@Composable internal fun Ended(vm: ShuttleViewModel) {
    Section("운행 종료 기록을 저장했습니다", "직접 확인과 관측 한계를 함께 보존했습니다") {
        val end = vm.trip.endRecord
        Text("종료 ${timeText(end?.recordedAtMillis)} · 직접 실내 확인 ${timeText(end?.manualInspectionAtMillis)}")
        Notice(end?.statement ?: "종료 기록 미확인")
        Action("진단·이력 보기", "view-history") { vm.panel = "diagnostics" }
        Action("완료 기록 보관 후 새 모의 운행", "new-trip", secondary = true) { vm.startNewSimulationTrip() }
    }
}
