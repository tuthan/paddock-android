package io.github.tuthan.paddock.settings

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsTest {
    private val dir = Files.createTempDirectory("sett").toFile()
    private val file = dir.resolve("settings.json")

    @Test fun protectionIsOnByDefault() = runBlocking {
        assertTrue(AppSettings().protectSensitiveScreens)
        assertTrue(FileAppSettingsStore(file).load().protectSensitiveScreens)
    }

    @Test fun aSavedChoiceSurvivesANewStore() = runBlocking {
        FileAppSettingsStore(file).save(AppSettings(protectSensitiveScreens = false))
        assertEquals(AppSettings(false), FileAppSettingsStore(file).load())
    }

    @Test fun aDamagedFileFallsBackToTheSafeDefault() = runBlocking {
        file.writeText("{ not json")
        assertEquals(AppSettings(true), FileAppSettingsStore(file).load())
        file.writeText("""{"protectSensitiveScreens": "maybe"}""")
        assertEquals(AppSettings(true), FileAppSettingsStore(file).load())
    }

    @Test fun unknownKeysFromANewerVersionAreIgnored() = runBlocking {
        file.writeText("""{"protectSensitiveScreens": false, "somethingNew": 3}""")
        assertEquals(AppSettings(false), FileAppSettingsStore(file).load())
    }

    @Test fun saveLeavesNoTempFileBehind() = runBlocking {
        FileAppSettingsStore(file).save(AppSettings(false))
        assertEquals(listOf("settings.json"), dir.list()!!.toList())
    }
}
