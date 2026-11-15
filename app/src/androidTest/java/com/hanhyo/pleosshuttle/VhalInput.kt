package com.hanhyo.pleosshuttle

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * 기기 테스트용 차량 관측 입력(속도·기어·주차 브레이크). 앱 코드에는 모의 입력 경로가 없으므로 에뮬레이터 VHAL 값을 바꾼다.
 *
 * 앱은 값을 getProperty로 폴링한다(RealVehicleAdapter.property, DriveStateObserver). 그래서 **저장값을 바꾸는 방식**을 먼저 쓴다.
 * 1. AIDL fake VHAL: `dumpsys android.hardware.automotive.vehicle.IVehicle/default --set <prop> -i|-f <value>`
 * 2. HIDL fake VHAL: `dumpsys android.hardware.automotive.vehicle@2.0::IVehicle/default --set <prop> -i|-f <value>`
 * 3. 둘 다 실패할 때만 `cmd car_service inject-vhal-event <prop> 0 <value>`(구독 이벤트만 보낼 수 있어 폴링에 안 보일 수 있음)
 * 명령 출력에 오류·미지원·권한 문구가 있으면 그 방식은 실패로 보고 다음 방식을 쓴다. 명령이 통과해도 앱이 실제로 읽었는지는
 * 테스트가 따로 확인한다(SdkTripFlowDeviceTest.awaitVehicle). user 빌드 AVD처럼 모두 거부되면 [method]가 null이다.
 *
 * PERF_VEHICLE_SPEED는 연속 속성이라 앱이 5초 넘은 sample을 오래된 값으로 버린다. 그래서 값을 1초마다 다시 넣는다.
 */
object VhalInput {
    private const val PERF_VEHICLE_SPEED = 291504647 // 0x11600207, float m/s
    private const val GEAR_SELECTION = 289408000 // 0x11400400, int
    private const val PARKING_BRAKE_ON = 287310850 // 0x11200402, boolean
    const val GEAR_PARK = 4
    const val GEAR_DRIVE = 8

    private data class Value(val property: Int, val float: Boolean, val value: String)

    enum class Method(val label: String) {
        AIDL_DUMPSYS_SET("AIDL fake VHAL dumpsys --set"),
        HIDL_DUMPSYS_SET("HIDL fake VHAL dumpsys --set"),
        INJECT_EVENT("cmd car_service inject-vhal-event"),
    }

    /** 마지막으로 성공한 방식. null이면 아직 쓰지 않았거나 모든 방식이 거부됐다. */
    @Volatile var method: Method? = null; private set
    /** 진단용: 마지막 명령들의 출력. */
    @Volatile var lastOutput: String = ""; private set

    @Volatile private var held: List<Value> = emptyList()
    @Volatile private var holder: Thread? = null

    /** 정차: P 기어 · 속도 0 · 주차 브레이크 체결. 성공한 방식(없으면 null)을 돌려준다. */
    fun park(): Method? = hold(listOf(Value(PARKING_BRAKE_ON, false, "1"), Value(GEAR_SELECTION, false, "$GEAR_PARK"), Value(PERF_VEHICLE_SPEED, true, "0")))

    /** 주행: D 기어 · [metersPerSecond] · 주차 브레이크 해제. */
    fun drive(metersPerSecond: Float = 5f): Method? =
        hold(listOf(Value(PARKING_BRAKE_ON, false, "0"), Value(GEAR_SELECTION, false, "$GEAR_DRIVE"), Value(PERF_VEHICLE_SPEED, true, "$metersPerSecond")))

    /** 값을 유지하는 스레드를 멈춘다. VHAL 값 자체는 마지막 입력으로 남는다. */
    fun release() { holder?.interrupt(); holder = null; held = emptyList() }

    private fun hold(values: List<Value>): Method? {
        held = values
        val chosen = applyAll(values)
        if (holder == null) holder = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1_000)
                    val current = held
                    val m = method
                    if (current.isNotEmpty()) { if (m != null) current.forEach { run(m, it) } else applyAll(current) }
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; name = "vhal-input-hold"; start() }
        return chosen
    }

    /** 이미 성공한 방식을 먼저, 그다음 우선순위대로 시도해 모든 값이 오류 없이 들어간 첫 방식을 고른다. */
    @Synchronized private fun applyAll(values: List<Value>): Method? {
        val order = (listOfNotNull(method) + Method.values()).distinct()
        val outputs = StringBuilder()
        for (m in order) {
            val ok = values.all { v -> val out = run(m, v); outputs.append("[${m.name}] ").append(out.trim()).append('\n'); !failed(out) }
            if (ok) { method = m; lastOutput = outputs.toString(); return m }
        }
        method = null; lastOutput = outputs.toString()
        return null
    }

    private fun run(m: Method, v: Value): String {
        val type = if (v.float) "-f" else "-i"
        return shell(when (m) {
            Method.AIDL_DUMPSYS_SET -> "dumpsys android.hardware.automotive.vehicle.IVehicle/default --set ${v.property} $type ${v.value}"
            Method.HIDL_DUMPSYS_SET -> "dumpsys android.hardware.automotive.vehicle@2.0::IVehicle/default --set ${v.property} $type ${v.value}"
            Method.INJECT_EVENT -> "cmd car_service inject-vhal-event ${v.property} 0 ${v.value}"
        })
    }

    /** 명령 출력에 오류·미지원·권한·사용법 문구가 있으면 실패. */
    internal fun failed(output: String): Boolean {
        val text = output.lowercase()
        return FAILURE_MARKERS.any { it in text }
    }
    private val FAILURE_MARKERS = listOf("error", "fail", "not supported", "unsupported", "unknown", "permission", "denied",
        "not found", "can't find", "cannot", "invalid", "usage:", "exception", "no such", "not allowed")

    private fun shell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { descriptor.close() }
    }.getOrElse { "exception: ${it.javaClass.simpleName}: ${it.message}" }
}
