package com.hanhyo.pleosshuttle.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExactSupportedAreasTest {
    private class Area(val members: List<String>)

    @Test fun `여러 문이 묶인 SDK Area를 개별 문으로 나누거나 재생성하지 않는다`() {
        val original = Area(listOf("ROW_2_RIGHT", "ROW_1_LEFT", "ROW_2_LEFT", "ROW_1_RIGHT"))
        val supported = exactSupportedAreas(listOf(original)) { it.members }
        val selected = selectExactArea(supported, "ROW_2_RIGHT")!!

        assertEquals(1, supported.size)
        assertSame(original, selected.original)
        assertEquals("ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT", selected.key)
        assertEquals(4, selected.members.size)
    }

    @Test fun `개별 Area가 실제로 제공될 때만 해당 개별 문을 우선 선택한다`() {
        val group = Area(listOf("ROW_2_LEFT", "ROW_2_RIGHT"))
        val single = Area(listOf("ROW_2_RIGHT"))
        val supported = exactSupportedAreas(listOf(group, single)) { it.members }

        assertEquals(2, supported.size)
        assertSame(single, selectExactArea(supported, "ROW_2_RIGHT")!!.original)
    }

    @Test fun `대상 문이 포함되지 않은 SDK Area로 대상 문을 만들어내지 않는다`() {
        val supported = exactSupportedAreas(listOf(Area(listOf("ROW_1_LEFT", "ROW_1_RIGHT")))) { it.members }
        assertNull(selectExactArea(supported, "ROW_2_RIGHT"))
    }

    @Test fun `좌석과 벨트도 SDK가 반환한 각 복합 Area 원본을 유지한다`() {
        val front = Area(listOf("ROW_1_LEFT", "ROW_1_RIGHT"))
        val rear = Area(listOf("ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT"))
        val supported = exactSupportedAreas(listOf(front, rear)) { it.members }

        assertEquals(listOf("ROW_1_LEFT+ROW_1_RIGHT", "ROW_2_CENTER+ROW_2_LEFT+ROW_2_RIGHT"), supported.map { it.key })
        assertSame(front, supported[0].original)
        assertSame(rear, supported[1].original)
    }
}
