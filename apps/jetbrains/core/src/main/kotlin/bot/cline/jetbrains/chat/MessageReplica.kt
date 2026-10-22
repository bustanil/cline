package bot.cline.jetbrains.chat

import com.google.gson.JsonObject

/**
 * One `ClineMessage` from the core. [raw] keeps every field, so message kinds
 * the plugin does not know yet still reach the plain-text fallback intact.
 */
class ChatMessage(val raw: JsonObject) {
    val ts: Long = raw.get("ts")?.asLong ?: 0L
    val seq: Int = raw.get("seq")?.takeIf { !it.isJsonNull }?.asInt ?: 0
    val epoch: Int = raw.get("epoch")?.takeIf { !it.isJsonNull }?.asInt ?: 0
}

/** `TurnState` from the core: the phase of the current turn, gated by [seq]. */
data class TurnState(val phase: String, val anchorTs: Long?, val seq: Int)

/**
 * The plugin's copy of the conversation. This is a port of the webview's
 * `messageReducer.ts`; read its header for why each rule exists. Instances are
 * immutable: every function returns the same object when the input is stale.
 */
class ReplicaState private constructor(
    val messages: List<ChatMessage>,
    /** Highest epoch applied. Older traffic is dropped. */
    val epoch: Int,
    private val seqByTs: Map<Long, Int>,
    /** Highest snapshot version applied. */
    val stateVersion: Int,
    val turnState: TurnState?,
    /** A lone newer-epoch message advanced [epoch] while old rows remain. */
    val transcriptCarriedForward: Boolean,
) {
    private fun copy(
        messages: List<ChatMessage> = this.messages,
        epoch: Int = this.epoch,
        seqByTs: Map<Long, Int> = this.seqByTs,
        stateVersion: Int = this.stateVersion,
        turnState: TurnState? = this.turnState,
        transcriptCarriedForward: Boolean = this.transcriptCarriedForward,
    ) = ReplicaState(messages, epoch, seqByTs, stateVersion, turnState, transcriptCarriedForward)

    /** Applies a turn state only if its seq is higher than the one held. */
    fun applyTurnState(incoming: TurnState?): ReplicaState {
        if (incoming == null) return this
        if (turnState != null && incoming.seq <= turnState.seq) return this
        return copy(turnState = incoming)
    }

    /** Applies one message from the partial-message stream or from inside a snapshot. */
    fun applyMessage(incoming: ChatMessage): ReplicaState {
        if (incoming.epoch < epoch) return this
        if (incoming.epoch > epoch) {
            // Advance the fence without discarding the transcript on one message's say-so.
            return copy(epoch = incoming.epoch, transcriptCarriedForward = messages.isNotEmpty()).applyMessage(incoming)
        }
        val existingSeq = seqByTs[incoming.ts]
        val index = messages.indexOfFirst { it.ts == incoming.ts }
        if (index != -1) {
            if (existingSeq != null && incoming.seq < existingSeq) return this
            val updated = messages.toMutableList().also { it[index] = incoming }
            return copy(messages = updated, seqByTs = seqByTs + (incoming.ts to maxOf(existingSeq ?: 0, incoming.seq)))
        }
        return copy(messages = messages + incoming, seqByTs = seqByTs + (incoming.ts to incoming.seq))
    }

    /** Applies the transcript of a full state snapshot. */
    fun applyStateSnapshot(
        snapshotMessages: List<ChatMessage>,
        snapshotEpoch: Int = 0,
        snapshotVersion: Int = 0,
        snapshotTurnState: TurnState? = null,
    ): ReplicaState {
        if (snapshotEpoch < epoch) return this
        if (snapshotEpoch > epoch) return resetTo(snapshotEpoch, snapshotMessages, snapshotVersion, snapshotTurnState)

        if (transcriptCarriedForward) {
            var next = resetTo(snapshotEpoch, snapshotMessages, snapshotVersion, turnState)
            for (message in messages) {
                if (message.epoch == snapshotEpoch) next = next.applyMessage(message)
            }
            return next.applyTurnState(snapshotTurnState)
        }
        if (snapshotVersion != 0 && snapshotVersion <= stateVersion) return applyTurnState(snapshotTurnState)

        // Merge by ts and seq; the transcript never shrinks within an epoch.
        var next = this
        for (message in snapshotMessages) next = next.applyMessage(message)
        if (snapshotVersion > next.stateVersion) next = next.copy(stateVersion = snapshotVersion)
        return next.applyTurnState(snapshotTurnState)
    }

    companion object {
        fun empty() = ReplicaState(emptyList(), 0, emptyMap(), 0, null, false)

        private fun resetTo(epoch: Int, messages: List<ChatMessage>, stateVersion: Int, turnState: TurnState?): ReplicaState {
            val seqByTs = HashMap<Long, Int>()
            for (m in messages) {
                val existing = seqByTs[m.ts]
                if (existing == null || m.seq >= existing) seqByTs[m.ts] = m.seq
            }
            return ReplicaState(messages.toList(), epoch, seqByTs, stateVersion, turnState, false)
        }
    }
}
