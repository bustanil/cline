package bot.cline.jetbrains.bridge

import bot.cline.host.proto.CoreConnectionMessage
import io.grpc.stub.StreamObserver

/** One authenticated stream to a running `cline-core`. */
class CoreConnection internal constructor(
    private val outbound: StreamObserver<CoreConnectionMessage>,
) {
    @Volatile
    var authenticated: Boolean = false
        internal set

    @Volatile
    var instanceId: String = ""
        internal set

    @Volatile
    var closed: Boolean = false
        private set

    internal fun dispatch(message: CoreConnectionMessage) {
        // Responses to the plugin's calls are matched to requests in the next step.
    }

    internal fun close(cause: Throwable?) {
        closed = true
    }
}
