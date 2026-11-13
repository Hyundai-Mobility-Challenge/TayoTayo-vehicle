@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hanhyo.pleosshuttle.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.hanhyo.pleosshuttle.domain.IntegrationMode
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
import com.hanhyo.pleosshuttle.ui.component.MusicBar
import com.hanhyo.pleosshuttle.ui.component.Notice
import com.hanhyo.pleosshuttle.ui.component.StatusChip
import com.hanhyo.pleosshuttle.ui.component.StopTimelinePanel

private val toolPanels = setOf("door-lab", "observations", "trials", "diagnostics")

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
    val uxRestricted = vm.trip.integrationMode == IntegrationMode.SDK && vm.sdkVehicle.uxRestricted.value == true
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
        LocalGuideAction provides { tag ->
            if (tag == "calculate-route") boardingReviewed = true
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(if (compact) AppSpacing.screenCompact else AppSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.md else AppSpacing.lg),
        ) {
            AppHeader(runName = "${vm.tripDirectionLabel} 한 바퀴", compact = compact) {
                if (!compact) {
                    StatusChip(phaseLabel(vm.trip), tone = ChipTone.INFO)
                    StatusChip("차내 기록 ${vm.trip.onboardChildren.size}명")
                    StatusChip("남은 ${vm.trip.remainingStopCount}곳")
                }
                StatusChip(if (vm.trip.integrationMode == IntegrationMode.SIMULATION) "모의 체험" else "차량 연결", tone = ChipTone.ACCENT)
                Action(if (vm.panel == "flow") "명단 · 상세" else "${vm.tripDirectionLabel}으로", if (vm.panel == "flow") "tab-details" else "tab-flow", secondary = true) {
                    vm.panel = if (vm.panel == "flow") "details" else "flow"
                }
            }
            if (vm.panel != "flow") DetailTabs(vm)
            // V-06 차량 벨트 경고등: 모든 단계·화면의 상태 줄 한 줄(주행 중에도 팝업 없이 띠만). 좌석·아이에 붙이지 않는다.
            vm.beltTelltaleBanner?.let { Notice(it, Modifier.testTag("belt-telltale-banner"), error = true) }
            // 정차 후 운행 화면이 전체로 돌아왔을 때만. 분할은 Pleos에서 사람이 손잡이로만 만들 수 있어 안내만 한다. 이미 분할(좁은 창)이면 내린다.
            LaunchedEffect(compact, vm.splitHint) { if (compact && vm.splitHint) vm.dismissSplitHint() }
            if (vm.splitHint && !compact) Row(
                Modifier.fillMaxWidth().testTag("split-hint"),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md), verticalAlignment = Alignment.CenterVertically,
            ) {
                Notice("정차했어요. 내비와 나눠 보려면 화면 오른쪽 끝 손잡이(⋮)를 왼쪽으로 끌어 주세요. 나눈 채로 출발하면 이 화면은 조회 전용으로 남아요.", Modifier.weight(1f))
                Action("닫기", "split-hint-close", secondary = true) { vm.dismissSplitHint() }
            }
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
                    if (vm.panel in toolPanels) Action("‹ 테스트 도구", "back-tools", secondary = true) { vm.panel = "tools" }
                    if (vm.panel == "controls") Action("‹ 명단", "back-roster", secondary = true) { vm.panel = "details" }
                    MainPanel(vm, requestSpeedPermission, reason) { reason = it }
                    if (vm.panel in setOf("controls", "door-lab", "diagnostics")) Foldout("최근 처리 내용", "필요할 때 펼쳐보기", "last-message") {
                        Text(vm.message, modifier = Modifier.testTag("message"))
                    }
                }
            }
            val speaking = vm.audioState.speechStatus in setOf(SpeechStatus.INITIALIZING, SpeechStatus.SPEAKING)
            MusicBar(
                status = if (vm.audioState.speechStatus == SpeechStatus.FAILED) "음성 안내 실패 · 다시 눌러 주세요" else if (speaking) "음성 안내 중" else if (vm.audioState.isPlaying) "시험 멜로디 재생 중" else "음성 자동 안내 · 시험 멜로디",
                playing = vm.audioState.isPlaying,
                speaking = speaking,
                compact = compact,
                onPlay = { vm.audio.play() },
                onPause = { vm.audio.pause() },
                onStop = { vm.audio.stop() },
                onReplay = { vm.replayGuidance() },
                onStopSpeech = { vm.audio.stopSpeech() },
            )
            Text("동승 선생님용 · 가상 명단 체험", style = AppTheme.typography.caption2Regular, color = AppTheme.colors.textTertiary)
        }
    }
}

