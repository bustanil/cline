package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionCancel
import bot.cline.host.proto.CoreConnectionMessage
import bot.cline.host.proto.CoreConnectionRequest
import bot.cline.host.proto.CoreConnectionResponse
import io.grpc.stub.StreamObserver
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** The core answered a call with an error. */
class CoreCallException(message: String) : RuntimeException(message)

/** Stops a subscription. */
fun interface Subscription {
    fun cancel()
}

/** What the chat needs from the core: unary calls and subscriptions with JSON payloads. */
interface CoreApi {
    fun call(service: String, method: String, messageJson: String = "{}"): CompletableFuture<String>

    fun subscribe(
        service: String,
        method: String,
        messageJson: String = "{}",
        onMessage: (String) -> Unit,
        onClose: (Throwable?) -> Unit = {},
    ): Subscription
}

/**
 * One authenticated stream to a running `cline-core`. It sends the plugin's
 * calls and routes the core's responses back by request id. Payloads are
 * proto3 JSON strings.
 */
class CoreConnection internal constructor(
    private val outbound: StreamObserver<CoreConnectionMessage>,
) : CoreApi {
    @Volatile
    var authenticated: Boolean = false
        internal set

    @Volatile
    var instanceId: String = ""
        internal set

    @Volatile
    var closed: Boolean = false
        private set

    private val pending = ConcurrentHashMap<String, PendingCall>()

    /** Calls a unary method, such as `cline.StateService` / `getLatestState`. Resolves with the response JSON. */
    override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        send(service, method, messageJson, streaming = false, PendingCall(future = future))
        return future
    }

    /**
     * Calls a streaming method. [onMessage] gets each payload in order;
     * [onClose] gets null when the core ends the stream, or the failure.
     */
    override fun subscribe(
        service: String,
        method: String,
        messageJson: String,
        onMessage: (String) -> Unit,
        onClose: (Throwable?) -> Unit,
    ): Subscription {
        val requestId = send(service, method, messageJson, streaming = true, PendingCall(onMessage = onMessage, onClose = onClose))
        return Subscription {
            if (pending.remove(requestId) != null) {
                write(CoreConnectionMessage.newBuilder().setCancel(CoreConnectionCancel.newBuilder().setRequestId(requestId)).build())
            }
        }
    }

    private fun send(service: String, method: String, json: String, streaming: Boolean, call: PendingCall): String {
        val requestId = UUID.randomUUID().toString()
        if (closed) {
            call.fail(CoreCallException("core connection is closed"))
            return requestId
        }
        pending[requestId] = call
        val request = CoreConnectionRequest.newBuilder()
            .setRequestId(requestId)
            .setService(service)
            .setMethod(method)
            .setMessageJson(json)
            .setIsStreaming(streaming)
            .build()
        try {
            write(CoreConnectionMessage.newBuilder().setRequest(request).build())
        } catch (e: Exception) {
            pending.remove(requestId)?.fail(e)
        }
        return requestId
    }

    @Synchronized
    private fun write(message: CoreConnectionMessage) = outbound.onNext(message)

    internal fun dispatch(message: CoreConnectionMessage) {
        if (!message.hasResponse()) return
        val response = message.response
        val call = pending[response.requestId] ?: return
        if (response.hasError()) {
            pending.remove(response.requestId)
            call.fail(CoreCallException(response.error))
            return
        }
        call.accept(response)
        if (response.completed) {
            pending.remove(response.requestId)
            call.complete()
        }
    }

    internal fun close(cause: Throwable?) {
        closed = true
        val failure = cause ?: CoreCallException("core connection closed")
        pending.keys.toList().forEach { pending.remove(it)?.fail(failure) }
    }

    /** Waits for the chunks of one logical payload, then hands it on. */
    private class PendingCall(
        val future: CompletableFuture<String>? = null,
        val onMessage: ((String) -> Unit)? = null,
        val onClose: ((Throwable?) -> Unit)? = null,
    ) {
        private val chunks = ByteArrayOutputStream()

        fun accept(response: CoreConnectionResponse) {
            val payload = when {
                response.hasMessageJson() -> response.messageJson
                response.hasMessageJsonChunk() -> {
                    response.messageJsonChunk.writeTo(chunks)
                    if (response.messageJsonComplete) chunks.toString(Charsets.UTF_8).also { chunks.reset() } else null
                }
                else -> null
            } ?: return
            future?.complete(payload)
            onMessage?.invoke(payload)
        }

        fun complete() {
            // A unary call whose response carried no body still finishes.
            future?.complete("{}")
            onClose?.invoke(null)
        }

        fun fail(error: Throwable) {
            future?.completeExceptionally(error)
            onClose?.invoke(error)
        }
    }
}
