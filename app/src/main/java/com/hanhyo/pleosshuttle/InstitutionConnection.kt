package com.hanhyo.pleosshuttle

import android.content.Context
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.shuttle.net.Api
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Connects the prototype's local trip state to the current TayoTayo vehicle API. */
class InstitutionConnection(context: Context) {
    private val prefs = context.getSharedPreferences("institution", 0)
    val base get() = prefs.getString("base", "http://10.0.2.2:8080")!!
    val name get() = prefs.getString("name", "연결된 차량 없음")!!
    val connected get() = prefs.contains("credential")
    /** VS-02: 선생님이 고른 방향. 한 번도 고르지 않았으면 시간대 기본값(TripDirections.defaultFor). */
    val direction get() = InstitutionTrips.effectiveDirection(prefs.getString("direction", null), java.time.LocalTime.now().hour)
    private val credential get() = prefs.getString("credential", "")!!
    val boundTrip get() = prefs.getString("trip", "")!!
    private fun call(path: String, body: JSONObject? = null, method: String? = null) =
        Api.request(base, path, credential, body, method, "X-Vehicle-Credential")

    fun pair(address: String, code: String) {
        val server = Api.base(address, BuildConfig.DEBUG)
        val installId = prefs.getString("installId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installId", it).commit() }
        val value = Api.request(server, "/api/v1/vehicle/pair", data = JSONObject().put("code", code.trim())
            .put("installId", installId).put("appVersion", BuildConfig.VERSION_NAME).put("pleosSdkVersion", "unknown"))
        check(prefs.edit().putString("base", server).putString("credential", value.getString("vehicleCredential"))
            .putString("vehicleId", value.optString("vehicleId")).putString("orgId", value.optString("orgId"))
            .putString("name", "연결된 차량 ${value.optString("vehicleId")}").remove("token").commit())
    }

    /** Trips are created by the organization web/API. The vehicle app selects today's prepared trip. */
    fun setDirection(value: String) {
        require(value in setOf(TripDirections.PICK_UP, TripDirections.DROP_OFF))
        prefs.edit().putString("direction", value).apply()
    }

    fun createTrip(): TripState {
        val result = call("/api/v1/vehicle/trips?direction=$direction")
        val rows = result.optJSONArray("items") ?: JSONArray()
        val candidates = (0 until rows.length()).map { rows.getJSONObject(it) }.map { it.optString("id") to it.optString("status") }
        val tripId = InstitutionTrips.selectActiveTripId(candidates) ?: error(InstitutionTrips.noTripMessage(direction))
        val value = call("/api/v1/trips/$tripId/prepare", method = "POST")
        val stopRows = value.optJSONArray("stops") ?: JSONArray()
        val sample = SampleTrip.create()
        val stops = (0 until stopRows.length()).mapIndexed { i, index ->
            val row = stopRows.getJSONObject(index)
            val fallback = sample.stops.getOrNull(i % sample.stops.size) ?: sample.origin
            Stop(row.optString("id"), row.optString("stopName", "정류장 ${i + 1}"),
                row.optDouble("latitude", fallback.latitude), row.optDouble("longitude", fallback.longitude))
        }
        val childrenRows = value.optJSONArray("children") ?: JSONArray()
        val children = (0 until childrenRows.length()).mapIndexed { i, index ->
            val row = childrenRows.getJSONObject(index)
            val stopId = if (row.isNull("tripStopId")) stops.firstOrNull()?.id ?: "" else row.optString("tripStopId")
            InstitutionTrips.childRecord(row.optString("tripChildId"), row.optString("childName", "어린이"), stopId,
                row.optString("status"), row.opt("boardingExpected"),
                if (row.isNull("notBoardedReason")) null else row.optString("notBoardedReason"))
        }
        if (stops.isEmpty()) error("운행에 등록된 정류장이 없습니다.")
        check(prefs.edit().putString("pendingTrip", tripId).commit())
        val trip = TripState(tripId = tripId, integrationMode = IntegrationMode.SDK, origin = sample.origin,
            stops = stops, routeOrder = stops.map { it.id }, routeIsExample = false, children = children,
            direction = TripDirections.of(direction))
        bind(trip)
        return trip
    }

    fun bind(trip: TripState) {
        val stored = prefs.all
        // 같은 운행을 다시 묶을 때만 기존(구버전 전역 키 포함) 전송 기록을 유지하고, 다른 운행 기록은 지운다.
        val keep = if (boundTrip == trip.tripId) InstitutionTrips.sentEventIds(trip.tripId, stored) else emptySet()
        val edit = prefs.edit().putString("trip", trip.tripId).remove("pendingTrip")
        InstitutionTrips.staleSentKeys(trip.tripId, stored.keys).forEach { edit.remove(it) }
        check(edit.putStringSet(InstitutionTrips.sentEventsKey(trip.tripId), keep).commit())
    }
    fun recover(trip: TripState) { if (prefs.getString("pendingTrip", null) == trip.tripId) bind(trip) }
    private fun sentEventIds(tripId: String) = InstitutionTrips.sentEventIds(tripId, prefs.all)
    fun pending(trip: TripState): Int = if (trip.tripId != boundTrip) 0 else InstitutionTrips.pendingCount(trip.ledger, sentEventIds(trip.tripId))

    companion object { private val reportingLock = Any() }
    fun reportTracking(trip: TripState, enabled: Boolean, fix: TrackingLocation?, navigation: TrackingNavigation? = null,
                       stillCurrent: () -> Boolean = { true }) = synchronized(reportingLock) {
        if (!connected || trip.tripId != boundTrip || !stillCurrent() || fix == null) return@synchronized
        val body = JSONObject().put("samples", JSONArray().put(JSONObject().put("recordedAt", Instant.ofEpochMilli(fix.receivedAt).toString())
            .put("latitude", fix.latitude).put("longitude", fix.longitude).put("source", if (fix.simulated) "SIMULATED" else "PLEOS")))
        call("/api/v1/vehicle/trips/${trip.tripId}/locations", body)
    }

    fun sync(trip: TripState): Int {
        if (!connected || trip.tripId != boundTrip) return 0
        var count = 0
        val prefix = "/api/v1/trips/${trip.tripId}"
        fun sent(key: String) = prefs.getBoolean("$boundTrip:$key", false)
        fun mark(key: String) { check(prefs.edit().putBoolean("$boundTrip:$key", true).commit()); count++ }
        fun lifecycle(key: String, path: String, body: JSONObject? = null, method: String? = "POST") {
            if (sent(key)) return
            call("$prefix$path", body, method); mark(key)
        }
        val status = runCatching { call("$prefix/prepare", method = "POST").optString("status") }.getOrNull()
        if (status == "PREPARING") {
            // V-08: 출발 전 기사 확인은 출발할 때 한 번만 보낸다(체크 뒤 변경으로 해제될 수 있으므로 출발 전에는 보내지 않음).
            // 서버 TripController.checklist: driverConfirmed=true면 trips.driver_checked_at 기록, false면 비워 둔 채 "NG" 항목만.
            if (trip.phase != TripPhase.PREPARING) {
                lifecycle("checklist", "/checklist", JSONObject().put(InstitutionTrips.DRIVER_CONFIRMED_FIELD, InstitutionTrips.driverConfirmed(trip)), "PUT")
                lifecycle("start", "/start")
            }
        } else if (status != null && status != "COMPLETED" && trip.phase != TripPhase.PREPARING) {
            if (!sent("start")) lifecycle("start", "/start")
        }

        val localStops = trip.routeOrder.mapNotNull { id -> trip.stops.find { it.id == id } }
        for (i in localStops.indices) {
            val stopId = localStops[i].id
            val serverId = stopId
            val key = "stop-$serverId"
            if (trip.phase == TripPhase.ALIGHTING && trip.currentStop?.id == stopId) lifecycle("$key-arrive", "/stops/$serverId/arrive")
            if (trip.currentStopIndex > i || trip.phase in listOf(TripPhase.END_REVIEW, TripPhase.ENDED)) {
                if (!sent("$key-arrive")) lifecycle("$key-arrive", "/stops/$serverId/arrive")
                lifecycle("$key-complete", "/stops/$serverId/complete")
            }
        }
        // V-09·D17: 선생님이 뺀 정류장은 복귀(종료 확인) 뒤에만 서버에 skip으로 알린다. 서버 skip은 되돌릴 수 없고
        // (TripController.skip: PENDING/ARRIVED → SKIPPED, 되돌리는 API 없음) 운행 중에는 자동 재포함(D17)이 가능하므로
        // 더 이상 다시 넣을 수 없는 시점까지 기다린다. 서버 /complete는 COMPLETED·SKIPPED가 아닌 정류장이 있으면 거절한다.
        for (stopId in InstitutionTrips.stopsToSkip(trip)) lifecycle("stop-$stopId-skip", "/stops/$stopId/skip")
        // 등원(V-29)은 기관 도착이 종착이라 "복귀 중"(RETURNING)을 보내지 않는다. 서버 /complete는 IN_PROGRESS도 받는다.
        val sendReturn = !trip.isPickup
        if (trip.phase == TripPhase.END_REVIEW && sendReturn) {
            lifecycle("return", "/return")
        }
        if (trip.phase == TripPhase.ENDED) {
            if (sendReturn) lifecycle("return", "/return")
            lifecycle("interior", "/interior-check", JSONObject().put("checked", true))
            lifecycle("complete", "/complete")
        }

        val sentEvents = sentEventIds(trip.tripId).toMutableSet()
        val stopByChild = trip.children.associate { it.id to it.stopId }
        val serverEntries = InstitutionTrips.serverLedger(trip.ledger)
        val guardianCalls = serverEntries.mapNotNull { e -> InstitutionTrips.guardianCallRequest(trip.tripId, e)?.let { e.id to it } }.toMap()
        val ledgerRows = serverEntries.map { entry ->
            val eventType = InstitutionTrips.ledgerEventType(entry)
            val body = JSONObject().put("tripChildId", entry.childId).put("tripStopId", stopByChild[entry.childId])
            InstitutionTrips.eventPayload(entry).takeIf { it.isNotEmpty() }?.let { body.put("payload", JSONObject(it)) }
            Triple(entry.id, eventType, body)
        }
        val handovers = trip.handoverChildIds.map { id -> Triple("handover-$id", "CHILD_HANDED_OVER", JSONObject().put("tripChildId", id).put("tripStopId", stopByChild[id])) }
        for ((key, type, body) in ledgerRows + handovers) {
            if (key in sentEvents) continue
            body.put("clientEventId", UUID.nameUUIDFromBytes("${trip.tripId}:$key".toByteArray()).toString())
                .put("eventType", type).put("occurredAt", Instant.now().toString())
            if (type == "CHILD_NOT_BOARDED") body.put("reason", "OTHER")
            // V-28: 보호자 호출은 guardian-calls로 실제 알림을 열고(서버가 열린 호출이 있으면 그 호출을 돌려줘 중복 알림 없음),
            // 같은 원장 항목을 GUARDIAN_CALL_REQUESTED 이벤트로도 남긴다. 둘 다 성공해야 전송 완료로 표시한다.
            guardianCalls[key]?.let { (path, callBody) -> call(path, JSONObject(callBody)) }
            call("$prefix/events", body)
            sentEvents.add(key); check(prefs.edit().putStringSet(InstitutionTrips.sentEventsKey(trip.tripId), sentEvents.toSet()).commit()); count++
        }
        return count
    }
}

