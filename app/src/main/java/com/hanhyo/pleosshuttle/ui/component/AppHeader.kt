package com.hanhyo.pleosshuttle.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.designsystem.theme.canvasDp

/** 화면 맨 위 줄. 로고와 운행 이름, 오른쪽에 상태 칩과 화면 전환 버튼을 둔다. */
@Composable internal fun AppHeader(
    runName: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = if (compact) AppSize.headerHeightCompact else AppSize.headerHeight),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) canvasDp(44) else AppSize.logo).background(AppTheme.colors.primary, RoundedCornerShape(AppRadius.card)),
            contentAlignment = Alignment.Center,
        ) { Text("통", style = AppTheme.typography.titleBold, color = AppTheme.colors.onPrimary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(canvasDp(2))) {
            Text("통학메이트", style = AppTheme.typography.caption1Regular.copy(fontWeight = FontWeight.Medium), color = AppTheme.colors.textSecondary)
            Text(runName, style = AppTheme.typography.head4Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(canvasDp(10)), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}
