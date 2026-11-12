package com.hanhyo.pleosshuttle

/** SDK callback receipt time, NOT a guaranteed GNSS measurement timestamp. */
data class TrackingLocation(val latitude: Double, val longitude: Double, val receivedAt: Long, val simulated: Boolean, val routeId: String? = null, val routeIndex: Int? = null) {
    fun valid(now: Long) = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0) && now - receivedAt in 0..30_000
}
