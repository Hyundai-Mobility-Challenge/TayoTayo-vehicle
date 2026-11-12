package com.hanhyo.pleosshuttle.designsystem.theme.typography

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hanhyo.pleosshuttle.R

/**
 * IBM Plex Sans KR 1.1.0 hinted TTF (SIL Open Font License 1.1, 라이선스는 assets/licenses/).
 * 차량에 Google Play 서비스가 없을 수 있어 다운로드 글꼴 대신 앱에 포함한다. 출처는 docs/design/TOKENS.md '글자'.
 */
val AppFont = FontFamily(
    Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_kr_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_kr_bold, FontWeight.Bold),
)
