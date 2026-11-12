package com.hanhyo.pleosshuttle

/** Native timing; normalized waypoint coordinates explicitly retain their registered-stop provenance. */
data class TrackingNavigation(
    val state: String, val epoch: Long, val routeId: String? = null,
    val latitude: Double? = null, val longitude: Double? = null,
    val remainSeconds: Int? = null, val remainMeters: Int? = null, val receivedAt: Long? = null,
    val stops: List<NavigationStopEta> = emptyList()
)

data class NavigationStopEta(val latitude: Double, val longitude: Double, val seconds: Int, val meters: Int, val receivedAt: Long,
    val observedName: String? = null, val coordinateSource: String = "PLEOS")

class NavigationTracking(private val clock: () -> Long = System::currentTimeMillis) {
    private var epoch = 0L
    private var guiding = false
    private var simulated = false
    private var recalculating = false
    private var target: Target? = null
    private var routeId: String? = null
    private var expected: List<Pair<Double,Double>> = emptyList()
    private var expectedNames: List<String> = emptyList()
    private var waypoints: List<Target>? = null
    fun expectStops(points: List<Pair<Double,Double>>, names: List<String> = emptyList()) {
        require(points.size <= 3 && (names.isEmpty() || names.size==points.size))
        if (expected != points || expectedNames != names) {
            expected = points.toList(); expectedNames=names.toList(); invalidate(false)
        }
    }
    fun waypoints(points: List<NavigationStopEta>) {
        if (!guiding || recalculating) return
        waypoints = points.takeIf { it.size <= 2 && it.all { p -> valid(p.latitude,p.longitude,p.seconds,p.meters) } }
            ?.map { Target(it.latitude,it.longitude,it.seconds,it.meters,clock(),it.observedName) }
    }
    private fun valid(lat:Double,lon:Double,seconds:Int,meters:Int) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            seconds in 0..86400 && meters in 0..10_000_000
    var location: TrackingLocation? = null; private set
    private data class Target(val lat: Double, val lon: Double, val seconds: Int, val meters: Int, val at: Long, val name: String? = null)

