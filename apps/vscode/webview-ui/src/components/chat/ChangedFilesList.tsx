import { type ChangedFile, ChangedFileStatus } from "@shared/proto/cline/checkpoints"
import { StringRequest } from "@shared/proto/cline/common"
import { FileLocation } from "@shared/proto/cline/file"
import { GitCompareIcon, Undo2Icon } from "lucide-react"
import { memo, useState } from "react"
import { cn } from "@/lib/utils"
import { CheckpointsServiceClient, FileServiceClient } from "@/services/grpc-client"

const STATUS_LABELS: Record<ChangedFileStatus, { letter: string; title: string; className: string }> = {
	[ChangedFileStatus.CHANGED_FILE_MODIFIED]: { letter: "M", title: "Modified", className: "text-warning" },
	[ChangedFileStatus.CHANGED_FILE_ADDED]: { letter: "A", title: "Added", className: "text-success" },
	[ChangedFileStatus.CHANGED_FILE_DELETED]: { letter: "D", title: "Deleted", className: "text-error" },
	[ChangedFileStatus.UNRECOGNIZED]: { letter: "?", title: "Changed", className: "text-description" },
}

interface ChangedFilesListProps {
	files: ChangedFile[]
	/** Called after a file was reverted, so the caller can reload the list. */
	onReverted: () => void
}

/**
 * The files changed since the user's last message, each with its line counts and
 * actions to open it, view its diff, or revert it to the checkpoint.
 */
export const ChangedFilesList = memo(({ files, onReverted }: ChangedFilesListProps) => {
	// Revert asks for a second click on the same row before it changes anything
	const [confirmingRevert, setConfirmingRevert] = useState<string | null>(null)
	const [reverting, setReverting] = useState<string | null>(null)

	const revert = (file: ChangedFile) => {
		if (confirmingRevert !== file.path) {
			setConfirmingRevert(file.path)
			return
		}
		setConfirmingRevert(null)
		setReverting(file.path)
		CheckpointsServiceClient.checkpointRevertFile(StringRequest.create({ value: file.path }))
			.catch((err) => console.error("Failed to revert file:", err))
			.finally(() => {
				setReverting(null)
				onReverted()
			})
	}

	return (
		<ul aria-label="Changed files" className="flex flex-col max-h-60 overflow-y-auto m-0 p-0 list-none">
			{files.map((file) => {
				const status = STATUS_LABELS[file.status] ?? STATUS_LABELS[ChangedFileStatus.UNRECOGNIZED]
				const isDeleted = file.status === ChangedFileStatus.CHANGED_FILE_DELETED
				const isConfirming = confirmingRevert === file.path
				return (
					<li className="flex items-center gap-1.5 min-w-0 text-[12px] leading-5" key={file.path}>
						<span className={cn("shrink-0 w-3 font-mono font-semibold", status.className)} title={status.title}>
							{status.letter}
						</span>
						<button
							className={cn(
								"flex-1 min-w-0 truncate text-left bg-transparent border-0 p-0 text-[12px] text-foreground [direction:rtl]",
								isDeleted ? "line-through opacity-70 cursor-default" : "cursor-pointer hover:text-link",
							)}
							disabled={isDeleted}
							onClick={() =>
								FileServiceClient.openFileLocation(FileLocation.create({ path: file.path })).catch((err) =>
									console.error("Failed to open file:", err),
								)
							}
							title={isDeleted ? `${file.relativePath} (deleted)` : `Open ${file.relativePath}`}
							type="button">
							{`${file.relativePath}‎`}
						</button>
						<span className="shrink-0 font-mono tabular-nums">
							<span className="text-success">+{file.addedLines}</span>{" "}
							<span className="text-error">−{file.removedLines}</span>
						</span>
						<button
							aria-label={`View changes to ${file.relativePath}`}
							className="shrink-0 bg-transparent border-0 p-0.5 cursor-pointer text-description hover:text-link"
							onClick={() =>
								CheckpointsServiceClient.checkpointViewFileChanges(
									StringRequest.create({ value: file.path }),
								).catch((err) => console.error("Failed to view file changes:", err))
							}
							title="View changes"
							type="button">
							<GitCompareIcon className="size-3" />
						</button>
						<button
							aria-label={isConfirming ? `Confirm revert of ${file.relativePath}` : `Revert ${file.relativePath}`}
							className={cn(
								"shrink-0 flex items-center gap-0.5 bg-transparent border-0 p-0.5 cursor-pointer",
								isConfirming ? "text-error" : "text-description hover:text-error",
							)}
							disabled={reverting !== null}
							onBlur={() => isConfirming && setConfirmingRevert(null)}
							onClick={() => revert(file)}
							title={isConfirming ? "Click again to revert this file" : "Revert to before your last message"}
							type="button">
							<Undo2Icon className="size-3" />
							{isConfirming && <span>Revert?</span>}
						</button>
					</li>
				)
			})}
		</ul>
	)
})

ChangedFilesList.displayName = "ChangedFilesList"
