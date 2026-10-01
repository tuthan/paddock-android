package io.github.tuthan.paddock.spike

class SshlibClientSuite : SpikeSuite() {
    override fun newClient(): SpikeClient = SshlibClient()
}
