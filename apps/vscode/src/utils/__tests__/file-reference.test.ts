import { afterAll, beforeAll, describe, expect, it } from "bun:test"
import * as fs from "fs"
import * as os from "os"
import * as path from "path"
import { formatLineRange, parseFileReference, resolveReferencedFile } from "../file-reference"

describe("parseFileReference", () => {
	it("returns a plain path without lines", () => {
		expect(parseFileReference("src/app.ts")).toEqual({ path: "src/app.ts" })
	})

	it("parses :line", () => {
		expect(parseFileReference("src/app.ts:12")).toEqual({ path: "src/app.ts", startLine: 12, endLine: undefined })
	})

	it("ignores the column in :line:col", () => {
		expect(parseFileReference("src/app.ts:12:5")).toEqual({ path: "src/app.ts", startLine: 12, endLine: undefined })
	})

	it("parses :start-end", () => {
		expect(parseFileReference("src/app.ts:12-20")).toEqual({ path: "src/app.ts", startLine: 12, endLine: 20 })
	})

	it("parses GitHub-style #Lstart-Lend", () => {
		expect(parseFileReference("src/app.ts#L12-L20")).toEqual({ path: "src/app.ts", startLine: 12, endLine: 20 })
		expect(parseFileReference("src/app.ts#L7")).toEqual({ path: "src/app.ts", startLine: 7, endLine: undefined })
	})

	it("never ends before it starts", () => {
		expect(parseFileReference("a.ts:20-12")).toEqual({ path: "a.ts", startLine: 20, endLine: 20 })
	})

	it("keeps absolute, home and Windows paths intact", () => {
		expect(parseFileReference("/Users/me/notes.md:3")?.path).toBe("/Users/me/notes.md")
		expect(parseFileReference("~/.buzz/skills/x/SKILL.md")?.path).toBe("~/.buzz/skills/x/SKILL.md")
		expect(parseFileReference("C:\\repo\\app.ts")).toEqual({ path: "C:\\repo\\app.ts" })
		expect(parseFileReference("C:\\repo\\app.ts:9")).toEqual({ path: "C:\\repo\\app.ts", startLine: 9, endLine: undefined })
	})

	it("rejects empty and multi-line text", () => {
		expect(parseFileReference("  ")).toBeUndefined()
		expect(parseFileReference("a.ts\nb.ts")).toBeUndefined()
	})
})

describe("resolveReferencedFile", () => {
	let rootA: string
	let rootB: string
	let homeFile: string

	beforeAll(() => {
		rootA = fs.mkdtempSync(path.join(os.tmpdir(), "ref-a-"))
		rootB = fs.mkdtempSync(path.join(os.tmpdir(), "ref-b-"))
		fs.mkdirSync(path.join(rootB, "src"))
		fs.writeFileSync(path.join(rootB, "src", "only-in-b.ts"), "")
		fs.writeFileSync(path.join(rootA, "both.ts"), "")
		fs.writeFileSync(path.join(rootB, "both.ts"), "")
		homeFile = path.join(fs.mkdtempSync(path.join(os.homedir(), ".ref-home-")), "note.md")
		fs.writeFileSync(homeFile, "")
	})

	afterAll(() => {
		fs.rmSync(rootA, { recursive: true, force: true })
		fs.rmSync(rootB, { recursive: true, force: true })
		fs.rmSync(path.dirname(homeFile), { recursive: true, force: true })
	})

	it("finds a relative path in a later workspace root", () => {
		expect(resolveReferencedFile("src/only-in-b.ts", [rootA, rootB])).toBe(path.join(rootB, "src", "only-in-b.ts"))
	})

	it("prefers the first root that has the file", () => {
		expect(resolveReferencedFile("both.ts", [rootA, rootB])).toBe(path.join(rootA, "both.ts"))
	})

	it("uses absolute paths outside every root", () => {
		expect(resolveReferencedFile(homeFile, [rootA])).toBe(homeFile)
	})

	it("expands ~/", () => {
		const fromHome = `~/${path.relative(os.homedir(), homeFile)}`
		expect(resolveReferencedFile(fromHome, [rootA])).toBe(homeFile)
	})

	it("returns undefined for missing files and directories", () => {
		expect(resolveReferencedFile("nope.ts", [rootA, rootB])).toBeUndefined()
		expect(resolveReferencedFile("src", [rootB])).toBeUndefined()
	})
})

describe("formatLineRange", () => {
	it("describes a range, a single line, or nothing", () => {
		expect(formatLineRange(12, 20)).toBe(" (lines 12-20)")
		expect(formatLineRange(7, 7)).toBe(" (line 7)")
		expect(formatLineRange(7)).toBe(" (line 7)")
		expect(formatLineRange(undefined, 9)).toBe("")
		expect(formatLineRange(0, 3)).toBe("")
	})
})
