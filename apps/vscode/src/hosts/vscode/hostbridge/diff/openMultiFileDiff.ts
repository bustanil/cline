import * as fs from "fs/promises"
import path from "path"
import * as vscode from "vscode"
import { OpenMultiFileDiffRequest, OpenMultiFileDiffResponse } from "@/shared/proto/index.host"
import { getCwd } from "@/utils/path"
import { DIFF_VIEW_URI_SCHEME } from "../../VscodeDiffContentProvider"

async function matchesFileOnDisk(filePath: string, content: string): Promise<boolean> {
	try {
		return (await fs.readFile(filePath, "utf8")) === content
	} catch {
		return false
	}
}

export async function openMultiFileDiff(request: OpenMultiFileDiffRequest): Promise<OpenMultiFileDiffResponse> {
	const cwd = await getCwd()
	await vscode.commands.executeCommand(
		"vscode.changes",
		request.title,
		await Promise.all(
			request.diffs.map(async (diff) => {
				const file = vscode.Uri.file(diff.filePath || "")
				const relativePath = path.relative(cwd, diff.filePath || "")
				const left = diff.leftContent ?? ""
				const right = diff.rightContent ?? ""
				// When the right side is the file as it is on disk, show the real file so it can be
				// edited and individual changes reverted in place. Previews of unsaved content stay read-only.
				const rightUri = (await matchesFileOnDisk(file.fsPath, right))
					? file
					: vscode.Uri.parse(`${DIFF_VIEW_URI_SCHEME}:${relativePath}`).with({
							query: Buffer.from(right).toString("base64"),
						})
				return [
					file,
					vscode.Uri.parse(`${DIFF_VIEW_URI_SCHEME}:${relativePath}`).with({
						query: Buffer.from(left).toString("base64"),
					}),
					rightUri,
				]
			}),
		),
	)

	// Hide the bottom panel to give more room for the diff view
	vscode.commands.executeCommand("workbench.action.closePanel")

	return {}
}
