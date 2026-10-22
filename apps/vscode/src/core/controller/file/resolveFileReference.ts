import { StringRequest } from "@shared/proto/cline/common"
import { FileLocation } from "@shared/proto/cline/file"
import { parseFileReference, resolveReferencedFile } from "@utils/file-reference"
import { getWorkspacePath } from "@utils/path"
import { HostProvider } from "@/hosts/host-provider"
import { Controller } from ".."

/**
 * Workspace roots to resolve relative chat paths against: the root of the active editor
 * first, then every other workspace folder.
 */
export async function getReferenceRoots(): Promise<string[]> {
	const roots = [await getWorkspacePath()]
	try {
		roots.push(...(await HostProvider.workspace.getWorkspacePaths({})).paths)
	} catch {
		// The active root alone is still useful
	}
	return [...new Set(roots.filter(Boolean))]
}

/**
 * Resolves a file reference from chat (e.g. "src/app.ts:12-20" or "~/notes.md") to an
 * existing file and its line range. The returned path is empty when no file matches.
 */
export async function resolveFileReference(_controller: Controller, request: StringRequest): Promise<FileLocation> {
	const reference = parseFileReference(request.value)
	if (!reference) {
		return FileLocation.create({ path: "" })
	}

	const absolutePath = resolveReferencedFile(reference.path, await getReferenceRoots())
	if (!absolutePath) {
		return FileLocation.create({ path: "" })
	}

	return FileLocation.create({
		path: absolutePath,
		startLine: reference.startLine,
		endLine: reference.endLine,
	})
}
