package com.hanhyo.pleosshuttle

import android.content.Context
import com.hanhyo.pleosshuttle.domain.*
import com.hanhyo.shuttle.net.Api
import com.hanhyo.shuttle.net.ApiFailure
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Connects the prototype's local trip state to the current TayoTayo vehicle API. */
class InstitutionConnection(context: Context) {
    private val prefs = context.getSharedPreferences("institution", 0)
    /** 마지막 동기화 때 받은 보호자 호출 상태(tripChildId → 정보). 운행이 바뀌면 [bind]에서 비운다. */
    @Volatile var lastGuardianCalls: Map<String, GuardianCallInfo> = emptyMap(); private set
    val base get() = prefs.getString("base", "http://10.0.2.2:8080")!!
    val name get() = InstitutionTrips.connectionLabel(connected, prefs.getString("name", null))
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
            .put("installId", installId).put("appVersion", BuildConfig.VERSION_NAME).put("pleosSdkVersion", InstitutionTrips.pleosSdkVersion(BuildConfig.PLEOS_VEHICLE_SDK, BuildConfig.PLEOS_NAVI_SDK, BuildConfig.PLEOS_TTS_SDK)))
        check(prefs.edit().putString("base", server).putString("credential", value.getString("vehicleCredential"))
            .putString("vehicleId", value.optString("vehicleId")).putString("orgId", value.optString("orgId"))
            .putString("name", value.optString("vehicleName").trim()).remove("token").commit())
    }

    /**
     * 차량 연결 해제(서버 VehicleManagementController.revoke: X-Vehicle-Credential의 기기 자격 증명에 revoked_at 기록).
     * 서버가 401이면 이 자격 증명은 이미 폐기됐거나 서버가 모르는 것이다(모든 차량 API가 같은 검사로 401). 더 쓸 수 없으므로
     * [RevokeResult.ALREADY_REVOKED]로 알리고 호출부가 로컬 연결 정보를 지운다. 네트워크 오류·다른 서버 오류는 예외로 올린다.
     */
    fun revoke(): RevokeResult = try {
        call("/api/v1/vehicle/session/revoke", JSONObject(), "POST"); RevokeResult.REVOKED
    } catch (e: ApiFailure) {
        if (InstitutionTrips.revokeClearsLocal(e.status)) RevokeResult.ALREADY_REVOKED else throw e
    }

    /**
     * 로컬 연결 정보를 지운다: 자격 증명·차량·기관 id·차량 이름·운행 바인딩·전송 기록 키("<운행>:<키>", "sentEvents:<운행>").
     * 서버 주소·설치 id(installId)·고른 운행 방향은 다시 연결할 때 쓰려고 남긴다.
     */
    fun clearLocal() {
        lastGuardianCalls = emptyMap()
        val edit = prefs.edit()
        InstitutionTrips.connectionKeysToClear(prefs.all.keys).forEach { edit.remove(it) }
        check(edit.commit())
    }

    /** Trips are created by the organization web/API. The vehicle app selects today's prepared trip. */
    fun setDirection(value: String) {
        require(value in setOf(TripDirections.PICK_UP, TripDirections.DROP_OFF))
        prefs.edit().putString("direction", value).apply()
    }

    /**
     * 오늘 이 차량에 배정된 [direction] 운행 중 끝나지 않은 것(준비·운행 중·복귀 중). 목록 응답에는 노선 이름·인원이 없어
     * 운행마다 `prepare`(서버에서 읽기만 함)로 정류장·인원을 채운다. 진행 중인 운행이 앞.
     */
    fun listTrips(): List<TripOption> {
        val result = call("/api/v1/vehicle/trips?direction=$direction")
        val rows = result.optJSONArray("items") ?: JSONArray()
        val active = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("id").isNotBlank() && it.optString("status") in InstitutionTrips.ACTIVE_TRIP_STATUSES }
        return InstitutionTrips.orderOptions(active.map { row ->
            val id = row.optString("id")
            val detail = runCatching { call("/api/v1/trips/$id/prepare", method = "POST") }.getOrNull()
            val stopRows = detail?.optJSONArray("stops") ?: JSONArray()
            val childRows = detail?.optJSONArray("children") ?: JSONArray()
            val names = (0 until stopRows.length()).map { stopRows.getJSONObject(it).optString("stopName") }.filter { it.isNotBlank() }
            val expected = (0 until childRows.length()).count { InstitutionTrips.boardingExpected(childRows.getJSONObject(it).opt("boardingExpected")) }
            TripOption(id, row.optString("status"), names, childRows.length(), expected)
        })
    }

    /** [chosenTripId]가 없으면 예전처럼 가장 최근의 끝나지 않은 운행. */
    fun createTrip(chosenTripId: String? = null): TripState {
        val tripId = chosenTripId ?: run {
            val result = call("/api/v1/vehicle/trips?direction=$direction")
            val rows = result.optJSONArray("items") ?: JSONArray()
            val candidates = (0 until rows.length()).map { rows.getJSONObject(it) }.map { it.optString("id") to it.optString("status") }
            InstitutionTrips.selectActiveTripId(candidates) ?: error(InstitutionTrips.noTripMessage(direction))
        }
        val value = call("/api/v1/trips/$tripId/prepare", method = "POST")
        val stopRows = value.optJSONArray("stops") ?: JSONArray()
        // 정류장 좌표가 없으면 임의 좌표로 채우지 않는다(내비·보호자 공유가 엉뚱한 곳을 가리키지 않게). 운행 준비를 멈추고 알린다.
        val stops = (0 until stopRows.length()).mapIndexed { i, index ->
            val row = stopRows.getJSONObject(index)
            val name = row.optString("stopName", "정류장 ${i + 1}")
            val lat = row.optDouble("latitude", Double.NaN); val lng = row.optDouble("longitude", Double.NaN)
            if (!InstitutionTrips.validCoordinate(lat, lng)) error("정류장 위치(좌표)가 없습니다: $name · 기관 관리 화면에서 정류장 주소를 확인해 주세요.")
            Stop(row.optString("id"), name, lat, lng)
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
        // 기관 위치: 서버 응답 orgName·orgLatitude·orgLongitude(또는 org 묶음). 없으면 임의 위치로 대신하지 않고 운행 준비를 멈춘다.
        val origin = InstitutionTrips.institutionOrigin(value)
            ?: error("기관 위치(좌표)를 받지 못했습니다. 기관 관리 화면에서 기관 주소를 확인해 주세요.")
        check(prefs.edit().putString("pendingTrip", tripId).commit())
        val trip = TripState(tripId = tripId, integrationMode = IntegrationMode.SDK, origin = origin,
            stops = stops, routeOrder = stops.map { it.id }, routeIsExample = false, children = children,
            direction = TripDirections.of(direction))
        bind(trip)
        return trip
    }

    fun bind(trip: TripState) {
        lastGuardianCalls = emptyMap()
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
    /** 지금 운행 단계에서 보냈어야 하는데 아직 서버에 가지 않은 단계 기록 수(sync와 같은 키 규칙, InstitutionTrips.lifecycleSteps). */
    fun unsentLifecycle(trip: TripState): Int = if (!connected || trip.tripId != boundTrip) 0
        else InstitutionTrips.unsentLifecycleKeys(trip) { key -> prefs.getBoolean("$boundTrip:$key", false) }.size

    companion object {
        private val reportingLock = Any()
    }
    /**
     * 위치 한 건을 보내고, 내비 관측이 있으면 예상 시간(eta-snapshots)을 따로 보낸다.
     * 위치 전송 실패는 예외로 알린다(호출부가 "전송 연결 끊김"으로 표시). 예상 시간 전송 실패는 위치 전송을 실패로 만들지 않고
     * [TrackingReport.eta] = [EtaReport.FAILED]로만 알린다. 같은 예상 시간 관측은 다시 보내지 않으며, 보낸 키는 성공했을 때만 기억한다.
     */
    fun reportTracking(trip: TripState, enabled: Boolean, fix: TrackingLocation?, navigation: TrackingNavigation? = null,
                       vehicle: VehicleSample? = null, stillCurrent: () -> Boolean = { true }): TrackingReport = synchronized(reportingLock) {
        if (!connected || trip.tripId != boundTrip || !stillCurrent() || fix == null) return@synchronized TrackingReport(locationSent = false)
        val body = JSONObject().put("samples", JSONArray().put(JSONObject().put("recordedAt", Instant.ofEpochMilli(fix.receivedAt).toString())
            .put("latitude", fix.latitude).put("longitude", fix.longitude).put("source", if (fix.simulated) "SIMULATED" else "PLEOS")
            // 차량 상태는 신선한 관측이 있을 때만(vehicleSample). 방향·정확도는 관측 출처가 없어 넣지 않는다.
            .apply { vehicle?.let { put("speedKph", it.speedKph).put("gear", it.gear) } }))
        call("/api/v1/vehicle/trips/${trip.tripId}/locations", body)
        // 내비 남은 시간 → 서버 eta-snapshots(VehicleTelemetryController.eta). 서버는 tripStopId가 있는 항목으로
        // trip_stops.eta_remaining_sec/m를 갱신하고, 보호자 앱·공유 링크가 그 값을 읽는다.
        val eta = InstitutionTrips.etaSnapshot(trip, navigation, fix.simulated)
            ?: return@synchronized TrackingReport(locationSent = true, eta = EtaReport.NOT_AVAILABLE)
        val key = "${trip.tripId}/${eta.sourceTime}/${eta.entries}"
        if (!InstitutionTrips.shouldSendEta(key, lastEtaKey)) return@synchronized TrackingReport(locationSent = true, eta = EtaReport.UNCHANGED)
        val outcome = if (runCatching { call("/api/v1/vehicle/trips/${trip.tripId}/eta-snapshots", InstitutionTrips.etaBody(eta)) }.isSuccess)
            EtaReport.SENT else EtaReport.FAILED
        lastEtaKey = InstitutionTrips.nextEtaKey(lastEtaKey, key, outcome)
        TrackingReport(locationSent = true, eta = outcome)
    }
    @Volatile private var lastEtaKey: String? = null

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
        val prepared = runCatching { call("$prefix/prepare", method = "POST") }.getOrNull()
        prepared?.let { lastGuardianCalls = InstitutionTrips.guardianCalls(it) }
        val status = prepared?.optString("status")
        if (status == "PREPARING") {
            // V-08: 출발 전 기사 확인은 출발할 때 한 번만 보낸다(체크 뒤 변경으로 해제될 수 있으므로 출발 전에는 보내지 않음).
            // 서버 TripController.checklist: driverConfirmed=true면 trips.driver_checked_at 기록, false면 비워 둔 채 "NG" 항목만.
            if (trip.phase != TripPhase.PREPARING) {
                lifecycle("checklist", "/checklist", JSONObject().put(InstitutionTrips.DRIVER_CONFIRMED_FIELD, InstitutionTrips.driverConfirmed(trip))
                    .apply { InstitutionTrips.checkedAt(trip)?.let { put("checkedAt", it) } }, "PUT")
                lifecycle("start", "/start")
            }
        } else if (status != null && status != "COMPLETED" && trip.phase != TripPhase.PREPARING) {
            if (!sent("start")) lifecycle("start", "/start")
        }

        // 정류장 도착·완료 → 뺀 정류장 skip → 복귀 → 실내 확인 → 완료. 보낼 키와 순서는 InstitutionTrips.lifecycleSteps 한곳에서 정한다
        // (연결 해제 판단 disconnectBlocker도 같은 규칙으로 "아직 안 보낸 단계 기록"을 센다). start는 위에서 서버 상태에 맞춰 보냈다.
        for (step in InstitutionTrips.lifecycleSteps(trip)) {
            if (step.key == InstitutionTrips.START_KEY) continue
            lifecycle(step.key, step.path, if (step.checkedBody) JSONObject().put("checked", true) else null)
        }

        val sentEvents = sentEventIds(trip.tripId).toMutableSet()
        val stopByChild = trip.children.associate { it.id to it.stopId }
        val serverEntries = InstitutionTrips.serverLedger(trip.ledger)
        val guardianCalls = serverEntries.mapNotNull { e -> InstitutionTrips.guardianCallRequest(trip.tripId, e)?.let { e.id to it } }.toMap()
        val ledgerRows = serverEntries.map { entry ->
            val eventType = InstitutionTrips.ledgerEventType(entry)
            val body = JSONObject().put("tripChildId", entry.childId).put("tripStopId", stopByChild[entry.childId])
            InstitutionTrips.eventPayload(entry).takeIf { it.isNotEmpty() }?.let { body.put("payload", JSONObject(it)) }
            Triple(entry.id, eventType, body) to entry.childId
        }
        val handovers = trip.handoverChildIds.map { id -> Triple("handover-$id", "CHILD_HANDED_OVER", JSONObject().put("tripChildId", id).put("tripStopId", stopByChild[id])) to id }
        val pending = (ledgerRows + handovers).filter { (row, _) -> row.first !in sentEvents }.map { (row, childId) ->
            val (key, type, body) = row
            body.put("clientEventId", UUID.nameUUIDFromBytes("${trip.tripId}:$key".toByteArray()).toString())
                .put("eventType", type).put("occurredAt", Instant.now().toString())
            if (type == "CHILD_NOT_BOARDED") body.put("reason", "OTHER")
            PendingEvent(key, type, childId.takeIf { it.isNotBlank() }, body, guardianCall = key in guardianCalls)
        }
        // 묶음 나누기·보호자 호출 순서·응답 해석·실패 대체는 InstitutionTrips.sendPendingEvents(단위 테스트 대상)가 정한다.
        InstitutionTrips.sendPendingEvents(pending, EventTransport(
            guardianCall = { item -> guardianCalls[item.key]?.let { (path, callBody) -> call(path, JSONObject(callBody)) } },
            single = { item -> call("$prefix/events", item.body) },
            batch = { items ->
                val response = call("$prefix/events/batch", JSONObject().put("events", JSONArray().apply { items.forEach { put(it.body) } }))
                if (response.has("acceptedCount")) response.optInt("acceptedCount", -1) else null
            },
            markSent = { keys ->
                sentEvents.addAll(keys); check(prefs.edit().putStringSet(InstitutionTrips.sentEventsKey(trip.tripId), sentEvents.toSet()).commit()); count += keys.size
            },
        ))
        return count
    }
}

