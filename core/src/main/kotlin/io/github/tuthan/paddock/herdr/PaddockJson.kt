package io.github.tuthan.paddock.herdr

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for everything herdr sends. Unknown keys are ignored so a newer herdr
 * does not break an older phone; a missing required field is a decode failure that the caller turns
 * into a disabled feature, never a crash.
 */
@OptIn(ExperimentalSerializationApi::class)
val PaddockJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Decodes [text] as [T]; any malformed or incomplete input becomes a failed [Result], not an exception. */
inline fun <reified T> Json.decodeResult(text: String): Result<T> =
    try {
        Result.success(decodeFromString<T>(text))
    } catch (e: RuntimeException) {
        // SerializationException and IllegalArgumentException, plus what kotlinx throws on odd shapes (IndexOutOfBounds).
        Result.failure(e)
    }
