package com.hanhyo.pleosshuttle

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

class IsolatedTripRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val backups = listOf("trip-v1.bin", "trip-v1.bin.bak", "trip-v1.bin.new").associateWith { name ->
                File(context.filesDir, name).takeIf { it.exists() }?.readBytes()
            }
            try {
                backups.keys.forEach { name -> File(context.filesDir, name).let { check(!it.exists() || it.delete()) } }
                base.evaluate()
            } finally {
                instrumentation.waitForIdleSync()
                backups.forEach { (name, bytes) ->
                    val file = File(context.filesDir, name)
                    if(bytes == null) check(!file.exists() || file.delete()) else file.writeBytes(bytes)
                }
            }
        }
    }
}
