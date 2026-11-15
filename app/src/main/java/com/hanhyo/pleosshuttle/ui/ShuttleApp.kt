@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hanhyo.pleosshuttle.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.FlowGuide
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.audio.SpeechStatus
import com.hanhyo.pleosshuttle.designsystem.theme.AppRadius
import com.hanhyo.pleosshuttle.designsystem.theme.AppSize
import com.hanhyo.pleosshuttle.designsystem.theme.AppSpacing
import com.hanhyo.pleosshuttle.designsystem.theme.AppTheme
import com.hanhyo.pleosshuttle.domain.Attendance
import com.hanhyo.pleosshuttle.domain.SurroundingKind
import com.hanhyo.pleosshuttle.domain.TripPhase
import com.hanhyo.pleosshuttle.flowProgressKey
import com.hanhyo.pleosshuttle.nextFlowGuide
import com.hanhyo.pleosshuttle.ui.component.Action
import com.hanhyo.pleosshuttle.ui.component.AppHeader
import com.hanhyo.pleosshuttle.ui.component.ChipTone
import com.hanhyo.pleosshuttle.ui.component.CompactTimeline
import com.hanhyo.pleosshuttle.ui.component.Foldout
import com.hanhyo.pleosshuttle.ui.component.GuideFocus
import com.hanhyo.pleosshuttle.ui.component.LocalCompactLayout
import com.hanhyo.pleosshuttle.ui.component.LocalGuideAction
import com.hanhyo.pleosshuttle.ui.component.LocalGuideEditing
import com.hanhyo.pleosshuttle.ui.component.LocalGuideFocus
import com.hanhyo.pleosshuttle.ui.component.Notice
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.StopTimelinePanel
import com.hanhyo.pleosshuttle.ui.component.VoiceGuidanceBar

/**
 * 앱 화면의 뿌리. 창 폭이 AppSize.compactWidthBreakpoint 이상이면 가로(왼쪽 타임라인 + 본문),
 * 미만이면 세로 분할(한 열, 타임라인은 '다음 · 이후' 줄)로 그린다. 창 크기는 실행 중에도 바뀐다.
 */
@Composable fun ShuttleApp(vm: ShuttleViewModel, requestSpeedPermission: () -> Unit) {
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = AppTheme.colors.background, contentColor = AppTheme.colors.textPrimary) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxWidth < AppSize.compactWidthBreakpoint
                CompositionLocalProvider(LocalCompactLayout provides compact) {
                    ShuttleContent(vm, requestSpeedPermission, compact)
                }
            }
        }
    }
}

