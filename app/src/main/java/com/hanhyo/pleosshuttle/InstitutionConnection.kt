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
        if (rows.length() == 0) error("오늘 차량에 배정된 등원 운행이 없습니다. 기관에서 운행을 먼저 생성해 주세요.")
        val tripId = rows.getJSONObject(0).optString("id")
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
            ChildRecord(row.optString("tripChildId"), row.optString("childName", "어린이"), stopId, "미배정",
                when (row.optString("status")) { "BOARDED" -> Attendance.ONBOARD; "NOT_BOARDED" -> Attendance.NOT_BOARDED; "DROPPED_OFF" -> Attendance.ALIGHTED; else -> Attendance.NOT_BOARDED })
        }
        if (stops.isEmpty()) error("운행에 등록된 정류장이 없습니다.")
        check(prefs.edit().putString("pendingTrip", tripId).commit())
        val trip = TripState(tripId = tripId, integrationMode = IntegrationMode.SDK, origin = sample.origin,
            stops = stops, routeOrder = stops.map { it.id }, routeIsExample = false, children = children)
        bind(trip)
        return trip
    }

    fun bind(trip: TripState) {
        check(prefs.edit().putString("trip", trip.tripId).remove("pendingTrip").putStringSet("sent", emptySet()).commit())
    }
    fun recover(trip: TripState) { if (prefs.getString("pendingTrip", null) == trip.tripId) bind(trip) }
    fun pending(trip: TripState): Int = if (trip.tripId != boundTrip) 0 else trip.ledger.count { it.id !in prefs.getStringSet("sent", emptySet()).orEmpty() }

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
            lifecycle("checklist", "/checklist", JSONObject().put("driverConfirmed", false), "PUT")
            if (trip.phase != TripPhase.PREPARING) lifecycle("start", "/start")
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

        val sentEvents = prefs.getStringSet("sentEvents", emptySet()).orEmpty().toMutableSet()
        val stopByChild = trip.children.associate { it.id to it.stopId }
        val ledgerRows = trip.ledger.map { entry ->
            val eventType = when (entry.after) { Attendance.ONBOARD -> "CHILD_BOARDED"; Attendance.NOT_BOARDED -> "CHILD_NOT_BOARDED"; Attendance.ALIGHTED -> "CHILD_DROPPED_OFF" }
            Triple(entry.id, eventType, JSONObject().put("tripChildId", entry.childId).put("tripStopId", stopByChild[entry.childId]))
        }
        val handovers = trip.handoverChildIds.map { id -> Triple("handover-$id", "CHILD_HANDED_OVER", JSONObject().put("tripChildId", id).put("tripStopId", stopByChild[id])) }
        for ((key, type, body) in ledgerRows + handovers) {
            if (key in sentEvents) continue
            body.put("clientEventId", UUID.nameUUIDFromBytes("${trip.tripId}:$key".toByteArray()).toString())
                .put("eventType", type).put("occurredAt", Instant.now().toString())
            if (type == "CHILD_NOT_BOARDED") body.put("reason", "OTHER")
            call("$prefix/events", body)
            sentEvents.add(key); check(prefs.edit().putStringSet("sentEvents", sentEvents).commit()); count++
        }
        return count
    }
}
