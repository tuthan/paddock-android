package io.github.tuthan.paddock.notify

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.alerts.DeepLink
import io.github.tuthan.paddock.alerts.FilePushStore
import io.github.tuthan.paddock.alerts.NotificationContent
import io.github.tuthan.paddock.alerts.PushRegistry
import io.github.tuthan.paddock.alerts.UnifiedPush
import io.github.tuthan.paddock.ports.Clock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a push raises no notification: only when the herd of **that machine** is in front ([io.github.tuthan.paddock.alerts.AlertScreen]). A user reported nothing at
 * all for a second machine while Paddock was open on the first, because the receiver skipped every push whenever any screen was in front.
 *
 * Each case builds its own connector over its own temporary store and hands it the broadcast the distributor would send, so nothing here touches the app's
 * registrations, its notifications or its activity (a version that launched the real activity left the other connector tests flaky).
 */
class PushInFrontTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    @After fun stop() { scope.cancel() }

    /** What a push for the machine "push-box" raises when [front] says whether a herd is in front and [watched] is the machine it shows. */
    private fun raised(front: Boolean, watched: String?): List<NotificationContent> {
        val file = File.createTempFile("push-in-front", ".json", ctx.cacheDir)
        try {
            val shown = CopyOnWriteArrayList<NotificationContent>()
            val registry = PushRegistry(FilePushStore(file), clock = clock)
            val connector = UnifiedPushConnector(
                ctx, registry, scope, clock,
                machineName = { id -> if (id == "push-box") "push box" else null },
                show = { shown += it }, herdInFront = { front }, watchedProfile = { watched }, hideOnLockScreen = { true },
            )
            val token = runBlocking { registry.begin("push-box", "org.example.distributor").first.token }
            // The distributor gives the registration its address first, as it does for a real one; a message before that is not one it could have routed here.
            val addressed = CountDownLatch(1)
            connector.handle(Intent(UnifiedPush.NEW_ENDPOINT).putExtra("token", token).putExtra("endpoint", "https://push.example.org/up/abc123"), addressed::countDown)
            assertTrue("the address was taken", addressed.await(10, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            val message = Intent(UnifiedPush.MESSAGE).putExtra("token", token).putExtra("id", "m-1")
                .putExtra("bytesMessage", """{"v":1,"h":"push-box","n":"abc123def456"}""".toByteArray())
            connector.handle(message, done::countDown)
            assertTrue("the message was handled", done.await(10, TimeUnit.SECONDS))
            return shown.toList()
        } finally { file.delete() }
    }

    @Test fun aPushForAMachineThatIsNotOnScreenIsRaisedEvenWhilePaddockIsInFront() {
        val shown = raised(front = true, watched = "another-box").single()
        assertEquals("Paddock: attention on push box", shown.publicTitle)
        assertEquals("push-box", DeepLink.profileOf(shown.link))
    }

    @Test fun aPushForTheMachineOnScreenIsTheScreensToShowAndRaisesNothing() {
        assertEquals(emptyList<NotificationContent>(), raised(front = true, watched = "push-box"))
    }

    @Test fun withNoHerdInFrontEveryPushIsRaisedWhicheverMachineIsWatched() {
        assertEquals(1, raised(front = false, watched = "push-box").size)
        assertEquals(1, raised(front = false, watched = "another-box").size)
        assertEquals(1, raised(front = false, watched = null).size)
    }

    @Test fun withNoMachineWatchedNoHerdIsOnScreenSoThePushIsRaised() {
        assertEquals(1, raised(front = true, watched = null).size)
    }
}
