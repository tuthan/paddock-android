package io.github.tuthan.paddock.spike

import android.app.Activity
import android.os.Bundle

/** Roots the adapter so the release (R8) build keeps the library, for the S7 size measurement. */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SshlibClient().close()
    }
}