/** Pure mapping of the vehicle trip API responses (TripController.vehicleTrips / getTrip), kept JSON-free for unit tests. */
internal object InstitutionTrips {
    /** Server trip statuses that are still operable: PREPARING / IN_PROGRESS / RETURNING. COMPLETED·CANCELLED are finished. */
    val ACTIVE_TRIP_STATUSES = setOf("PREPARING", "IN_PROGRESS", "RETURNING")

    fun directionLabel(direction: String) = TripDirections.label(direction)

    /** VS-02 방향: 저장된 선택이 있으면 그 값, 없으면 시간대 기본값(D25·OQ-26 제안안). 선생님 선택은 유지한다. */
    fun effectiveDirection(stored: String?, hourOfDay: Int): String =
        if (stored.isNullOrBlank()) TripDirections.defaultFor(hourOfDay) else TripDirections.of(stored)

    fun noTripMessage(direction: String) =
        "오늘 차량에 배정된 ${directionLabel(direction)} 운행이 없습니다. 기관에서 운행을 먼저 생성해 주세요."

    /** [trips] is (id, status) in server order (id desc). Returns the newest not-finished trip. */
    fun selectActiveTripId(trips: List<Pair<String, String>>): String? =
        trips.firstOrNull { (id, status) -> id.isNotBlank() && status in ACTIVE_TRIP_STATUSES }?.first

