package dev.doona.android.vpn.honk

import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.read
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** Polls honk's operation at [href] until it ends, and returns it, succeeded or failed. */
internal suspend fun NativeApi.finished(href: String): Operation<JsonElement> {
    while (true) {
        delay(OPERATION_POLL_MS)
        val operation = read("GET", href, Operation.serializer(JsonElement.serializer()))
        if (operation.status == OperationStatus.Succeeded || operation.status == OperationStatus.Failed) return operation
    }
}

/** honk's operations ask to be polled no more than once a second (`Retry-After: 1`). */
private const val OPERATION_POLL_MS = 1_000L
