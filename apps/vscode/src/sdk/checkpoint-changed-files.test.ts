import { afterEach, beforeEach, describe, expect, it } from "bun:test"
import { execFileSync } from "node:child_process"
import * as fs from "node:fs"
import * as os from "node:os"
import * as path from "node:path"
import { ChangedFileStatus } from "@shared/proto/cline/checkpoints"
import {
	type CheckpointComparison,
	findFileDiff,
	revertFileToCheckpoint,
	summarizeChangedFiles,
} from "./checkpoint-changed-files"

function git(cwd: string, ...args: string[]): string {
	return execFileSync("git", ["-C", cwd, ...args], { encoding: "utf8" }).trim()
}

describe("checkpoint changed files", () => {
	let repo: string
	let comparison: CheckpointComparison
	const file = (relativePath: string) => path.join(repo, relativePath)

	beforeEach(() => {
		repo = fs.mkdtempSync(path.join(os.tmpdir(), "changed-files-"))
		git(repo, "init", "-q")
		git(repo, "config", "user.email", "test@example.com")
		git(repo, "config", "user.name", "Test")
		fs.mkdirSync(file("src"))
		fs.writeFileSync(file("src/edited.ts"), "a\nb\nc\n")
		fs.writeFileSync(file("src/removed.ts"), "gone\n")
		fs.writeFileSync(file("src/was-empty.ts"), "")
		git(repo, "add", "-A")
		git(repo, "commit", "-q", "-m", "checkpoint")
		const checkpointRef = git(repo, "rev-parse", "HEAD")

		// What the agent did after the checkpoint
		fs.writeFileSync(file("src/edited.ts"), "a\nB\nc\nd\n")
		fs.rmSync(file("src/removed.ts"))
		fs.writeFileSync(file("src/was-empty.ts"), "now has content\n")
		fs.writeFileSync(file("src/created.ts"), "new\nfile\n")

		comparison = {
			cwd: repo,
			checkpointRef,
			diffs: [
				{ filePath: file("src/edited.ts"), leftContent: "a\nb\nc\n", rightContent: "a\nB\nc\nd\n" },
				{ filePath: file("src/removed.ts"), leftContent: "gone\n", rightContent: "" },
				{ filePath: file("src/was-empty.ts"), leftContent: "", rightContent: "now has content\n" },
				{ filePath: file("src/created.ts"), leftContent: "", rightContent: "new\nfile\n" },
			],
		}
	})

	afterEach(() => {
		fs.rmSync(repo, { recursive: true, force: true })
	})

	it("reports status and line counts, sorted by path", async () => {
		const files = await summarizeChangedFiles(comparison)

		expect(files.map((f) => [f.relativePath, f.status, f.addedLines, f.removedLines])).toEqual([
			["src/created.ts", ChangedFileStatus.CHANGED_FILE_ADDED, 2, 0],
			["src/edited.ts", ChangedFileStatus.CHANGED_FILE_MODIFIED, 2, 1],
			["src/removed.ts", ChangedFileStatus.CHANGED_FILE_DELETED, 0, 1],
			// Empty at the checkpoint is not the same as new: git still knows the file
			["src/was-empty.ts", ChangedFileStatus.CHANGED_FILE_MODIFIED, 1, 0],
		])
		expect(files[0].path).toBe(file("src/created.ts"))
	})

	it("restores an edited file's checkpoint content", async () => {
		await revertFileToCheckpoint(comparison, findFileDiff(comparison, file("src/edited.ts"))!)

		expect(fs.readFileSync(file("src/edited.ts"), "utf8")).toBe("a\nb\nc\n")
	})

	it("recreates a deleted file", async () => {
		await revertFileToCheckpoint(comparison, findFileDiff(comparison, file("src/removed.ts"))!)

		expect(fs.readFileSync(file("src/removed.ts"), "utf8")).toBe("gone\n")
	})

	it("deletes a file the agent created", async () => {
		await revertFileToCheckpoint(comparison, findFileDiff(comparison, file("src/created.ts"))!)

		expect(fs.existsSync(file("src/created.ts"))).toBe(false)
	})

	it("empties, rather than deletes, a file that was empty at the checkpoint", async () => {
		await revertFileToCheckpoint(comparison, findFileDiff(comparison, file("src/was-empty.ts"))!)

		expect(fs.readFileSync(file("src/was-empty.ts"), "utf8")).toBe("")
	})

	it("leaves the other files alone", async () => {
		await revertFileToCheckpoint(comparison, findFileDiff(comparison, file("src/edited.ts"))!)

		expect(fs.existsSync(file("src/created.ts"))).toBe(true)
		expect(fs.existsSync(file("src/removed.ts"))).toBe(false)
	})

	it("matches paths regardless of how they are spelled", () => {
		expect(findFileDiff(comparison, path.join(repo, "src", "..", "src", "edited.ts"))?.filePath).toBe(file("src/edited.ts"))
		expect(findFileDiff(comparison, file("src/unchanged.ts"))).toBeUndefined()
	})
})