    /** boardingExpected is TINYINT(1): JSON boolean (MySQL driver) or 0/1. Missing/unknown → 탑승 예정(D20). */
    fun boardingExpected(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> when (raw.trim().lowercase()) { "false", "0" -> false; else -> true }
        else -> true
    }

    /**
     * 장부(LedgerEntry) 한 건 → 서버 이벤트 유형(TripController.updateChildFromEvent).
     * - 미탑승 확인: TEACHER_CONFIRMED_NOT_BOARDED → trip_children.status=NOT_BOARDED (boarding_expected·사유는 그대로).
     *   CHILD_NOT_BOARDED는 boarding_expected=0·사유까지 덮어쓰므로 선생님 확인에는 쓰지 않는다.
     * - 미탑승 확인 취소(CONFIRMED_NOT_BOARDED → NOT_BOARDED): 서버에 상태를 EXPECTED로 되돌리는 이벤트가 없어
     *   스키마의 CORRECTION으로 이력만 남긴다(서버 상태는 NOT_BOARDED로 남음).
     */
    /** 서버로 보낼 원장 항목. V-05 벨트 확인·V-08 기사 확인 기록은 기기 안에만 두고 출결 이벤트로 보내지 않는다(D27). */
    /** V-09 정류장 제외·다시 넣기 기록도 출결 이벤트가 아니므로 보내지 않는다(서버에는 [stopsToSkip]의 skip으로만). */
    fun serverLedger(ledger: List<LedgerEntry>): List<LedgerEntry> = ledger.filterNot { it.isBeltRecord || it.isDriverCheckRecord || it.isStopExclusionRecord }