    fun invalidate(recalculating: Boolean = true) {
        epoch++; target = null; waypoints = null; location = null; routeId = null
        this.recalculating = recalculating
    }
    fun guidance(active: Boolean, simulation: Boolean) {
        if (guiding != active || simulated != simulation) invalidate(if (active) recalculating else false)
        guiding = active; simulated = simulation
    }
    fun completed() = invalidate(false)
    /** Native callbacks differ across service builds; a fresh non-routing guidance state also settles a search.
     * Clear all pre-settlement samples; only subsequent location/destination/waypoint replies may become ready. */
    fun routeState(active:Boolean, simulation:Boolean, calculating:Boolean) {
        guidance(active,simulation)
        if(calculating && !recalculating) invalidate()
        else if(active && !calculating && recalculating) completed()
    }
    fun position(lat: Double, lon: Double, id: String?, index: Int?) {
        if (!guiding) return
        if (routeId != null && routeId != id) {
            target = null; waypoints = null; epoch++ // A changed native route cannot reuse an old destination ETA.
        }
        routeId = id?.takeIf { it.isNotBlank() && it.length <= 200 }
        location = TrackingLocation(lat, lon, clock(), simulated, routeId, index?.takeIf { it >= 0 })
            .takeIf { it.valid(clock()) }
    }
    fun destination(lat: Double, lon: Double, seconds: Int, meters: Int, name: String? = null) {
        if (!guiding || recalculating) return
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            seconds !in 0..86400 || meters !in 0..10_000_000) { target = null; return }
        target?.let { old ->
            if (old.lat != lat || old.lon != lon || old.name != name) { location = null; waypoints = null; routeId = null; epoch++ }
        }
        target = Target(lat, lon, seconds, meters, clock(),name)
    }
    internal fun diagnostic(): Map<String,Any?> = mapOf(
        "guiding" to guiding,"recalculating" to recalculating,"expectedCount" to expected.size,
        "waypointCount" to waypoints?.size,"destinationPresent" to (target!=null),
        "locationValid" to (location?.valid(clock())==true),"routeIdPresent" to !routeId.isNullOrEmpty(),
        "destinationAgeMs" to target?.let {clock()-it.at},
        "waypointAgesMs" to waypoints?.map {clock()-it.at},
        "waypointOrderMatches" to waypoints?.mapIndexed { i,p -> expected.getOrNull(i)?.let { kotlin.math.abs(p.lat-it.first)<0.00001 && kotlin.math.abs(p.lon-it.second)<0.00001 } },
        "waypointDegreeDeltas" to waypoints?.mapIndexed {i,p -> expected.getOrNull(i)?.let {listOf(p.lat-it.first,p.lon-it.second)}},
        "waypointCrossMatches" to waypoints?.map {p -> expected.map { kotlin.math.abs(p.lat-it.first)<0.00001 && kotlin.math.abs(p.lon-it.second)<0.00001 }},
        "destinationMatches" to target?.let {p -> expected.lastOrNull()?.let {kotlin.math.abs(p.lat-it.first)<0.00001 && kotlin.math.abs(p.lon-it.second)<0.00001}},
        "observedSeconds" to ((waypoints?:emptyList())+listOfNotNull(target)).map {it.seconds})
    fun snapshot(): TrackingNavigation {
        val dest = target
        val chain = if (expected.isNotEmpty()) waypoints?.plus(listOfNotNull(dest)) else listOfNotNull(dest)
        fun exact(got:Target,wanted:Pair<Double,Double>) =
            kotlin.math.abs(got.lat-wanted.first)<0.00001 && kotlin.math.abs(got.lon-wanted.second)<0.00001
        // No non-zero wrong coordinate is ever replaced. Missing (0,0) waypoint coordinates may use
        // the registered stop only with the WHOLE unique name/order chain and exact final destination.
        val namesMatch = expectedNames.size==expected.size && expectedNames.isNotEmpty() &&
            expectedNames.all { it.isNotBlank() } && expectedNames.distinct().size==expectedNames.size &&
            chain?.size==expectedNames.size && chain.map { it.name }==expectedNames
        val finalMatches = dest!=null && expected.lastOrNull()?.let {exact(dest,it)}==true
        val matched = expected.isEmpty() || (chain?.size == expected.size && chain.withIndex().all { (i,got) ->
            val nameMatches=expectedNames.isEmpty() || got.name==expectedNames[i]
            nameMatches && (exact(got,expected[i]) ||
                (i<expected.lastIndex && got.lat==0.0 && got.lon==0.0 && namesMatch && finalMatches))
        })
        val ready = guiding && !recalculating && location?.valid(clock()) == true &&
            !routeId.isNullOrEmpty() && dest != null && matched && !chain.isNullOrEmpty() &&
            chain.all { clock()-it.at in 0..30_000 } && chain.zipWithNext().all { (a,b) -> a.seconds<=b.seconds }
        if (!ready) return TrackingNavigation(if(recalculating) "REROUTING" else "UNAVAILABLE",epoch)
        val normalized=chain!!.mapIndexed {i,p ->
            val registered=expected.isNotEmpty() && !exact(p,expected[i])
            NavigationStopEta(if(registered)expected[i].first else p.lat,if(registered)expected[i].second else p.lon,
                p.seconds,p.meters,p.at,p.name,if(registered)"REGISTERED_STOP" else "PLEOS")
        }
        val first = normalized.first()
        return TrackingNavigation("READY",epoch,routeId,first.latitude,first.longitude,first.seconds,first.meters,first.receivedAt,
            if(expected.isEmpty()) emptyList() else normalized)
    }
}

/** Foreground state controls START only; an opted-in active trip survives opening native navigation. */
object TrackingLifecycle {
    fun desired(enabled: Boolean, connected: Boolean, bound: Boolean, sdk: Boolean, active: Boolean) =
        enabled && connected && bound && sdk && active
    fun mayStart(desired: Boolean, visible: Boolean, permission: Boolean) = desired && visible && permission
}
