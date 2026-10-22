import { Empty, StringRequest } from "@shared/proto/cline/common"
import { Controller } from ".."

/**
 * Reverts one file to the latest checkpoint, or deletes it if the agent
 * created it. The request value is the file's absolute path.
 */
export async function checkpointRevertFile(controller: Controller, request: StringRequest): Promise<Empty> {
	const sdkRevertLatestFileChange = (
		controller as Controller & {
			revertLatestFileChange?: (filePath: string) => Promise<void>
		}
	).revertLatestFileChange
	if (sdkRevertLatestFileChange && request.value) {
		await sdkRevertLatestFileChange.call(controller, request.value)
	}
	return Empty.create({})
}
