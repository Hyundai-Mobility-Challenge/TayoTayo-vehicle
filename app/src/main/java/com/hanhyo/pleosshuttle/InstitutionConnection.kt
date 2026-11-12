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
    val direction get() = prefs.getString("direction", "DROP_OFF")!!
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
        require(value in setOf("PICK_UP", "DROP_OFF"))
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
            stops = stops, routeOrder = stops.map { it.id }, routeIsExample = false, children = children)
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
        if (trip.phase == TripPhase.END_REVIEW) {
            lifecycle("return", "/return")
        }
        if (trip.phase == TripPhase.ENDED) {
            lifecycle("return", "/return")
            lifecycle("interior", "/interior-check", JSONObject().put("checked", true))
            lifecycle("complete", "/complete")
        }

        val sentEvents = sentEventIds(trip.tripId).toMutableSet()
        val stopByChild = trip.children.associate { it.id to it.stopId }
        val ledgerRows = InstitutionTrips.serverLedger(trip.ledger).map { entry ->
            val eventType = InstitutionTrips.ledgerEventType(entry.before, entry.after)
            Triple(entry.id, eventType, JSONObject().put("tripChildId", entry.childId).put("tripStopId", stopByChild[entry.childId]))
        }
        val handovers = trip.handoverChildIds.map { id -> Triple("handover-$id", "CHILD_HANDED_OVER", JSONObject().put("tripChildId", id).put("tripStopId", stopByChild[id])) }
        for ((key, type, body) in ledgerRows + handovers) {
            if (key in sentEvents) continue
            body.put("clientEventId", UUID.nameUUIDFromBytes("${trip.tripId}:$key".toByteArray()).toString())
                .put("eventType", type).put("occurredAt", Instant.now().toString())
            if (type == "CHILD_NOT_BOARDED") body.put("reason", "OTHER")
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

    fun directionLabel(direction: String) = if (direction == "PICK_UP") "등원" else "하원"

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
    fun serverLedger(ledger: List<LedgerEntry>): List<LedgerEntry> = ledger.filterNot { it.isBeltRecord || it.isDriverCheckRecord }

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
                else -> Attendance.NOT_BOARDED
            },
            notExpectedToBoard = !expected,
            notBoardingReason = notBoardedReason?.takeIf { it.isNotBlank() })
    }
}
