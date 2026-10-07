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

    @Test fun theLockIsOffByDefaultAndItsChoicesSurviveARestartAndAnOlderFileHasNone() = runBlocking {
        assertEquals(false, AppSettings().appLock)
        assertEquals(60, AppSettings().appLockAfterSeconds)
        FileAppSettingsStore(file).save(AppSettings(appLock = true, appLockAfterSeconds = 300))
        val back = FileAppSettingsStore(file).load()
        assertEquals(true, back.appLock); assertEquals(300, back.appLockAfterSeconds)
        file.writeText("""{"protectSensitiveScreens": true}""")
        assertEquals(false, FileAppSettingsStore(file).load().appLock, "a file written before the lock existed")
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

    @Test fun theWatchedMachineIsRememberedAndAnOlderFileHasNone() = runBlocking {
        FileAppSettingsStore(file).save(AppSettings(watchedProfileId = "laptop"))
        assertEquals("laptop", FileAppSettingsStore(file).load().watchedProfileId)
        file.writeText("""{"protectSensitiveScreens": false}""")
        assertEquals(AppSettings(false, watchedProfileId = null), FileAppSettingsStore(file).load())
        file.writeText("""{"watchedProfileId": [1]}""")
        assertEquals(AppSettings(), FileAppSettingsStore(file).load())
    }

    @Test fun theChosenMachineIsRememberedApartFromTheWatchedOneAndAnOlderFileHasNone() = runBlocking {
        FileAppSettingsStore(file).save(AppSettings(watchedProfileId = "desk", chosenProfileId = "laptop"))
        assertEquals(AppSettings(watchedProfileId = "desk", chosenProfileId = "laptop"), FileAppSettingsStore(file).load(), "a round trip keeps both")
        file.writeText("""{"protectSensitiveScreens": false, "watchedProfileId": "laptop"}""")
        val older = FileAppSettingsStore(file).load()
        assertEquals(null, older.chosenProfileId, "a file from before the choice loads with none; the app fills it at start")
        assertEquals(AppSettings(protectSensitiveScreens = false, watchedProfileId = "laptop"), older, "and keeps everything else")
    }

    @Test fun saveLeavesNoTempFileBehind() = runBlocking {
        FileAppSettingsStore(file).save(AppSettings(false))
        assertEquals(listOf("settings.json"), dir.list()!!.toList())
    }

    @Test fun promptTextIsNotKeptByDefaultAndTheChoiceSurvivesANewStore() = runBlocking {
        assertEquals(false, AppSettings().keepPromptText)
        assertEquals(false, FileAppSettingsStore(file).load().keepPromptText)
        FileAppSettingsStore(file).save(AppSettings(keepPromptText = true))
        assertEquals(true, FileAppSettingsStore(file).load().keepPromptText)
        file.writeText("""{"protectSensitiveScreens": true, "keepPromptText": "yes"}""")
        assertEquals(false, FileAppSettingsStore(file).load().keepPromptText, "a damaged value falls back to the private default")
    }

    @Test fun desktopFocusAsksUntilTheUserHasConfirmedOnceAndRemembersIt() = runBlocking {
        assertEquals(false, AppSettings().desktopFocusConfirmed)
        assertEquals(false, FileAppSettingsStore(file).load().desktopFocusConfirmed)
        FileAppSettingsStore(file).save(AppSettings(desktopFocusConfirmed = true))
        assertEquals(true, FileAppSettingsStore(file).load().desktopFocusConfirmed, "it survives a restart")
        file.writeText("""{"protectSensitiveScreens": true, "desktopFocusConfirmed": "yes"}""")
        assertEquals(false, FileAppSettingsStore(file).load().desktopFocusConfirmed, "a damaged value asks again")
    }

    @Test fun agentIconsAreOnByDefaultAndTheChoiceSurvivesANewStore() = runBlocking {
        assertEquals(true, AppSettings().agentGlyphs)
        assertEquals(true, FileAppSettingsStore(file).load().agentGlyphs)
        FileAppSettingsStore(file).save(AppSettings(agentGlyphs = false))
        assertEquals(false, FileAppSettingsStore(file).load().agentGlyphs, "it survives a restart")
        assertEquals(AppSettings(agentGlyphs = false), FileAppSettingsStore(file).load(), "and nothing else changed with it")
    }

    @Test fun aSettingsFileFromBeforeAgentIconsLoadsWithThemOnAndKeepsTheOtherChoices() = runBlocking {
        file.writeText("""{"protectSensitiveScreens": false, "keepPromptText": true, "watchedProfileId": "laptop"}""")
        val loaded = FileAppSettingsStore(file).load()
        assertEquals(true, loaded.agentGlyphs)
        assertEquals(AppSettings(protectSensitiveScreens = false, keepPromptText = true, watchedProfileId = "laptop"), loaded)
    }

    @Test fun aWrongTypedAgentIconsValueLoadsAsOnAndLoadingLeavesTheFileAlone() = runBlocking {
        val damaged = """{"protectSensitiveScreens": true, "agentGlyphs": "maybe"}"""
        file.writeText(damaged)
        assertEquals(true, FileAppSettingsStore(file).load().agentGlyphs)
        assertEquals(damaged, file.readText(), "a load never rewrites or removes the file")
        assertEquals(listOf("settings.json"), dir.list()!!.toList())
        file.writeText("""{"agentGlyphs": 0}""")
        assertEquals(true, FileAppSettingsStore(file).load().agentGlyphs)
        file.writeText("""{"agentGlyphs": null}""")
        assertEquals(true, FileAppSettingsStore(file).load().agentGlyphs)
    }
}
