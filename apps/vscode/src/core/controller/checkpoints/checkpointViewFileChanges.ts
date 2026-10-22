import { Empty, StringRequest } from "@shared/proto/cline/common"
import { Controller } from ".."

/**
 * Opens the diff of one file changed since the latest checkpoint. The request
 * value is the file's absolute path.
 */
export async function checkpointViewFileChanges(controller: Controller, request: StringRequest): Promise<Empty> {
	const sdkViewLatestFileChanges = (
		controller as Controller & {
			viewLatestFileChanges?: (filePath: string) => Promise<void>
		}
	).viewLatestFileChanges
	if (sdkViewLatestFileChanges && request.value) {
		await sdkViewLatestFileChanges.call(controller, request.value)
	}
	return Empty.create({})
}