/** Pure mapping of the vehicle trip API responses (TripController.vehicleTrips / getTrip), kept JSON-free for unit tests. */
/** 운행 고르기 목록 한 줄. 서버 목록에 노선 이름·출발 시각이 없어 정류장 이름으로 구분한다. */
data class TripOption(val id: String, val status: String, val stopNames: List<String>, val childCount: Int, val expectedCount: Int) {
    val inProgress get() = status == "IN_PROGRESS" || status == "RETURNING"
}

/** sync가 보내는 운행 단계 기록 하나. [checkedBody]면 {"checked": true}를 함께 보낸다. */
data class LifecycleStep(val key: String, val path: String, val checkedBody: Boolean = false)
/** 미전송 원장 이벤트 하나. [body]는 실제 전송 본문(앱에서는 JSONObject). [guardianCall]이면 보내기 전에 보호자 호출을 연다. */
data class PendingEvent<B>(val key: String, val type: String, val childId: String?, val body: B, val guardianCall: Boolean = false)
/** 이벤트 전송 수단. batch는 서버 acceptedCount(없으면 null)를 돌려준다. 실패는 예외(HTTP 오류는 ApiFailure). */
class EventTransport<B>(
    val guardianCall: (PendingEvent<B>) -> Unit,
    val single: (PendingEvent<B>) -> Unit,
    val batch: (List<PendingEvent<B>>) -> Int?,
    val markSent: (List<String>) -> Unit,
)

