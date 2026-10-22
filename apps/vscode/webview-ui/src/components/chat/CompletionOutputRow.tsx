import type { ChangedFile } from "@shared/proto/cline/checkpoints"
import { EmptyRequest } from "@shared/proto/cline/common"
import { GitCompareIcon } from "lucide-react"
import { memo, useCallback, useEffect, useState } from "react"
import { CheckpointsServiceClient } from "@/services/grpc-client"
import { CopyButton } from "../common/CopyButton"
import SuccessButton from "../common/SuccessButton"
import { ChangedFilesList } from "./ChangedFilesList"
import { QuoteButtonState } from "./ChatRow"
import { MarkdownRow } from "./MarkdownRow"
import QuoteButton from "./QuoteButton"

interface CompletionOutputRowProps {
	text: string
	quoteButtonState: QuoteButtonState
	handleQuoteClick: () => void
	/**
	 * Allows the changed-files list inside the card: every file that changed
	 * between the latest checkpoint (taken when the user's last message started
	 * the run) and the current working tree, plus a "View Changes" action that
	 * opens them all in one multi-file diff. Only meaningful on the latest,
	 * finalized completion row. Both stay hidden until the host confirms there
	 * are changes to show — no changes (or no checkpoint at all, e.g. a non-git
	 * workspace) simply renders nothing.
	 */
	showViewChanges?: boolean
}

/**
 * Quiet visual cue that the agent's turn ended on this response (act mode):
 * a green-tinted container with a small, muted "Completed" label and a copy
 * button. Deliberately less prominent than the legacy bold "Task Completed"
 * header, since the response might be a question or an interim summary
 * rather than a definitive task completion.
 */
export const CompletionOutputRow = memo(
	({ text, quoteButtonState, handleQuoteClick, showViewChanges }: CompletionOutputRowProps) => {
		const [viewChangesPending, setViewChangesPending] = useState(false)
		// undefined = still checking; the list stays hidden until the host
		// confirms the latest run actually changed files. An empty list also
		// covers "no checkpoint to compare against" (non-git workspace, no
		// commits yet, or a comparison failure) — in all of those cases there
		// is nothing to view, so nothing is rendered.
		const [changedFiles, setChangedFiles] = useState<ChangedFile[] | undefined>(undefined)
		// Bumped after a revert so the list is reloaded
		const [reloadCount, setReloadCount] = useState(0)
		const reload = useCallback(() => setReloadCount((count) => count + 1), [])

		// biome-ignore lint/correctness/useExhaustiveDependencies: reloadCount only triggers a reload
		useEffect(() => {
			// Reset on every re-check so a stale positive answer from a previous
			// evaluation can't flash the list before the host confirms the new
			// comparison.
			setChangedFiles(undefined)
			if (!showViewChanges) {
				return
			}
			let cancelled = false
			CheckpointsServiceClient.checkpointLatestChangedFiles(EmptyRequest.create({}))
				.then((result) => {
					if (!cancelled) {
						setChangedFiles(result.files ?? [])
					}
				})
				.catch((err) => {
					console.error("Failed to check for latest changes:", err)
					if (!cancelled) {
						setChangedFiles([])
					}
				})
			return () => {
				cancelled = true
			}
		}, [showViewChanges, reloadCount])

		const hasChanges = changedFiles !== undefined && changedFiles.length > 0

		return (
			<div className="rounded-sm border border-success/20 overflow-visible bg-success/10">
				<div className="flex items-center justify-between gap-2 pl-2 pr-1 pt-1 -mb-1.5">
					<span className="text-xs font-medium uppercase tracking-wider text-success/70">Completed</span>
					<CopyButton ariaLabel="Copy response" className="text-success/70" textToCopy={text} />
				</div>
				<div className="completion-output-content relative p-2 w-full [&_hr]:opacity-20 [&_p:last-child]:mb-0 rounded-sm">
					<MarkdownRow markdown={text} />
					{quoteButtonState.visible && (
						<QuoteButton left={quoteButtonState.left} onClick={handleQuoteClick} top={quoteButtonState.top} />
					)}
				</div>
				{showViewChanges && hasChanges && (
					<div className="flex flex-col gap-1.5 px-2 pb-2">
						<div className="text-[11px] font-medium uppercase tracking-wider text-success/70">
							{changedFiles.length === 1 ? "1 file changed" : `${changedFiles.length} files changed`}
						</div>
						<ChangedFilesList files={changedFiles} onReverted={reload} />
						<SuccessButton
							className="w-full"
							disabled={viewChangesPending}
							onClick={() => {
								setViewChangesPending(true)
								CheckpointsServiceClient.checkpointViewLatestChanges(EmptyRequest.create({}))
									.catch((err) => console.error("Failed to view latest changes:", err))
									.finally(() => setViewChangesPending(false))
							}}
							style={{ cursor: viewChangesPending ? "wait" : "pointer" }}>
							<GitCompareIcon className="size-3 mr-1.5" />
							View Changes
						</SuccessButton>
					</div>
				)}
			</div>
		)
	},
)

CompletionOutputRow.displayName = "CompletionOutputRow"