private val reorderDraftSaver = listSaver<ReorderDraft?, Any?>(
    save = { draft -> if (draft == null) emptyList() else listOf(ArrayList(draft.base), ArrayList(draft.order), draft.lastMoved, draft.reason) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        if (saved.isEmpty()) null else ReorderDraft(saved[0] as List<String>, saved[1] as List<String>, saved[2] as String?, saved[3] as String?)
    },
)

/**
 * 기본 운행 화면. 가로는 왼쪽 패널과 본문, 세로 분할은 '다음 · 이후' 줄 아래 본문 한 열.
 * 왼쪽 패널은 보통 정류장 타임라인이고, 경로 확인(L2)·순서 바꾸기(L9)에서는 그 화면의 보조 정보로 바뀐다.
 */
@Composable private fun FlowArea(vm: ShuttleViewModel, guide: FlowGuide, compact: Boolean, scroll: ScrollState, onBoardingReviewed: () -> Unit, modifier: Modifier) {
    val stops = timelineStops(vm.trip)
    // 운행 단계나 정류장이 바뀌면 초안을 버린다(정차가 끝나면 순서 바꾸기도 닫힘).
    var draft by rememberSaveable(vm.trip.tripId, vm.trip.phase, vm.trip.currentStopIndex, stateSaver = reorderDraftSaver) { mutableStateOf<ReorderDraft?>(null) }
    val reordering = draft != null && (vm.trip.phase == TripPhase.PREPARING || vm.trip.phase == TripPhase.ALIGHTING)
    val routeCheck = !reordering && vm.trip.phase == TripPhase.PREPARING && guide.targetTag == "start-trip"
    var criteriaOpen by rememberSaveable(vm.trip.tripId) { mutableStateOf(!vm.routeCriteriaSeen) }
    LaunchedEffect(routeCheck) { if (routeCheck) vm.markRouteCriteriaSeen() }
    LaunchedEffect(reordering) { scroll.scrollTo(0) }
    val openReorder = { val order = displayedRouteOrder(vm.trip); draft = ReorderDraft(base = order, order = order) }
    // 가로 화면의 운행 준비에서는 탑승 명단을 왼쪽 패널([경로 | 명단] 탭)에 따로 스크롤되게 둔다.
    // 행을 눌러 흐름 카드 높이가 바뀌거나 다음 아이로 화면이 이동해도 명단 행 위치는 그대로라 연속으로 눌러도 엉뚱한 행이 눌리지 않는다.
    // 탑승 기록 단계는 [명단]이 기본, 경로 확인(출발 직전)은 [출발 전 확인]이 기본이다.
    val rosterTabs = !compact && !reordering && vm.trip.phase == TripPhase.PREPARING && vm.trip.children.isNotEmpty()
    var rosterSelected by rememberSaveable(vm.trip.tripId, routeCheck) { mutableStateOf(!routeCheck) }
    // 세로 분할(좁은 화면)도 같은 이유로 운행 준비 중엔 [지금 할 일 | 명단] 탭을 둔다. 명단은 따로 스크롤되어
    // 행을 누를 때 흐름 카드 높이 변화·다음 할 일로의 자동 스크롤(bringIntoView)에 밀리지 않는다.
    // 하원 탑승 기록이 남았으면 [명단]이 기본, 다 기록되면(또는 등원 준비) [지금 할 일]로 돌아온다.
    val compactTabs = compact && !reordering && vm.trip.phase == TripPhase.PREPARING && vm.trip.children.isNotEmpty()
    val boardingLeft = compactTabs && !vm.trip.isPickup && !vm.trip.noTripToday &&
        vm.trip.children.any { it.status == Attendance.NOT_BOARDED }
    var compactRosterSelected by rememberSaveable(vm.trip.tripId, routeCheck, boardingLeft) { mutableStateOf(boardingLeft && !routeCheck) }
    val boardOnSide = (rosterTabs && rosterSelected) || (compactTabs && compactRosterSelected)
    val side = @Composable { sideModifier: Modifier ->
        when {
            reordering -> ReorderSide(vm, draft!!, { draft = it }, sideModifier)
            routeCheck -> RouteCheckSide(vm, criteriaOpen, { criteriaOpen = !criteriaOpen }, sideModifier)
        }
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
            if (reordering) ReorderBody(vm, draft!!, { draft = it }, onClose = { draft = null })
            else TeacherFlow(vm, guide, onBoardingReviewed, onOpenReorder = openReorder)
            // V-03 탑승 명단 보드: 흐름 카드(지금 할 일) 아래에 모든 아이 행을 늘 보인다. 순서 바꾸기 중에는 그 작업만 보인다.
            if (!reordering && !boardOnSide && !compactTabs) RiderBoardSection(vm, Modifier.padding(top = if (compact) 0.dp else AppSpacing.xxl))
            // 세로 분할에는 왼쪽 패널이 없으므로 같은 보조 정보를 본문 아래에 둔다.
            if (compact && (reordering || routeCheck)) side(Modifier.fillMaxWidth())
        }
    }
    if (compact) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            if (!reordering && !routeCheck) CompactTimeline(stops)
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
            } else if (reordering || routeCheck) {
                Column(
                    sideModifier
                        .background(AppTheme.colors.surface, RoundedCornerShape(AppRadius.panel))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.sidePanelVertical),
                ) { side(Modifier.fillMaxWidth()) }
            } else {
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
        listOf("details" to "명단", "history" to "운행 기록", "institution" to "기관 연결", "tools" to "테스트 도구").forEach { (id, label) ->
            FilterChip(
                selected = vm.panel == id || (id == "tools" && vm.panel in toolPanels) || (id == "details" && vm.panel == "controls"),
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
    val uxLocked = vm.trip.integrationMode == IntegrationMode.SDK && vm.sdkVehicle.uxRestricted.value == true
    if (uxLocked && (vm.panel != "flow" || vm.trip.phase != TripPhase.DRIVING)) {
        Notice("차량 UX 제한이 활성화되어 상세 조작을 제한합니다. 운행 화면에서 다음 지점과 상태를 확인하세요.")
        return
    }
    when (vm.panel) {
        "details" -> RosterPanel(vm)
        "institution" -> InstitutionPanel(vm)
        "tools" -> TestTools(vm)
        "door-lab" -> DoorLab(vm, reason, onReasonChange)
        "history" -> TripHistory(vm)
        "observations" -> ObservationPanel(vm, requestSpeedPermission)
        "trials" -> TrialsPanel(vm)
        "diagnostics" -> DiagnosticsPanel(vm)
        else -> when (vm.trip.phase) {
            TripPhase.PREPARING -> Preparation(vm)
            TripPhase.DRIVING -> Driving(vm)
            TripPhase.ALIGHTING -> Alighting(vm, reason, onReasonChange)
            TripPhase.END_REVIEW -> EndReview(vm)
            TripPhase.ENDED -> Ended(vm)
        }
    }
}
