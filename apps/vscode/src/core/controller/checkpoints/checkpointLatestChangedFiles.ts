import { ChangedFile, ChangedFiles } from "@shared/proto/cline/checkpoints"
import { EmptyRequest } from "@shared/proto/cline/common"
import { Controller } from ".."

/**
 * Lists the files changed between the latest checkpoint and the current
 * working tree, with status and line counts, for the changed-files list on
 * the completion row. Empty when nothing can be compared.
 */
export async function checkpointLatestChangedFiles(controller: Controller, _request: EmptyRequest): Promise<ChangedFiles> {
	const sdkGetLatestChangedFiles = (
		controller as Controller & {
			getLatestChangedFiles?: () => Promise<ChangedFile[]>
		}
	).getLatestChangedFiles
	if (sdkGetLatestChangedFiles) {
		return ChangedFiles.create({ files: await sdkGetLatestChangedFiles.call(controller) })
	}
	return ChangedFiles.create({ files: [] })
}
