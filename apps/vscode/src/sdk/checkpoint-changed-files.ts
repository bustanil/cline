import { execFile as execFileCallback } from "node:child_process"
import * as fs from "node:fs/promises"
import * as path from "node:path"
import { promisify } from "node:util"
import { ChangedFile, ChangedFileStatus } from "@shared/proto/cline/checkpoints"
import { diffLines } from "diff"

const execFile = promisify(execFileCallback)

/** One file's content at the checkpoint (left) and in the working tree (right). */
export interface CheckpointFileDiff {
	filePath: string
	leftContent: string
	rightContent: string
}

/** A comparison between the latest checkpoint and the working tree. */
export interface CheckpointComparison {
	cwd: string
	checkpointRef: string
	diffs: CheckpointFileDiff[]
}

async function pathExists(filePath: string): Promise<boolean> {
	try {
		await fs.access(filePath)
		return true
	} catch {
		return false
	}
}

async function gitObjectExists(cwd: string, object: string): Promise<boolean> {
	try {
		await execFile("git", ["-C", cwd, "cat-file", "-e", object], { windowsHide: true })
		return true
	} catch {
		return false
	}
}

/**
 * Whether the file existed at the checkpoint. Both a missing and an empty file read as "",
 * so ask git. Stash-shaped checkpoints keep files untracked at snapshot time in the third parent.
 */
export async function existedAtCheckpoint(comparison: CheckpointComparison, filePath: string): Promise<boolean> {
	const relativePath = path.relative(comparison.cwd, filePath).split(path.sep).join("/")
	return (
		(await gitObjectExists(comparison.cwd, `${comparison.checkpointRef}:${relativePath}`)) ||
		(await gitObjectExists(comparison.cwd, `${comparison.checkpointRef}^3:${relativePath}`))
	)
}

function countChangedLines(leftContent: string, rightContent: string): { added: number; removed: number } {
	let added = 0
	let removed = 0
	for (const part of diffLines(leftContent, rightContent)) {
		if (part.added) {
			added += part.count ?? 0
		} else if (part.removed) {
			removed += part.count ?? 0
		}
	}
	return { added, removed }
}

/** Lists each changed file with its status and added/removed line counts, sorted by path. */
export async function summarizeChangedFiles(comparison: CheckpointComparison): Promise<ChangedFile[]> {
	const files = await Promise.all(
		comparison.diffs.map(async (diff) => {
			const status = !(await pathExists(diff.filePath))
				? ChangedFileStatus.CHANGED_FILE_DELETED
				: (await existedAtCheckpoint(comparison, diff.filePath))
					? ChangedFileStatus.CHANGED_FILE_MODIFIED
					: ChangedFileStatus.CHANGED_FILE_ADDED
			const { added, removed } = countChangedLines(diff.leftContent, diff.rightContent)
			return ChangedFile.create({
				path: diff.filePath,
				relativePath: path.relative(comparison.cwd, diff.filePath).split(path.sep).join("/"),
				status,
				addedLines: added,
				removedLines: removed,
			})
		}),
	)
	return files.sort((a, b) => a.relativePath.localeCompare(b.relativePath))
}

/** Finds the diff for a file, comparing paths the way the host reports them. */
export function findFileDiff(comparison: CheckpointComparison, filePath: string): CheckpointFileDiff | undefined {
	const target = path.resolve(filePath)
	return comparison.diffs.find((diff) => path.resolve(diff.filePath) === target)
}

/**
 * Restores a file to its checkpoint content, or deletes it if it did not exist at the checkpoint.
 */
export async function revertFileToCheckpoint(comparison: CheckpointComparison, diff: CheckpointFileDiff): Promise<void> {
	if (!(await existedAtCheckpoint(comparison, diff.filePath))) {
		await fs.rm(diff.filePath, { force: true })
		return
	}
	await fs.mkdir(path.dirname(diff.filePath), { recursive: true })
	await fs.writeFile(diff.filePath, diff.leftContent, "utf8")
}
