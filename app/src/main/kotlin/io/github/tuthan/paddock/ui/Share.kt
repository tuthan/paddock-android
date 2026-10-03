package io.github.tuthan.paddock.ui

import android.content.Intent

/**
 * The share-sheet intent for the authorize command: plain text, and nothing else. No subject, no title, no stream, no clip
 * and no key material beyond what the command already says (it holds a public key line, which is not a secret). The caller
 * wraps it in a chooser; the user picks where it goes.
 */
fun commandShareIntent(command: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command)