@Composable private fun ShuttleContent(vm: ShuttleViewModel, requestSpeedPermission: () -> Unit, compact: Boolean) {
    var boardingReviewed by rememberSaveable(vm.trip.tripId) { mutableStateOf(false) }
    var reason by rememberSaveable(vm.trip.tripId, vm.trip.currentStopIndex) { mutableStateOf("") }
    var editingReason by remember { mutableStateOf(false) }
    val mainScroll = rememberScrollState()
    val requester = remember { BringIntoViewRequester() }
    val uxRestricted = vm.sdkVehicle.uxRestricted.value == true
    val guide = nextFlowGuide(
        trip = vm.trip,
        boardingReviewed = boardingReviewed,
        stationaryReady = vm.engine.stationaryBlockers(vm.now).isEmpty(),
        uxRestricted = uxRestricted,
        surroundingsWarning = vm.surroundings.kind == SurroundingKind.WARNING,
    )
    val contextKey = listOf(vm.panel, vm.trip.tripId, vm.trip.phase, vm.trip.currentStopIndex)
    val progressKey = flowProgressKey(vm.trip, boardingReviewed)
    val currentGuide by rememberUpdatedState(guide)
    var previousContext by remember { mutableStateOf<List<Any?>?>(null) }
    // Reading a different panel or new step starts from its own content. Ordinary observation/audio ticks are not keys.
    LaunchedEffect(contextKey, progressKey, guide.targetTag, editingReason) {
        if (previousContext != contextKey) {
            mainScroll.scrollTo(0)
            previousContext = contextKey
        }
        if (vm.panel == "flow" && currentGuide.targetTag != null && !editingReason) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    CompositionLocalProvider(
        LocalGuideFocus provides if (vm.panel == "flow") GuideFocus(guide.targetTag, requester) else null,
        LocalGuideEditing provides { editingReason = it },
        LocalGuideAction provides { _ -> },
    ) {
        Column(
            Modifier.fillMaxSize().padding(if (compact) AppSpacing.screenCompact else AppSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.md else AppSpacing.lg),
        ) {
            AppHeader(runName = if (vm.trip.hasTrip) "${vm.tripDirectionLabel} 운행" else "운행 없음", compact = compact) {
                if (!compact && vm.trip.hasTrip) {
                    StatusChip(phaseLabel(vm.trip), tone = ChipTone.INFO)
                    StatusChip("차내 기록 ${vm.trip.onboardChildren.size}명")
                    StatusChip("남은 ${vm.trip.remainingStopCount}곳")
                }
                StatusChip(if (vm.institutionConnected) "차량 연결" else "기관 연결 전", tone = ChipTone.ACCENT)
                Action(if (vm.panel == "flow") "명단 · 상세" else if (vm.trip.hasTrip) "${vm.tripDirectionLabel}으로" else "처음 화면으로", if (vm.panel == "flow") "tab-details" else "tab-flow", secondary = true) {
                    vm.panel = if (vm.panel == "flow") "details" else "flow"
                }
            }
            if (vm.panel != "flow") DetailTabs(vm)
            // V-06 차량 벨트 경고등: 모든 단계·화면의 상태 줄 한 줄(주행 중에도 팝업 없이 띠만). 좌석·아이에 붙이지 않는다.
            vm.beltTelltaleBanner?.let { Notice(it, Modifier.testTag("belt-telltale-banner"), error = true) }
            vm.storageError?.let { Notice(it, error = true) }
            vm.actionError?.let { Notice(it, error = true) }
            if (vm.panel == "flow") {
                FlowArea(vm, guide, compact, mainScroll, onBoardingReviewed = { boardingReviewed = true }, modifier = Modifier.weight(1f))
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                        .verticalScroll(mainScroll)
                        .padding(horizontal = if (compact) AppSpacing.xl else AppSpacing.panelHorizontal, vertical = if (compact) AppSpacing.xl else AppSpacing.panelVertical)
                        .testTag("main-scroll"),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
                ) {
                    if (devToolsEnabled && vm.panel in devPanels) Action("‹ 개발 도구", "back-dev", secondary = true) { vm.panel = "dev" }
                    if (vm.panel == "controls") Action("‹ 명단", "back-roster", secondary = true) { vm.panel = "details" }
                    MainPanel(vm, requestSpeedPermission, reason) { reason = it }
                    if (vm.panel == "controls" || (devToolsEnabled && vm.panel == "diagnostics")) Foldout("최근 처리 내용", "필요할 때 펼쳐보기", "last-message") {
                        Text(vm.message, modifier = Modifier.testTag("message"))
                    }
                }
            }
            val speaking = vm.audioState.speechStatus in setOf(SpeechStatus.INITIALIZING, SpeechStatus.SPEAKING)
            VoiceGuidanceBar(
                status = if (vm.audioState.speechStatus == SpeechStatus.FAILED) "음성 안내 실패 · 다시 눌러 주세요" else if (speaking) "음성 안내 중" else "음성 자동 안내",
                speaking = speaking,
                compact = compact,
                onReplay = { vm.replayGuidance() },
                onStopSpeech = { vm.audio.stopSpeech() },
            )
        }
    }
}

/**
 * 기본 운행 화면. 가로는 왼쪽 패널과 본문, 세로 분할은 '다음 · 이후' 줄 아래 본문 한 열.
 * 왼쪽 패널은 보통 정류장 타임라인이고, 경로 확인(L2)에서는 그 화면의 보조 정보로 바뀐다.
 * (순서 바꾸기 L9는 서버 route-revisions가 차량을 허용할 때까지 뺐다.)
 */
