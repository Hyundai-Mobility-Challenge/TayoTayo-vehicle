package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.hanhyo.pleosshuttle.FlowGuide
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.sdk.SdkOutcome
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.LocalCompactLayout

/** Teacher actions are explicit; destination and guidance advance after the last child record. */
@Composable internal fun TeacherFlow(vm: ShuttleViewModel, guide: FlowGuide, onBoardingReviewed: () -> Unit, onOpenReorder: () -> Unit) {
    val tag = guide.targetTag
    val child = vm.trip.children.firstOrNull { tag in listOf("board-${it.id}", "handover-${it.id}", "alight-${it.id}", "end-handover-${it.id}", "correct-${it.id}") }
    val stationary = vm.engine.stationaryBlockers(vm.now).isEmpty()
    val uxLocked = vm.trip.integrationMode == IntegrationMode.SDK && vm.sdkVehicle.uxRestricted.value == true
    val type = AppTheme.typography
    val question = type.titleBold.copy(fontWeight = FontWeight.Medium)
    val hint = type.body2Regular
    val warning = AppTheme.colors.primaryContent
    // 정차 중 인계 화면에서도 남은 지점이 둘 이상이면 순서를 바꿀 수 있다(바뀌는 것은 다음 정류장부터).
    val remainingReorder = @Composable {
        if (vm.trip.routeOrder.size - (vm.trip.currentStopIndex + 1) >= 2) {
            Action("남은 순서 바꾸기", "open-reorder", secondary = true, enabled = vm.engine.manualRouteBlockers(vm.now).isEmpty(), action = onOpenReorder)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("flow-guide"), verticalArrangement = Arrangement.spacedBy(AppSpacing.xl)) {
        Text("동승 선생님 · ${flowTitle(vm.trip)}", style = type.body1SemiBold, color = AppTheme.colors.infoContent)
        val title = when {
            child != null -> child.name.removePrefix("가상 ")
            tag == "start-trip" -> "하원 준비가 끝났어요"
            tag == "arrive-stop" -> "도착하면 확인해 주세요"
            tag == "arrive-origin" -> "출발지로 돌아가요"
            tag == "complete-stop" -> "이 지점의 기록을 마쳤어요"
            tag == "confirm-finish" -> "차량 실내를 확인해 주세요"
            tag == "view-history" -> "오늘의 하원을 마쳤어요"
            tag == "surroundings-status" -> "주변 위험 경고가 있어요"
            tag == "door-result" -> "별도 문 시험 응답 대기 중"
            else -> "정차 후 확인해 주세요"
        }
        Text(title, style = if (LocalCompactLayout.current) type.head3Bold else type.head2Bold, modifier = Modifier.testTag("guide-next-action"))
        when {
            tag?.startsWith("board-") == true && child != null -> {
                Text("차에 탔나요?", style = question)
                Action("탑승 기록", tag, prominent = true) { vm.perform { board(child.id, it) } }
                if (vm.trip.onboardChildren.isNotEmpty()) Action("탑승 마침 · ${vm.trip.onboardChildren.size}명", "review-boarding", secondary = true, action = onBoardingReviewed)
            }
            (tag?.startsWith("handover-") == true || tag?.startsWith("end-handover-") == true) && child != null -> {
                Text("보호자에게 직접 인계했나요?", style = question)
                Action("인계 확인", tag, prominent = true) { vm.perform { confirmHandover(child.id, it) } }
                if (tag?.startsWith("handover-") == true) remainingReorder()
            }
            tag?.startsWith("alight-") == true && child != null -> {
                Text("인계 확인 완료 · 차에서 내렸나요?", style = question)
                Action("하차 기록", tag, prominent = true) { vm.perform { recordAlightingAndContinue(child.id, it) } }
                remainingReorder()
            }
            tag?.startsWith("correct-") == true && child != null -> {
                Text("실제 상태와 다른 기록을 정정해 주세요.", style = question)
                Correction(child, vm, allowAlight = true)
            }
            tag == "start-trip" -> {
                Text("${vm.trip.onboardChildren.size}명 탑승 · ${vm.trip.children.filter { it.status == Attendance.ONBOARD }.map { it.stopId }.distinct().size}곳 방문", style = question)
                RouteCheckBody(vm, onOpenReorder)
            }
            tag in setOf("arrive-stop", "arrive-origin") -> {
                Text(if (vm.trip.currentStop == null) "복귀 후 차량 실내를 확인해요." else "내릴 어린이: " + vm.trip.currentStopChildren.filter { it.status == Attendance.ONBOARD }.joinToString { it.name.removePrefix("가상 ") }.ifBlank { "없음" }, style = question)
                Text(if (vm.trip.integrationMode == IntegrationMode.SIMULATION) "도착을 가정하고 정차를 확인해 주세요." else "운전자에게 도착과 정차를 확인해 주세요.", style = hint, color = AppTheme.colors.textSecondary)
                if (!stationary || uxLocked) Text(if (uxLocked) "차량 조작 제한 중" else vm.engine.stationaryBlockers(vm.now).first(), color = warning, style = type.body2SemiBold)
                if (vm.trip.integrationMode == IntegrationMode.SDK && vm.navi.outcome in setOf(SdkOutcome.ERROR, SdkOutcome.TIMEOUT, SdkOutcome.PERMISSION_REQUIRED)) Text("길 안내 연결 실패 · 상세에서 확인해 주세요.", color = warning, style = type.body2SemiBold)
                Action(if (tag == "arrive-origin") "복귀·정차 확인" else "도착·정차 확인", tag!!, prominent = true, enabled = stationary && !uxLocked) {
                    vm.perform { if (tag == "arrive-origin") beginEndReview(it) else chooseAlighting(it) }
                }
            }
            tag == "complete-stop" -> Action("다음 지점으로", tag, prominent = true) { vm.perform { completeCurrentStop(it) } }
            tag == "confirm-finish" -> {
                val occupied = vm.vehicle.seats.count { it.occupancy.isFresh(vm.now) && it.occupancy.value == true }
                Text("남아 있는 어린이가 없는지 직접 살펴보세요.", style = question)
                Text(if (occupied > 0) "좌석 점유 감지 ${occupied}곳 · 직접 확인 필요" else "센서가 확인하지 못하는 좌석이 있을 수 있어요.", color = warning, style = type.body2SemiBold)
                Action("실내 확인 완료 · 운행 종료", tag, prominent = true) { vm.perform { inspectAndFinish(it) } }
            }
            tag == "view-history" -> {
                Text("하차 기록과 실내 확인을 저장했어요.", style = question)
                Action("완료 기록 보기", tag, prominent = true) { vm.panel = "history" }
                Action("기록 보관 후 새 모의 운행", "new-trip", secondary = true) { vm.startNewSimulationTrip() }
            }
            tag == "surroundings-status" -> Text("운전자가 주변 위험을 확인해야 해요.", style = question, modifier = Modifier.testTag(tag))
            tag == "door-result" -> Text("시험 결과는 문 시험 화면에서 확인할 수 있어요.", style = question, modifier = Modifier.testTag(tag))
            tag == "stationary-status" -> Text(vm.engine.stationaryBlockers(vm.now).firstOrNull() ?: "차량 상태 확인 중", style = question, modifier = Modifier.testTag(tag))
            else -> Text("지금은 조작할 수 없어요.", style = question)
        }
        VehicleTrackingSummary(vm)
    }
}