    /**
     * 서버 POST /trips/{id}/stops/{stopId}/skip 대상(차량 credential 허용: ApiAuthorizationInterceptor.isTripDeviceOperation).
     * 복귀 뒤(종료 확인·종료)에만, 끝까지 빠진 정류장. 운행 중에는 다시 넣을 수 있어 보내지 않는다.
     */
    fun stopsToSkip(trip: TripState): List<String> =
        if (trip.phase != TripPhase.END_REVIEW && trip.phase != TripPhase.ENDED) emptyList()
        else trip.excludedStops.filter { id -> id !in trip.routeOrder && trip.stops.any { it.id == id } }

    /** 서버가 받은(전송 성공) 이벤트 키 집합의 운행별 SharedPreferences 키. */
    fun sentEventsKey(tripId: String) = "sentEvents:$tripId"
    /** 구버전 빌드가 쓰던 운행 구분 없는 전역 키(쓰기: "sentEvents", 읽기: "sent"). */
    val LEGACY_SENT_KEYS = listOf("sentEvents", "sent")

    /** [stored](prefs.all)에서 [tripId]의 전송 완료 키. 구버전 전역 키도 합치고, 타입이 다르면 무시한다. */
    fun sentEventIds(tripId: String, stored: Map<String, *>): Set<String> =
        (listOf(sentEventsKey(tripId)) + LEGACY_SENT_KEYS)
            .flatMap { (stored[it] as? Set<*>).orEmpty().filterIsInstance<String>() }.toSet()

    /** [tripId]에 묶을 때 지울 키: 다른 운행의 전송 기록과 구버전 전역 키. */
    fun staleSentKeys(tripId: String, keys: Collection<String>): List<String> =
        keys.filter { it in LEGACY_SENT_KEYS || (it.startsWith("sentEvents:") && it != sentEventsKey(tripId)) }

