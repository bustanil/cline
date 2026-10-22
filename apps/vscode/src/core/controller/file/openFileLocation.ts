import { openFile as openFileIntegration } from "@integrations/misc/open-file"
import { Empty } from "@shared/proto/cline/common"
import { FileLocation } from "@shared/proto/cline/file"
import { resolveReferencedFile } from "@utils/file-reference"
import { HostProvider } from "@/hosts/host-provider"
import { ShowMessageType } from "@/shared/proto/host/window"
import { Controller } from ".."
import { getReferenceRoots } from "./resolveFileReference"

/**
 * Opens a file and selects its line range, if any. Relative paths are resolved against
 * the workspace roots; a missing file is reported to the user instead of failing silently.
 */
export async function openFileLocation(_controller: Controller, request: FileLocation): Promise<Empty> {
	if (!request.path) {
		return Empty.create()
	}

	const absolutePath = resolveReferencedFile(request.path, await getReferenceRoots())
	if (!absolutePath) {
		HostProvider.window.showMessage({
			type: ShowMessageType.WARNING,
			message: `File not found: ${request.path}`,
		})
		return Empty.create()
	}

	const selection =
		request.startLine && request.startLine > 0
			? { startLine: request.startLine, endLine: request.endLine ?? request.startLine }
			: undefined
	await openFileIntegration(absolutePath, false, false, selection)
	return Empty.create()
}
