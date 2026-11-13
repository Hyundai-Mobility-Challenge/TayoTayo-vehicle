package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme

/** 회색 본문 패널 위의 흰 카드. 제목과 보조 문구 아래에 내용을 쌓는다. */
@Composable internal fun Section(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppTheme.colors.surfaceRaised, contentColor = AppTheme.colors.textPrimary, shape = RoundedCornerShape(AppRadius.card), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.xxl), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Text(title, style = AppTheme.typography.titleBold)
            Text(subtitle, style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
            content()
        }
    }
}

/** 기본은 접혀 있고 필요할 때 펼치는 카드. 펼침 상태는 [tag]별로 저장한다. */
@Composable internal fun Foldout(title: String, summary: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    Surface(color = AppTheme.colors.surfaceRaised, contentColor = AppTheme.colors.textPrimary, shape = RoundedCornerShape(AppRadius.card), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.xl), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(title, style = AppTheme.typography.body1SemiBold)
                    if (summary.isNotBlank()) Text(summary, style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
                }
                Action(if (expanded) "접기" else "펼치기", "expand-$tag", secondary = true) { expanded = !expanded }
            }
            if (expanded) Column(Modifier.testTag("content-$tag"), verticalArrangement = Arrangement.spacedBy(AppSpacing.lg), content = content)
        }
    }
}

/** 아이 한 명의 기록 행. 이름·상태와 보조 정보 아래에 그 아이에게만 해당하는 버튼을 둔다. */
@Composable internal fun ChildCard(title: String, detail: String, badge: String? = null, actions: @Composable RowScope.() -> Unit) {
    Surface(color = AppTheme.colors.surface, contentColor = AppTheme.colors.textPrimary, shape = RoundedCornerShape(AppRadius.row)) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Text(title, style = AppTheme.typography.body1SemiBold)
            badge?.let { StatusChip(it, tone = ChipTone.ACCENT) }
            Text(detail, style = AppTheme.typography.body3Regular, color = AppTheme.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 진행을 막는 이유 목록. 없으면 [emptyText]를 완료 색으로 보여준다. */
@Composable internal fun BlockReasons(reasons: List<String>, emptyText: String? = null) {
    if (reasons.isNotEmpty()) Text(reasons.joinToString("\n") { "• $it" }, color = AppTheme.colors.primaryContent, style = AppTheme.typography.body2SemiBold)
    else emptyText?.let { Text(it, color = AppTheme.colors.infoContent, style = AppTheme.typography.body2SemiBold) }
}
