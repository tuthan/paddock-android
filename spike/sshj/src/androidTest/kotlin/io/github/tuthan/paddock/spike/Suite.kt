package io.github.tuthan.paddock.spike

class SshjClientSuite : SpikeSuite() {
    override fun newClient(): SpikeClient = SshjClient()
}
