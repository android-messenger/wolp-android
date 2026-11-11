package org.wolpmessenger.web

import com.getcapacitor.annotation.CapacitorPlugin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.wolpmessenger.plugins.AppSettingsPlugin
import org.wolpmessenger.plugins.CallPlugin
import org.wolpmessenger.plugins.DeviceTrustPlugin
import org.wolpmessenger.plugins.FilesPlugin
import org.wolpmessenger.plugins.PushPlugin

/**
 * Every plugin checks [CallOrigins.allowed], which only finds calls the guard recorded — and the guard
 * records only plugins in [BridgeGuard.GUARDED]. A plugin missing there refuses every call (AppSettings
 * in 9995). Add new plugins (MainActivity.registerPlugin) here too.
 */
class BridgeGuardTest {
    @Test fun `every registered plugin is guarded`() {
        val names = listOf(
            DeviceTrustPlugin::class.java, FilesPlugin::class.java, PushPlugin::class.java,
            CallPlugin::class.java, AppSettingsPlugin::class.java,
        ).map { it.getAnnotation(CapacitorPlugin::class.java)!!.name }.toSet()
        assertEquals(names, BridgeGuard.GUARDED)
    }
}
