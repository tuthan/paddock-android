package io.github.tuthan.paddock.demo

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.HostProfileStore
import io.github.tuthan.paddock.ports.Clock

/**
 * Whether the demo machine is running, and its herd while it is. In memory only: a process that ends takes the demo with it, and the next start has
 * no machine (Welcome), which is the "leaves nothing behind" the screens promise.
 */
class DemoMode(private val clock: Clock) {
    private val lock = Any()
    private var herd: DemoHerd? = null
    private var profile: HostProfile? = null

    val active: Boolean get() = synchronized(lock) { herd != null }

    /** The demo machine's herd, or null when the demo is not running. Kept for as long as the demo runs, across the connection's reconnects. */
    fun herd(): DemoHerd? = synchronized(lock) { herd }

    fun profile(): HostProfile? = synchronized(lock) { profile }

    /** Starts the demo with a herd made from nothing; a demo already running keeps its herd and takes the new profile. */
    fun begin(demo: HostProfile) = synchronized(lock) {
        require(Demo.isDemo(demo)) { "not the demo machine" }
        if (herd == null) herd = DemoHerd(clock)
        profile = demo
    }

    fun end() = synchronized(lock) { herd = null; profile = null }

    /** The same rule as [HostProfileStore.update], on the demo's profile: what a bring-up read about the machine is kept for as long as the demo runs. */
    fun update(transform: (HostProfile) -> HostProfile?): HostProfile? = synchronized(lock) {
        val current = profile ?: return null
        transform(current)?.takeIf { it != current }?.also { profile = it }
    }
}

/**
 * The profile store the app uses: the saved machines of [delegate], and the demo machine beside them while [mode] runs. The demo's profile is never
 * written to [delegate]: putting it starts the demo, removing it ends it, and updating it changes the copy in memory. Putting any other machine ends the
 * demo, because the person has a real machine now.
 */
class DemoProfileStore(private val delegate: HostProfileStore, private val mode: DemoMode) : HostProfileStore {
    override suspend fun list(): List<HostProfile> = (delegate.list() + listOfNotNull(mode.profile())).sortedBy { it.name.lowercase() }

    override suspend fun get(id: String): HostProfile? = if (Demo.isDemo(id)) mode.profile() else delegate.get(id)

    override suspend fun put(profile: HostProfile) {
        if (Demo.isDemo(profile)) mode.begin(profile) else { mode.end(); delegate.put(profile) }
    }

    override suspend fun update(id: String, transform: (HostProfile) -> HostProfile?): HostProfile? =
        if (Demo.isDemo(id)) mode.update { current -> transform(current)?.also { require(it.id == id) { "update cannot rename a profile" } } } else delegate.update(id, transform)

    override suspend fun remove(id: String) { if (Demo.isDemo(id)) mode.end() else delegate.remove(id) }
}