/** 차량 연결 해제 결과. ALREADY_REVOKED = 서버가 401(이미 폐기·무효)로 답해 로컬만 지움. */
enum class RevokeResult { REVOKED, ALREADY_REVOKED }

/** 예상 시간(eta-snapshots) 전송 결과. NOT_AVAILABLE = 보낼 내비 관측 없음, UNCHANGED = 이미 보낸 관측과 같아 보내지 않음. */
enum class EtaReport { NOT_AVAILABLE, SENT, UNCHANGED, FAILED }
/** [InstitutionConnection.reportTracking] 결과. 위치 전송 실패는 예외로 알리므로 여기에는 담지 않는다. */
data class TrackingReport(val locationSent: Boolean, val eta: EtaReport = EtaReport.NOT_AVAILABLE)

/** 서버 eta-snapshots 항목 하나. [tripStopId]는 서버 trip_stops.id(숫자), 정류장과 짝이 없으면(기관·출발지) null. */
data class EtaEntry(val bundleIndex: Int, val pointType: String, val tripStopId: Long?,
    val latitude: Double, val longitude: Double, val remainingSec: Int, val remainingM: Int)
data class EtaSnapshot(val routeRevisionNo: Int, val navRouteKey: String?, val sourceTime: Long,
    val simulated: Boolean, val entries: List<EtaEntry>)