@Composable private fun FlowArea(vm: ShuttleViewModel, guide: FlowGuide, compact: Boolean, scroll: ScrollState, onBoardingReviewed: () -> Unit, modifier: Modifier) {
    val stops = timelineStops(vm.trip)
    val routeCheck = vm.trip.phase == TripPhase.PREPARING && guide.targetTag == "start-trip"
    var criteriaOpen by rememberSaveable(vm.trip.tripId) { mutableStateOf(!vm.routeCriteriaSeen) }
    LaunchedEffect(routeCheck) { if (routeCheck) vm.markRouteCriteriaSeen() }
    // 가로 화면의 운행 준비에서는 탑승 명단을 왼쪽 패널([경로 | 명단] 탭)에 따로 스크롤되게 둔다.
    // 행을 눌러 흐름 카드 높이가 바뀌거나 다음 아이로 화면이 이동해도 명단 행 위치는 그대로라 연속으로 눌러도 엉뚱한 행이 눌리지 않는다.
    // 탑승 기록 단계는 [명단]이 기본, 경로 확인(출발 직전)은 [출발 전 확인]이 기본이다.
    val rosterTabs = !compact && vm.trip.phase == TripPhase.PREPARING && vm.trip.children.isNotEmpty()
    var rosterSelected by rememberSaveable(vm.trip.tripId, routeCheck) { mutableStateOf(!routeCheck) }
    // 세로 분할(좁은 화면)도 같은 이유로 운행 준비 중엔 [지금 할 일 | 명단] 탭을 둔다. 명단은 따로 스크롤되어
    // 행을 누를 때 흐름 카드 높이 변화·다음 할 일로의 자동 스크롤(bringIntoView)에 밀리지 않는다.
    // 하원 탑승 기록이 남았으면 [명단]이 기본, 다 기록되면(또는 등원 준비) [지금 할 일]로 돌아온다.
    val compactTabs = compact && vm.trip.phase == TripPhase.PREPARING && vm.trip.children.isNotEmpty()
    val boardingLeft = compactTabs && !vm.trip.isPickup && !vm.trip.noTripToday &&
        vm.trip.children.any { it.status == Attendance.NOT_BOARDED }
    var compactRosterSelected by rememberSaveable(vm.trip.tripId, routeCheck, boardingLeft) { mutableStateOf(boardingLeft && !routeCheck) }
    val boardOnSide = (rosterTabs && rosterSelected) || (compactTabs && compactRosterSelected)
    val side = @Composable { sideModifier: Modifier ->
        if (routeCheck) RouteCheckSide(vm, criteriaOpen, { criteriaOpen = !criteriaOpen }, sideModifier)
    }
    val body = @Composable { bodyModifier: Modifier ->
        Column(
            bodyModifier
                .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                .verticalScroll(scroll)
                .padding(horizontal = if (compact) AppSpacing.xl else AppSpacing.panelHorizontal, vertical = if (compact) AppSpacing.xl else AppSpacing.panelVertical)
                .testTag("main-scroll"),
            verticalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.xl else 0.dp),
        ) {
            if (compact) Text("차내 기록 ${vm.trip.onboardChildren.size}명  ·  남은 ${vm.trip.remainingStopCount}곳  ·  ${phaseLabel(vm.trip)}", style = AppTheme.typography.body3SemiBold, color = AppTheme.colors.textSecondary)
            TeacherFlow(vm, guide, onBoardingReviewed)
            // V-03 탑승 명단 보드: 흐름 카드(지금 할 일) 아래에 모든 아이 행을 늘 보인다.
            if (!boardOnSide && !compactTabs && vm.trip.hasTrip) RiderBoardSection(vm, Modifier.padding(top = if (compact) 0.dp else AppSpacing.xxl))
            // 세로 분할에는 왼쪽 패널이 없으므로 같은 보조 정보를 본문 아래에 둔다.
            if (compact && routeCheck) side(Modifier.fillMaxWidth())
        }
    }
    if (compact) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            if (!routeCheck && vm.trip.hasTrip) CompactTimeline(stops)
            if (compactTabs) SidePaneTabs(routeLabel = "지금 할 일", rosterSelected = boardOnSide) { compactRosterSelected = it }
            if (compactTabs && boardOnSide) Column(
                Modifier.weight(1f).fillMaxWidth()
                    .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.md)
                    .testTag("side-roster"),
            ) { RiderBoardSection(vm, stableRows = true) }
            else body(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xl)) {
            val sideModifier = Modifier.width(AppSize.timelineWidth).fillMaxHeight()
            if (rosterTabs) {
                Column(
                    Modifier.width(if (boardOnSide) AppSize.rosterPaneWidth else AppSize.timelineWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                ) {
                    SidePaneTabs(routeLabel = if (routeCheck) "출발 전 확인" else "경로", rosterSelected = boardOnSide) { rosterSelected = it }
                    val paneModifier = Modifier.weight(1f).fillMaxWidth()
                    when {
                        boardOnSide -> Column(
                            paneModifier
                                .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                                .verticalScroll(rememberScrollState())
                                .padding(AppSpacing.md)
                                .testTag("side-roster"),
                        ) { RiderBoardSection(vm, stableRows = true) }
                        routeCheck -> Column(
                            paneModifier
                                .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sidePanelVertical),
                        ) { side(Modifier.fillMaxWidth()) }
                        else -> StopTimelinePanel("${vm.tripDirectionLabel} 경로 · 정류장 ${vm.trip.routeOrder.size}곳", stops, paneModifier)
                    }
                }
            } else if (routeCheck) {
                Column(
                    sideModifier
                        .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sidePanelVertical),
                ) { side(Modifier.fillMaxWidth()) }
            } else if (vm.trip.hasTrip) {
                StopTimelinePanel("${vm.tripDirectionLabel} 경로 · 정류장 ${vm.trip.routeOrder.size}곳", stops, sideModifier)
            }
            body(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** 가로 운행 준비의 왼쪽 패널 탭. [rosterSelected]면 탑승 명단, 아니면 경로(타임라인 또는 출발 전 확인 보조 정보). */
@Composable private fun SidePaneTabs(routeLabel: String, rosterSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to routeLabel, true to "명단").forEach { (roster, label) ->
            FilterChip(
                selected = rosterSelected == roster,
                onClick = { onSelect(roster) },
                label = { Text(label, style = AppTheme.typography.body2SemiBold) },
                modifier = Modifier.heightIn(min = AppSize.touchMin).testTag(if (roster) "side-tab-roster" else "side-tab-route"),
                shape = RoundedCornerShape(AppRadius.full),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AppTheme.colors.surfaceRaised,
                    labelColor = AppTheme.colors.textSecondary,
                    selectedContainerColor = AppTheme.colors.infoContainer,
                    selectedLabelColor = AppTheme.colors.infoContent,
                ),
            )
        }
    }
}