    /** 전송 대기 = 서버 전송 대상 원장 항목 중 서버가 아직 받지 않은 것. */
    fun pendingCount(ledger: List<LedgerEntry>, sentIds: Set<String>): Int = serverLedger(ledger).count { it.id !in sentIds }

    /** PUT /api/v1/trips/{id}/checklist 본문 필드 이름(TripController.checklist가 읽는 키). */
    const val DRIVER_CONFIRMED_FIELD = "driverConfirmed"

    /** V-08: 출발 시점에 기사 확인 체크가 있으면 true(서버가 driver_checked_at 기록), 없으면 false("기사 확인 없음"). */
    fun driverConfirmed(trip: TripState): Boolean = trip.driverCheckedAtMillis != null

    /**
     * V-21 동승 유지 기록을 포함한 원장 항목 → 서버 이벤트 유형. 서버 TripController.updateChildFromEvent 기준:
     * - 동승 유지(stayOnboard=true, ONBOARD→ONBOARD): GUARDIAN_NO_SHOW → trip_children.status=GUARDIAN_NO_SHOW, no_show_at 기록(D35).
     *   서버는 이 이벤트의 사유를 저장하지 않으므로 사유 코드는 기기 원장에만 남긴다.
     * - 동승 유지 취소(stayOnboard=false): 서버에 BOARDED로 되돌리는 이벤트가 없어 CORRECTION으로 이력만(상태는 이후 인계·하차 이벤트가 덮어씀).
     * - 동승 유지 아이 기관 인계 하차(stayOnboard=true, →ALIGHTED): CHILD_RETURNED_TO_ORG → status=RETURNED_TO_ORG, returned_to_org_at.
     * 그 밖은 [ledgerEventType] (before, after) 규칙.
     */
    fun ledgerEventType(entry: LedgerEntry): String = when {
        entry.pickupAction != null -> pickupEventType(entry.pickupAction)
        entry.stayOnboard == false -> "CORRECTION"
        entry.stayOnboard == true && entry.after == Attendance.ALIGHTED -> "CHILD_RETURNED_TO_ORG"
        entry.stayOnboard == true && entry.after == Attendance.ONBOARD -> "GUARDIAN_NO_SHOW"
        else -> ledgerEventType(entry.before, entry.after)
    }

    /**
     * 등원(V-26~V-29) 원장 항목 → 서버 이벤트 유형. 서버 TripController.updateChildFromEvent·openapi eventType enum 기준:
     * - 정류장 인계받음(RECEIVED): CHILD_HANDED_OVER → trip_children.status=HANDED_OVER, handed_over_at(3.5절 정류장 `handed_over`).
     *   등원 전용 "보호자에게서 인계받음" 이벤트는 서버에 없다. 넘겨준 사람 유형은 payload로만 보내며 서버는 payload를 저장하지 않는다.
     * - 탑승(BOARDED): CHILD_BOARDED → status=BOARDED.
     * - 보호자 도착 알림(GUARDIAN_CALLED): GUARDIAN_CALL_REQUESTED(이력만, 상태 변화 없음). 실제 알림은 [guardianCallRequest].
     * - 정류장 미탑승 확인(NOT_BOARDED_*): TEACHER_CONFIRMED_NOT_BOARDED → status=NOT_BOARDED(기관 웹 자동 기록, D31).
     *   보호자 미탑승 안내 선택(D30)은 payload notifyGuardian으로만 보낸다(서버에 이 안내 발송 경로 없음).
     * - 기관 인계 하차(INSTITUTION_HANDOVER): CHILD_RECEIVED_BY_ORG → status=DROPPED_OFF + 보호자 알림(notifyChildDroppedOff).
     */
    fun pickupEventType(action: String): String = when (action) {
        PickupActions.RECEIVED -> "CHILD_HANDED_OVER"
        PickupActions.BOARDED -> "CHILD_BOARDED"
        PickupActions.GUARDIAN_CALLED -> "GUARDIAN_CALL_REQUESTED"
        PickupActions.NOT_BOARDED_NOTIFY, PickupActions.NOT_BOARDED_SILENT -> "TEACHER_CONFIRMED_NOT_BOARDED"
        PickupActions.INSTITUTION_HANDOVER -> "CHILD_RECEIVED_BY_ORG"
        else -> "CORRECTION"
    }

