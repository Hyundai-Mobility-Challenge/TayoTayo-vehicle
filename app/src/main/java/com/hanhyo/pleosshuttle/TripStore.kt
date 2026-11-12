package com.hanhyo.pleosshuttle

import android.content.Context
import android.util.AtomicFile
import com.hanhyo.pleosshuttle.domain.TripState
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** Private, local-only checkpoint. Failed reads are never silently replaced by a fresh trip. */
class TripStore(context: Context) {
    companion object { private val ioLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "trip-v1.bin"))
    private val archive = File(context.filesDir, "completed-trips")
    fun load(): TripState? = synchronized(ioLock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return ObjectInputStream(file.openRead()).use { input -> input.readObject() as TripState }
    }
    fun archiveEnded(state: TripState) {
        require(state.phase == com.hanhyo.pleosshuttle.domain.TripPhase.ENDED)
        check(archive.exists() || archive.mkdirs())
        val target = AtomicFile(File(archive, "${state.tripId}.bin"))
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
            writeAtomic(target, state)
        }
        val restored = ObjectInputStream(target.openRead()).use { it.readObject() as TripState }
        // Recovery adds diagnostics and clears transient confirmations. Those changes must not
        // prevent retrying an archive committed before the next active checkpoint was saved.
        check(restored.tripId == state.tripId && restored.phase == state.phase &&
            restored.endRecord == state.endRecord && restored.children == state.children &&
            restored.ledger == state.ledger && restored.origin == state.origin &&
            restored.stops == state.stops && restored.routeOrder == state.routeOrder &&
            restored.currentStopIndex == state.currentStopIndex &&
            restored.integrationMode == state.integrationMode &&
            restored.routeObjective == state.routeObjective && restored.returnToOrigin == state.returnToOrigin
        ) { "보관된 완료 기록이 현재 완료 기록과 다릅니다. 기존 보관 파일을 유지합니다." }
    }
    fun archiveSnapshot(state: TripState) {
        check(archive.exists() || archive.mkdirs())
        val target = AtomicFile(File(archive, "snapshot-${java.util.UUID.randomUUID()}.bin"))
        writeAtomic(target, state)
        check(ObjectInputStream(target.openRead()).use { it.readObject() as TripState } == state)
    }
    fun savedTrips(): List<TripState> = archive.listFiles().orEmpty()
        .filter { it.extension == "bin" }.sortedByDescending { it.lastModified() }
        .map { ObjectInputStream(it.inputStream()).use { input -> input.readObject() as TripState } }

    fun save(state: TripState) = writeAtomic(file, state)

    private fun writeAtomic(target: AtomicFile, state: TripState) = synchronized(ioLock) {
        val output = target.startWrite()
        try {
            val stream = ObjectOutputStream(output)
            stream.writeObject(state)
            stream.flush()
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }
}
