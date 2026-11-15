package com.hanhyo.pleosshuttle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanhyo.pleosshuttle.InstitutionTrips
import com.hanhyo.pleosshuttle.ShuttleViewModel
import com.hanhyo.pleosshuttle.domain.TripDirections

@Composable fun InstitutionPanel(vm: ShuttleViewModel) {
    var base by rememberSaveable { mutableStateOf(vm.institutionBase) }
    var code by rememberSaveable { mutableStateOf("") }
    Text("기관 · 차량 연결", style = MaterialTheme.typography.headlineSmall)
    Text("관리 화면의 차량 연결 코드를 입력하세요. 보호자 폰의 어린이 초대 코드와는 다릅니다. 연결 후 배정 명단을 불러오면 기존 탑승·인계·하차 흐름으로 기록할 수 있습니다.")
    OutlinedTextField(base, { base = it }, label = { Text("서버 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("institution-server"), enabled = !vm.institutionBusy)
    OutlinedTextField(code, { code = it }, label = { Text("차량 연결 코드") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("institution-code"), enabled = !vm.institutionBusy)
    Button(onClick = { vm.pairInstitution(base, code) }, enabled = !vm.institutionBusy && code.isNotBlank(), modifier = Modifier.testTag("institution-pair")) { Text("차량 연결") }
    Text(vm.institutionName)
    if (vm.institutionConnected) DisconnectInstitution(vm)
    Text("운행 방향")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TripDirections.PICK_UP, TripDirections.DROP_OFF).forEach { direction ->
            FilterChip(selected = vm.institutionDirection == direction, onClick = { vm.chooseInstitutionDirection(direction) },
                label = { Text(TripDirections.label(direction)) }, enabled = !vm.institutionBusy,
                modifier = Modifier.testTag("institution-direction-$direction"))
        }
    }
    Text("처음에는 시간대로 정해요(정오 전 등원, 그 뒤 하원). 고른 방향은 다음에도 유지돼요.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.loadInstitutionTrip() }, enabled = !vm.institutionBusy && vm.institutionConnected, modifier = Modifier.testTag("institution-load")) { Text("배정 명단으로 운행 준비") }
        OutlinedButton(onClick = { vm.syncInstitution(true) }, enabled = !vm.institutionBusy && vm.institutionConnected) { Text("기록 전송 재시도") }
    }
    if (vm.tripChoices.isNotEmpty()) TripChoiceList(vm)
    HorizontalDivider()
    Text("배정 명단을 준비하면 Pleos에 자동 연결됩니다. 운행 시작 시 보호자에게 위치·예상 시간을 공유하고, D로 전환하면 내비 화면이 열립니다.")
    Text(vm.institutionStatus, modifier = Modifier.testTag("institution-status"))
    Text("명단 불러오기는 기록 전 준비 상태 또는 운행 종료 후에 가능합니다. 기존 운행은 보관됩니다. 서버 연결이 끊기면 기기에 기록하고, 이 앱이 열려 있을 때 다시 전송합니다.")
}

/** 오늘 운행이 둘 이상일 때. 누가 골라도 되므로(선생님·기사님) 문구에 사람을 넣지 않는다. */
@Composable internal fun TripChoiceList(vm: ShuttleViewModel) {
    Column(Modifier.fillMaxWidth().testTag("trip-choices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("오늘 ${TripDirections.label(vm.institutionDirection)} 운행을 골라 주세요", style = MaterialTheme.typography.titleMedium)
        vm.tripChoices.forEach { option ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("운행 ${option.id}" + if (option.inProgress) " · 이어서 진행" else "", style = MaterialTheme.typography.titleSmall)
                        Text(InstitutionTrips.optionSummary(option), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { vm.chooseInstitutionTrip(option.id) }, enabled = !vm.institutionBusy,
                        modifier = Modifier.testTag("trip-choice-${option.id}")) { Text("이 운행 준비") }
                }
            }
        }
        TextButton(onClick = vm::dismissTripChoices, enabled = !vm.institutionBusy) { Text("닫기") }
    }
}

/** [차량 연결 해제]: 연결된 경우에만 보인다. 진행 중 운행·미전송 기록이 있으면 버튼을 끄고 이유를 보여 준다. 누르면 한 번 더 확인한다. */
@Composable private fun DisconnectInstitution(vm: ShuttleViewModel) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val blocker = vm.disconnectBlocker
    OutlinedButton(onClick = { confirming = true }, enabled = blocker == null, modifier = Modifier.testTag("institution-disconnect")) { Text("차량 연결 해제") }
    blocker?.takeIf { vm.institutionConnected }?.let { Text(it, modifier = Modifier.testTag("institution-disconnect-blocked")) }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("차량 연결 해제") },
        text = { Text("이 차량의 기관 연결을 해제할까요? 다시 쓰려면 새 연결 코드가 필요해요.\n\n" +
            "오늘 이미 만들어진 운행은 다시 연결해도 이 기기에서 보이지 않아요. 기관에서 운행을 다시 만들어야 해요.",
            modifier = Modifier.testTag("institution-disconnect-warning")) },
        confirmButton = { TextButton(onClick = { confirming = false; vm.disconnectInstitution() }, modifier = Modifier.testTag("institution-disconnect-confirm")) { Text("연결 해제") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("취소") } },
    )
}