internal object InstitutionTrips {
    /**
     * 기관·차량 연결 화면의 연결 상태 문구. 서버가 차량 이름(vehicleName)을 주면 "연결된 차량 · 타요 1호차",
     * 없으면 차량 번호(ID) 대신 "차량 연결됨". 예전 빌드가 저장한 "연결된 차량 3" 같은 ID 표기도 숨긴다.
     */
    fun connectionLabel(connected: Boolean, storedName: String?): String {
        if (!connected) return "연결된 차량 없음"
        val name = storedName?.trim().orEmpty()
        return if (name.isEmpty() || Regex("연결된 차량 \\d+").matches(name)) "차량 연결됨" else "연결된 차량 · $name"
    }
    /**
     * 내비 관측([TrackingNavigation]) → 서버 eta-snapshots 본문 재료. READY(검증 통과)일 때만 만든다.
     * - 경유지 묶음(stops)이 있으면 순서대로, 없으면 단일 목적지 값 하나.
     * - 각 항목은 남은 방문 순서의 정류장([navigationWaypoints])과 좌표가 일치할 때만 tripStopId를 단다.
     *   (NavigationTracking.snapshot이 정규화한 좌표는 등록 정류장 좌표와 같다.) 일치가 없으면 null.
     * - routeRevisionNo: 서버 운행 응답에 서버 노선 개정 번호가 없어 차량의 [TripState.routeVersion]을 보낸다(서버는 저장만 함).
     * - 정차·하차 중(ALIGHTING)이면 현재 정류장(이미 도착) 항목은 뺀다. 남는 항목이 없으면 보내지 않는다(null).
     */
    fun etaSnapshot(trip: TripState, nav: TrackingNavigation?, simulated: Boolean): EtaSnapshot? {
        if (nav == null || nav.state != "READY") return null
        val points = nav.stops.ifEmpty {
            val lat = nav.latitude; val lng = nav.longitude; val sec = nav.remainSeconds; val m = nav.remainMeters; val at = nav.receivedAt
            if (lat != null && lng != null && sec != null && m != null && at != null) listOf(NavigationStopEta(lat, lng, sec, m, at)) else emptyList()
        }
        if (points.isEmpty()) return null
        val candidates = navigationWaypoints(trip)
        // 정차·하차 중(ALIGHTING)인 현재 정류장은 이미 도착했다. 그 정류장 항목은 보내지 않는다(보호자 화면에 "곧 도착"이 다시 뜨지 않게).
        val arrived = trip.currentStop?.takeIf { trip.phase == TripPhase.ALIGHTING }
        fun same(stop: Stop, p: NavigationStopEta) = kotlin.math.abs(stop.latitude - p.latitude) < 0.00001 && kotlin.math.abs(stop.longitude - p.longitude) < 0.00001
        val entries = points.mapIndexedNotNull { i, p ->
            val stop = candidates.firstOrNull { same(it, p) }
            if (arrived != null && (stop?.id == arrived.id || (stop == null && same(arrived, p)))) return@mapIndexedNotNull null
            EtaEntry(i, if (i == points.lastIndex) "DESTINATION" else "WAYPOINT", stop?.id?.toLongOrNull(),
                p.latitude, p.longitude, p.seconds, p.meters)
        }
        if (entries.isEmpty()) return null
        return EtaSnapshot(trip.routeVersion.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), nav.routeId?.take(100),
            points.first().receivedAt, simulated, entries)
    }

    /** 연결 해제 때 지울 로컬 키. 서버 주소(base)·installId·direction은 남긴다. */
    fun connectionKeysToClear(keys: Collection<String>): List<String> = keys.filter { key ->
        key in setOf("credential", "token", "vehicleId", "orgId", "name", "trip", "pendingTrip") || ':' in key
    }
    /** revoke 실패 상태 중 로컬을 지워도 되는 경우: 401(자격 증명 폐기됨·무효 → 어떤 차량 API도 더 쓸 수 없음). */
    fun revokeClearsLocal(status: Int): Boolean = status == 401
    /** 연결 해제 판단에 쓰는 운행·기록 상태. [unsentSteps]는 아직 안 보낸 단계 기록(start·정류장·복귀·완료 등) 수. */
    data class DisconnectState(
        val connected: Boolean, val hasTrip: Boolean, val phase: TripPhase, val ledgerEmpty: Boolean, val onboardEmpty: Boolean,
        val pendingEvents: Int, val unsentSteps: Int, val busy: Boolean, val storageError: Boolean,
    )
    /**
     * [차량 연결 해제]를 막을 이유. null이면 해제해도 된다.
     * 운행 바꾸기와 같은 기준(ShuttleViewModel.canReplaceInstitutionTrip)으로, 운행이 없거나 · 끝났거나 · 기록 없는 운행 준비일 때만 허용한다.
     * 저장 오류, 서버 통신 중, 아직 안 보낸 원장 이벤트·단계 기록(예: 종료했는데 /complete 미전송)이 있으면 막는다.
     */
    fun disconnectBlocker(s: DisconnectState): String? = when {
        !s.connected -> "연결된 차량이 없어요."
        s.storageError -> "운행 저장 오류가 있어요. 오류를 먼저 확인해 주세요."
        s.busy -> "서버와 기록을 주고받는 중이에요. 잠시 뒤 다시 시도해 주세요."
        s.hasTrip && s.phase != TripPhase.ENDED && !(s.phase == TripPhase.PREPARING && s.ledgerEmpty && s.onboardEmpty) ->
            if (s.phase == TripPhase.PREPARING) "운행 준비 중 기록이 있어요. 운행을 마친 뒤 해제해 주세요." else "진행 중인 운행이 있어요. 운행을 마친 뒤 해제해 주세요."
        s.pendingEvents + s.unsentSteps > 0 -> "서버로 보내지 않은 기록 ${s.pendingEvents + s.unsentSteps}건이 있어요. [기록 전송 재시도]로 보낸 뒤 해제해 주세요."
        else -> null
    }

    const val START_KEY = "start"
    /**
     * sync가 운행 단계에 따라 보내는 단계 기록(키 · 경로)과 그 순서. sync와 연결 해제 판단(unsentLifecycleKeys)이 같이 쓴다.
     * - 출발 뒤(운행 준비가 아님): start. checklist는 서버가 아직 PREPARING일 때만 보내므로 여기 넣지 않는다.
     * - 방문 순서의 정류장: 정차 중인 지금 정류장은 arrive, 지난 정류장(복귀·종료 뒤에는 전부)은 arrive와 complete.
     * - 복귀·종료 뒤 뺀 정류장: skip. 하원 복귀(END_REVIEW·ENDED): return. 종료(ENDED): interior-check, complete.
     */
    fun lifecycleSteps(trip: TripState): List<LifecycleStep> = buildList {
        if (!trip.hasTrip) return@buildList
        if (trip.phase != TripPhase.PREPARING) add(LifecycleStep(START_KEY, "/start"))
        val finished = trip.phase == TripPhase.END_REVIEW || trip.phase == TripPhase.ENDED
        trip.routeOrder.mapNotNull { id -> trip.stops.find { it.id == id } }.forEachIndexed { i, stop ->
            val key = "stop-${stop.id}"
            if (trip.phase == TripPhase.ALIGHTING && trip.currentStop?.id == stop.id) add(LifecycleStep("$key-arrive", "/stops/${stop.id}/arrive"))
            if (trip.currentStopIndex > i || finished) {
                add(LifecycleStep("$key-arrive", "/stops/${stop.id}/arrive"))
                add(LifecycleStep("$key-complete", "/stops/${stop.id}/complete"))
            }
        }
        stopsToSkip(trip).forEach { add(LifecycleStep("stop-$it-skip", "/stops/$it/skip")) }
        // 등원(V-29)은 기관 도착이 종착이라 "복귀 중"(RETURNING)을 보내지 않는다. 서버 /complete는 IN_PROGRESS도 받는다.
        if (finished && !trip.isPickup) add(LifecycleStep("return", "/return"))
        if (trip.phase == TripPhase.ENDED) {
            add(LifecycleStep("interior", "/interior-check", checkedBody = true))
            add(LifecycleStep("complete", "/complete"))
        }
    }.distinctBy { it.key }
    /** 지금 단계에서 보냈어야 하는데 아직 서버에 가지 않은 단계 기록 키. [sent]는 "<운행>:<키>" 전송 표시 조회. */
    fun unsentLifecycleKeys(trip: TripState, sent: (String) -> Boolean): List<String> = lifecycleSteps(trip).map { it.key }.filterNot(sent)

    /** 서버 TripController.event에서 푸시(notifyChildDroppedOff)를 동기로 보내는 이벤트. 묶음이 롤백돼도 푸시는 이미 나가므로 묶음에 넣지 않고 한 건씩 보낸다. */
    val PUSH_EVENT_TYPES = setOf("CHILD_DROPPED_OFF", "CHILD_RECEIVED_BY_ORG")
    /** 서버 TripController.event에서 그 아이의 보호자 호출을 닫는(closeGuardianCallsForChild) 이벤트. */
    val CLOSES_GUARDIAN_CALL_TYPES = setOf("CHILD_BOARDED", "CHILD_NOT_BOARDED", "TEACHER_CONFIRMED_NOT_BOARDED", "GUARDIAN_NO_SHOW")
    fun sendsPushOnServer(type: String): Boolean = type in PUSH_EVENT_TYPES

    /**
     * 미전송 이벤트를 보낼 묶음으로 나눈다(원래 순서 유지).
     * - 푸시를 일으키는 이벤트([PUSH_EVENT_TYPES])는 혼자 한 묶음(단건 전송).
     * - 보호자 호출이 붙은 이벤트 앞에, 같은 묶음 안에 같은 아이의 호출을 닫는 이벤트가 있으면 거기서 끊는다
     *   (묶음은 보호자 호출을 먼저 보내므로, 끊지 않으면 방금 연 호출이 앞 이벤트에 닫힌다).
     * - 한 묶음은 최대 [max]건.
     */
    fun <B> eventSegments(items: List<PendingEvent<B>>, max: Int = EVENT_BATCH_MAX): List<List<PendingEvent<B>>> {
        val out = mutableListOf<List<PendingEvent<B>>>()
        var current = mutableListOf<PendingEvent<B>>()
        fun flush() { if (current.isNotEmpty()) { out += current; current = mutableListOf() } }
        for (item in items) {
            if (sendsPushOnServer(item.type)) { flush(); out += listOf(item); continue }
            val closesBefore = item.guardianCall && item.childId != null &&
                current.any { it.childId == item.childId && it.type in CLOSES_GUARDIAN_CALL_TYPES }
            if (closesBefore || current.size >= max.coerceAtLeast(1)) flush()
            current += item
        }
        flush()
        return out
    }

    /**
     * 미전송 이벤트 전송. 묶음마다 보호자 호출(guardian-calls)을 먼저 개별로 보내고(기존 순서), 1건이면 /events, 여러 건이면 /events/batch.
     * - 보호자 호출이 실패하면 그 앞 이벤트까지만 보내고 실패를 알린다(나머지는 다음 동기화).
     * - batch는 acceptedCount가 보낸 건수와 같을 때만 전송 완료로 표시한다([batchAcceptedKeys]). 아니면 표시하지 않고 실패를 알린다.
     * - batch가 HTTP 오류로 거절되면(서버는 묶음 전체를 롤백) 한 건씩 다시 보낸다. 401·403은 그대로 알린다([fallBackToSingleEvents]).
     *   묶음에는 푸시 이벤트가 없으므로 다시 보내도 푸시가 두 번 가지 않는다.
     */
    fun <B> sendPendingEvents(items: List<PendingEvent<B>>, transport: EventTransport<B>) {
        for (segment in eventSegments(items)) {
            var ready = segment.size
            var callFailure: Exception? = null
            for ((index, item) in segment.withIndex()) {
                if (!item.guardianCall) continue
                try { transport.guardianCall(item) } catch (e: Exception) { ready = index; callFailure = e; break }
            }
            val sendable = segment.take(ready)
            fun oneByOne() { for (item in sendable) { transport.single(item); transport.markSent(listOf(item.key)) } }
            if (sendable.size == 1) oneByOne()
            else if (sendable.size > 1) {
                val acceptedCount = try { transport.batch(sendable) } catch (e: com.hanhyo.shuttle.net.ApiFailure) {
                    if (!fallBackToSingleEvents(e.status)) throw e
                    oneByOne(); null
                }
                // 대체 경로(oneByOne)는 이미 건별로 표시했고 acceptedCount는 null이다.
                if (acceptedCount != null) {
                    val accepted = batchAcceptedKeys(sendable.map { it.key }, acceptedCount)
                    if (accepted.isEmpty()) error("이벤트 묶음 응답을 확인하지 못했습니다 · 다음 동기화 때 다시 보냅니다.")
                    transport.markSent(accepted)
                }
            }
            callFailure?.let { throw it }
        }
    }

    /**
     * 이벤트 묶음 크기. 서버 events/batch는 건수 제한이 없지만, 묶음 하나가 한 트랜잭션이라 실패하면 전체를 다시 보내야 하므로
     * 요청 크기와 재전송량을 줄이려고 50건씩 나눈다.
     */
    const val EVENT_BATCH_MAX = 50
    /**
     * events/batch 응답 해석. 서버는 묶음 전체를 한 트랜잭션으로 처리하고(실패하면 전체 롤백 · 오류 응답), 성공하면
     * 처리한 건수(acceptedCount, 중복 clientEventId 포함)만 돌려준다. 그래서 acceptedCount가 보낸 건수와 같을 때만
     * 모두 전송 완료로 표시하고, 다르거나 없으면 아무것도 표시하지 않는다(다음 동기화 때 다시 보냄 · clientEventId로 멱등).
     */
    fun batchAcceptedKeys(sentKeys: List<String>, acceptedCount: Int?): List<String> =
        if (acceptedCount != null && acceptedCount == sentKeys.size) sentKeys else emptyList()
    /**
     * 묶음이 HTTP 오류로 거절됐을 때 한 건씩 다시 보낼지. 특정 항목 때문에 거절된 요청 오류(4xx)만 한 건씩 보낸다.
     * 인증·권한 오류(401·403)는 한 건씩 보내도 같고, 서버 오류(5xx)는 부하만 키우므로 다음 동기화로 미룬다.
     */
    fun fallBackToSingleEvents(status: Int): Boolean = status in 400..499 && status != 401 && status != 403

    /** 서버 vehicle_devices.pleos_sdk_version 컬럼 길이(varchar(20)). 서버 pair는 길이를 검사하지 않아 넘으면 저장이 실패한다. */
    const val PLEOS_SDK_VERSION_MAX = 20
    /**
     * 페어링 때 보내는 Pleos SDK 버전. "Vehicle 2.3.2; NaviHelper 3.0.5; TextToSpeech 3.3.1"은 51자라 컬럼(20자)에 맞지 않아
     * 머리글자로 줄인 "V2.3.2;N3.0.5;T3.3.1"(20자)을 보낸다. 버전이 길어져 넘치면 TTS → Navi 순으로 빼고, 그래도 길면 자른다.
     */
    fun pleosSdkVersion(vehicle: String, navi: String, tts: String): String =
        listOf("V$vehicle;N$navi;T$tts", "V$vehicle;N$navi", "V$vehicle").firstOrNull { it.length <= PLEOS_SDK_VERSION_MAX }
            ?: "V$vehicle".take(PLEOS_SDK_VERSION_MAX)

    /** 같은 예상 시간 관측(운행·관측 시각·항목이 같은 키)은 다시 보내지 않는다. */
    fun shouldSendEta(key: String, lastSentKey: String?): Boolean = key != lastSentKey
    /** 보낸 키는 전송이 성공했을 때만 바꾼다(실패하면 다음 주기에 같은 관측을 다시 보낸다). */
    fun nextEtaKey(lastSentKey: String?, key: String, outcome: EtaReport): String? = if (outcome == EtaReport.SENT) key else lastSentKey
    /** 위치 서비스(TripTrackingService) 상태 문구. 위치를 보냈는데 예상 시간만 실패했으면 연결 끊김이 아니라 그 사실만 알린다. */
    fun trackingServiceStatus(fixPresent: Boolean, report: TrackingReport): String = when {
        !fixPresent || !report.locationSent -> "Pleos 위치 수신 대기"
        report.eta == EtaReport.FAILED -> "Pleos 내비 위치 전송 중 · 예상 시간 전송 실패(다음 주기에 다시 보냄)"
        else -> "Pleos 내비 위치 전송 중 · 내비 화면에서도 유지"
    }

    /** POST /api/v1/vehicle/trips/{id}/eta-snapshots 본문(VehicleTelemetryController.eta가 읽는 키). */
    fun etaBody(eta: EtaSnapshot): JSONObject = JSONObject()
        .put("routeRevisionNo", eta.routeRevisionNo)
        .apply { eta.navRouteKey?.let { put("navRouteKey", it) } }
        .put("sourceTime", Instant.ofEpochMilli(eta.sourceTime).toString())
        .put("validationStatus", "VALID")
        .apply { if (eta.simulated) put("validationNote", "SIMULATED") }
        .put("entries", JSONArray().apply {
            eta.entries.forEach { e ->
                put(JSONObject().put("bundleIndex", e.bundleIndex).put("pointType", e.pointType)
                    .apply { e.tripStopId?.let { put("tripStopId", it) } }
                    .put("latitude", e.latitude).put("longitude", e.longitude)
                    .put("remainingSec", e.remainingSec).put("remainingM", e.remainingM))
            }
        })

    /** 진행 중(운행 중·복귀 중) 먼저, 그다음 최근에 만든 운행(id 큰 것) 먼저. */
    fun orderOptions(options: List<TripOption>): List<TripOption> =
        options.sortedWith(compareBy<TripOption>({ !it.inProgress }, { -(it.id.toLongOrNull() ?: 0L) }))
    /** 예: "중앙로역 → … → 동대구역 · 정류장 4곳 · 탈 어린이 5명 · 준비" */
    fun optionSummary(o: TripOption): String {
        val route = when (o.stopNames.size) {
            0 -> "정류장 정보 없음"
            1 -> o.stopNames.first()
            2 -> o.stopNames.joinToString(" → ")
            else -> "${o.stopNames.first()} → … → ${o.stopNames.last()}"
        }
        val state = when (o.status) { "IN_PROGRESS" -> "운행 중"; "RETURNING" -> "복귀 중"; else -> "준비" }
        return "$route · 정류장 ${o.stopNames.size}곳 · 탈 어린이 ${o.expectedCount}명 · $state"
    }
    /**
     * 운행의 기관(하원 출발·복귀지, 등원 도착지) 위치. 백엔드 현재 형식은 최상위 `orgName`·`orgLatitude`·`orgLongitude`(TripController.getTrip),
     * 이전에 제안했던 `org { name, latitude, longitude }` 묶음도 읽는다(서버 형식이 바뀌어도 깨지지 않게). 둘 다 있으면 최상위 값 우선.
     * 좌표가 없거나 잘못되면 null(임의 위치로 대신하지 않는다).
     */
    fun institutionOrigin(trip: JSONObject): Stop? {
        val org = trip.optJSONObject("org")
        return institutionOriginFrom({ k -> trip.opt(k).takeUnless { it == JSONObject.NULL } },
            org?.let { o -> { k: String -> o.opt(k).takeUnless { it == JSONObject.NULL } } })
    }
    /** JSON 없이 시험할 수 있게 값 조회만 받는다. [top]은 운행 응답 최상위, [nested]는 `org` 묶음(없으면 null). */
    fun institutionOriginFrom(top: (String) -> Any?, nested: ((String) -> Any?)?): Stop? {
        fun num(v: Any?) = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { !it.isNaN() }
        fun str(v: Any?) = (v as? String)?.takeIf { it.isNotBlank() }
        val flatLat = num(top("orgLatitude")); val flatLng = num(top("orgLongitude"))
        return if (flatLat != null && flatLng != null) institutionOrigin(str(top("orgName")), flatLat, flatLng)
            else institutionOrigin(nested?.let { str(it("name")) } ?: str(top("orgName")), nested?.let { num(it("latitude")) },
                nested?.let { num(it("longitude")) })
    }
    fun institutionOrigin(orgName: String?, latitude: Double?, longitude: Double?): Stop? =
        if (latitude != null && longitude != null && validCoordinate(latitude, longitude))
            Stop("origin", orgName?.takeIf { it.isNotBlank() } ?: "기관", latitude, longitude)
        else null
    fun validCoordinate(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    /** Server trip statuses that are still operable: PREPARING / IN_PROGRESS / RETURNING. COMPLETED·CANCELLED are finished. */
    val ACTIVE_TRIP_STATUSES = setOf("PREPARING", "IN_PROGRESS", "RETURNING")

    fun directionLabel(direction: String) = TripDirections.label(direction)

    /** VS-02 방향: 저장된 선택이 있으면 그 값, 없으면 시간대 기본값(D25·OQ-26 제안안). 선생님 선택은 유지한다. */
    fun effectiveDirection(stored: String?, hourOfDay: Int): String =
        if (stored.isNullOrBlank()) TripDirections.defaultFor(hourOfDay) else TripDirections.of(stored)

    /** 운행 준비 버튼을 눌렀을 때: 바로 진행 / 5초 동기화가 끝난 뒤 이어서 진행 / 거절. */
    enum class PrepareStart { NOW, AFTER_SYNC, BLOCKED }

    /**
     * [allowedNow]: 지금 조건으로 준비 가능. [allowedAfterSync]: 동기화 중이라는 것(과 그 동기화로 보낼 미전송 기록)만 빼면 가능.
     * 동기화 중에 누른 것만으로 거절하면 안내가 목록 아래에 떠서 "눌러도 반응 없음"처럼 보였다. 이때는 기다렸다가 이어서 한다.
     */
    fun prepareStart(allowedNow: Boolean, allowedAfterSync: Boolean, syncing: Boolean): PrepareStart = when {
        allowedNow -> PrepareStart.NOW
        syncing && allowedAfterSync -> PrepareStart.AFTER_SYNC
        else -> PrepareStart.BLOCKED
    }

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
    /** 기사 확인을 실제로 체크한 시각(ISO-8601 UTC). 서버는 이 값을 driver_checked_at으로 저장(A6). 체크 안 했으면 null. */
    fun checkedAt(trip: TripState): String? = trip.driverCheckedAtMillis?.let { java.time.Instant.ofEpochMilli(it).toString() }
    /** 운행 응답 children[].guardianCall → tripChildId별 정보. 호출이 없는 아이는 빠진다. */
    fun guardianCalls(trip: JSONObject): Map<String, GuardianCallInfo> {
        val rows = trip.optJSONArray("children") ?: return emptyMap()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val c = row.optJSONObject("guardianCall") ?: return@mapNotNull null
            fun s(k: String) = if (c.isNull(k)) null else c.optString(k).takeIf { it.isNotBlank() }
            row.optString("tripChildId") to GuardianCallInfo(s("callId") ?: "", s("status") ?: "OPEN", s("response"),
                ServerTime.parseMillis(s("openedAt")), ServerTime.parseMillis(s("respondedAt")))
        }.toMap()
    }

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
