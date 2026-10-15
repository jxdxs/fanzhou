package com.kevin.fanzhou

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kevin.fanzhou.data.ConfigSync
import com.kevin.fanzhou.data.Repo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class SyncTest {
    @Test fun roundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = ctx.getSharedPreferences("fanzhou", Context.MODE_PRIVATE)
        val pos = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE)
        p.edit().putString("sources", "[{\"id\":\"a\",\"name\":\"x\",\"type\":\"jellyfin\",\"url\":\"u\"}]").putFloat("speed", 1.5f).putInt("seek_step", 15).putBoolean("auto_next", false).putString("device_id", "dev1").commit()
        pos.edit().putLong("k1", 1000L).commit()
        val json = ConfigSync.export()
        println(json)
        p.edit().clear().putString("device_id", "dev2").putFloat("speed", 1f).commit()
        pos.edit().clear().putLong("k2", 2000L).commit()
        ConfigSync.import(json)
        assertEquals(1.5f, p.getFloat("speed", 0f))
        assertEquals(15, p.getInt("seek_step", 0))
        assertEquals(false, p.getBoolean("auto_next", true))
        assertEquals("dev2", p.getString("device_id", null))
        assertEquals(1000L, pos.getLong("k1", 0)); assertEquals(2000L, pos.getLong("k2", 0))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, Repo.store.sources.value.size)
    }

    @Test fun encryptedRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = ctx.getSharedPreferences("fanzhou", Context.MODE_PRIVATE)
        p.edit().putString("sources", "[{\"id\":\"b\",\"name\":\"y\",\"type\":\"115\",\"cookie\":\"secret_cookie\"}]").putInt("seek_step", 20).commit()

        val encJson = ConfigSync.export(password = "pass1234", profile = "张三")
        val info = ConfigSync.inspect(encJson)
        assertEquals(true, info.isEncrypted)
        assertEquals("张三", info.profile)

        // Reset prefs
        p.edit().clear().putInt("seek_step", 5).commit()

        // Wrong password should fail
        var failed = false
        try {
            ConfigSync.import(encJson, password = "wrongPassword")
        } catch (e: Exception) {
            failed = true
        }
        assertEquals(true, failed)

        // Correct password should succeed
        ConfigSync.import(encJson, password = "pass1234")
        assertEquals(20, p.getInt("seek_step", 0))
    }

    @Test fun cloudRecentAddAndRemove() {
        val s = Repo.store
        val sid = "test_src"
        val r1 = com.kevin.fanzhou.data.CloudRecent(id = "vid1", name = "Video 1.mp4")
        val r2 = com.kevin.fanzhou.data.CloudRecent(id = "vid2", name = "Video 2.mp4")
        s.addCloudRecent(sid, r1)
        s.addCloudRecent(sid, r2)
        assertEquals(2, s.cloudRecent(sid).size)
        assertEquals("vid2", s.cloudRecent(sid)[0].id)
        s.removeCloudRecent(sid, "vid1")
        assertEquals(1, s.cloudRecent(sid).size)
        assertEquals("vid2", s.cloudRecent(sid)[0].id)
        s.removeCloudRecent(sid, "vid2")
        assertEquals(0, s.cloudRecent(sid).size)
    }
}
