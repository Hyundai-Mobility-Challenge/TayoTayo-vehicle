package com.hanhyo.pleosshuttle.sdk

/** One SDK Area may contain several members. Keep the original object and its exact scope. */
internal data class ExactSupportedArea<T>(val key: String, val original: T, val members: Set<String>)

internal fun <T> exactSupportedAreas(areas: Iterable<T>, members: (T) -> Iterable<String>): List<ExactSupportedArea<T>> =
    areas.map { area ->
        val names = members(area).toSortedSet()
        ExactSupportedArea(names.joinToString("+"), area, names)
    }.filter { it.key.isNotEmpty() }.distinctBy { it.key }

internal fun <T> selectExactArea(areas: List<ExactSupportedArea<T>>, member: String): ExactSupportedArea<T>? =
    areas.firstOrNull { it.members == setOf(member) } ?: areas.firstOrNull { member in it.members }
