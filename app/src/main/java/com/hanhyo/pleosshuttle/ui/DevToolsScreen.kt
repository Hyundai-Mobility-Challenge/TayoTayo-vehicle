package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.BuildConfig
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.ui.component.Foldout
import com.hanhyo.pleosshuttle.ui.component.Notice

/** 개발 도구 탭의 하위 화면 id. [devToolsEnabled]가 false(출시 빌드)면 이 화면들은 열리지 않는다. */
internal val devPanels = setOf("observations", "diagnostics")

/** 개발 도구(위치 전송 · Virtual Console 운전 · 차량 관측 · 진단)는 디버그 빌드에서만 보인다. 출시 빌드에서는 탭·진입 버튼이 없다. */
internal val devToolsEnabled: Boolean get() = BuildConfig.DEBUG

@Composable internal fun DevTools(vm: ShuttleViewModel) {
    if (!devToolsEnabled) return
    Text("개발 도구", style = AppTheme.typography.head4Bold)
    Notice("디버그 빌드 전용 화면이에요. 출시 빌드에는 보이지 않아요.")
    Foldout("위치 전송", "평상시에는 출발과 함께 자동 전송 · 여기서는 직접 켜고 끄기", "tracking-lab") {
        VehicleSharingControls(vm.trackingControls, vm.trackingEnabled, vm.institutionBusy, vm.trackingStatus,
            onEnabled = vm::changeTrackingEnabled, directionLabel = vm.tripDirectionLabel)
        ConsoleDriveControls(vm)
    }
    val items = listOf(
        Triple("observations", "차량 관측", "속도·기어·센서 상태와 연결 확인"),
        Triple("diagnostics", "진단", "오류·변경 이력을 확인하고 파일 저장"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)) {
        items.forEach { (id, title, description) ->
            Card(
                onClick = { vm.panel = id },
                modifier = Modifier.weight(1f).testTag("tab-$id"),
                shape = RoundedCornerShape(AppRadius.card),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceRaised, contentColor = AppTheme.colors.textPrimary),
            ) {
                Column(Modifier.padding(AppSpacing.xxl), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(title, style = AppTheme.typography.titleBold)
                    Text(description, style = AppTheme.typography.body2Regular, color = AppTheme.colors.textSecondary)
                }
            }
        }
    }
}