@Composable private fun DetailTabs(vm: ShuttleViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        // 개발 도구 탭은 디버그 빌드에서만(출시 빌드에는 탭이 없다).
        val tabs = listOf("details" to "명단", "history" to "운행 기록", "institution" to "기관 연결") +
            if (devToolsEnabled) listOf("dev" to "개발 도구") else emptyList()
        tabs.forEach { (id, label) ->
            FilterChip(
                selected = vm.panel == id || (id == "dev" && vm.panel in devPanels) || (id == "details" && vm.panel == "controls"),
                onClick = { vm.panel = id },
                label = { Text(label, style = AppTheme.typography.body2SemiBold) },
                modifier = Modifier.heightIn(min = AppSize.touchMin).testTag("tab-$id"),
                shape = RoundedCornerShape(AppRadius.full),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AppTheme.colors.surfaceRaised,
                    labelColor = AppTheme.colors.textSecondary,
                    selectedContainerColor = AppTheme.colors.infoContainer,
                    selectedLabelColor = AppTheme.colors.infoContent,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(phaseLabel(vm.trip), style = AppTheme.typography.body1SemiBold)
    }
}

@Composable private fun MainPanel(vm: ShuttleViewModel, requestSpeedPermission: () -> Unit, reason: String, onReasonChange: (String) -> Unit) {
    val uxLocked = vm.sdkVehicle.uxRestricted.value == true
    if (uxLocked && (vm.panel != "flow" || vm.trip.phase != TripPhase.DRIVING)) {
        Notice("차량 UX 제한이 활성화되어 상세 조작을 제한합니다. 운행 화면에서 다음 지점과 상태를 확인하세요.")
        return
    }
    when (vm.panel) {
        "details" -> RosterPanel(vm)
        "institution" -> InstitutionPanel(vm)
        "history" -> TripHistory(vm)
        // 개발 도구 화면은 디버그 빌드에서만 연다(출시 빌드에서 panel 값이 남아 있어도 열리지 않는다).
        "dev" -> if (devToolsEnabled) DevTools(vm) else RosterPanel(vm)
        "observations" -> if (devToolsEnabled) ObservationPanel(vm, requestSpeedPermission) else RosterPanel(vm)
        "diagnostics" -> if (devToolsEnabled) DiagnosticsPanel(vm) else RosterPanel(vm)
        else -> if (!vm.trip.hasTrip) RosterPanel(vm) else when (vm.trip.phase) {
            TripPhase.PREPARING -> Preparation(vm)
            TripPhase.DRIVING -> Driving(vm)
            TripPhase.ALIGHTING -> Alighting(vm, reason, onReasonChange)
            TripPhase.END_REVIEW -> EndReview(vm)
            TripPhase.ENDED -> Ended(vm)
        }
    }
}