    /** 이벤트 payload(서버 openapi TripEvent.payload). 이름 등 개인정보는 넣지 않는다. 하원 기록은 빈 맵(payload 없음). */
    fun eventPayload(entry: LedgerEntry): Map<String, Any> = buildMap {
        entry.receivedFromType?.let { put("receivedFromType", it) }
        when (entry.pickupAction) {
            PickupActions.NOT_BOARDED_NOTIFY -> put("notifyGuardian", true)
            PickupActions.NOT_BOARDED_SILENT -> put("notifyGuardian", false)
        }
    }

    /**
     * V-28 보호자 호출 요청(ShareAndCallController.openCall, 차량 credential 허용). 보호자 호출 원장 항목만 (경로, 본문)을 돌려준다.
     * [LedgerEntry.childId]는 서버 tripChildId다(createTrip). 서버는 같은 아이의 열린 호출(10분)이 있으면 새로 만들지 않는다.
     */
    fun guardianCallRequest(tripId: String, entry: LedgerEntry): Pair<String, Map<String, String>>? =
        if (entry.pickupAction != PickupActions.GUARDIAN_CALLED || entry.childId.isBlank()) null
        else "/api/v1/trips/$tripId/children/${entry.childId}/guardian-calls" to mapOf("callType" to GUARDIAN_CALL_TYPE)

    /** 서버 openCall이 받는 유일한 callType. */
    const val GUARDIAN_CALL_TYPE = "CHILD_NOT_AT_STOP"

    fun ledgerEventType(before: Attendance, after: Attendance): String = when (after) {
        Attendance.ONBOARD -> "CHILD_BOARDED"
        Attendance.ALIGHTED -> "CHILD_DROPPED_OFF"
        Attendance.CONFIRMED_NOT_BOARDED -> "TEACHER_CONFIRMED_NOT_BOARDED"
        Attendance.NOT_BOARDED -> if (before == Attendance.CONFIRMED_NOT_BOARDED) "CORRECTION" else "CHILD_NOT_BOARDED"
    }

    /**
     * 서버 status=NOT_BOARDED는 두 경우다: 운행 생성 때 승인된 결석·직접 픽업으로 미리 넣은 값(boardingExpected=0),
     * 또는 선생님 미탑승 확인(TEACHER_CONFIRMED_NOT_BOARDED, boardingExpected 유지=1). 앞의 것은 아직 선생님이 확인하지
     * 않았으므로 미결정(NOT_BOARDED), 뒤의 것은 이미 확인된 상태로 유지한다(D16).
     */
    fun childRecord(id: String, name: String, stopId: String, status: String, boardingExpected: Any?, notBoardedReason: String?): ChildRecord {
        val expected = boardingExpected(boardingExpected)
        return ChildRecord(id, name, stopId, "미배정",
            when (status) {
                "BOARDED" -> Attendance.ONBOARD
                "NOT_BOARDED" -> if (expected) Attendance.CONFIRMED_NOT_BOARDED else Attendance.NOT_BOARDED
                "DROPPED_OFF" -> Attendance.ALIGHTED
                // V-21: 서버에 동승 유지(보호자 미도착)로 기록된 아이는 아직 차 안에 있다. 서버는 사유를 저장하지 않아 "보호자 미도착"으로 둔다.
                "GUARDIAN_NO_SHOW" -> Attendance.ONBOARD
                else -> Attendance.NOT_BOARDED
            },
            notExpectedToBoard = !expected,
            notBoardingReason = notBoardedReason?.takeIf { it.isNotBlank() },
            stayOnboardReason = if (status == "GUARDIAN_NO_SHOW") StayOnboardReasons.GUARDIAN_ABSENT else null)
    }
}
