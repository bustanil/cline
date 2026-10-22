import * as fs from "fs"
import * as os from "os"
import * as path from "path"

/** A file path written in chat, with the line range it points at, if any. */
export interface FileReference {
	path: string
	// 1-based and inclusive
	startLine?: number
	endLine?: number
}

// Matches a trailing line suffix: ":12", ":12:5" (column ignored), ":12-20", "#L12" or "#L12-L20".
// A Windows drive colon ("C:\...") never matches because it is not followed by digits to the end.
const LINE_SUFFIX_REGEX = /(?::(\d+)(?:-(\d+)|:\d+)?|#L(\d+)(?:-L?(\d+))?)$/

/**
 * Splits chat text such as "src/app.ts:12-20" or "~/notes.md#L4" into a path and line range.
 * Returns undefined for empty text or text spanning several lines.
 */
export function parseFileReference(text: string): FileReference | undefined {
	const trimmed = text.trim()
	if (!trimmed || trimmed.includes("\n")) {
		return undefined
	}

	const match = trimmed.match(LINE_SUFFIX_REGEX)
	if (!match || match.index === 0) {
		return { path: trimmed }
	}

	const startLine = Number(match[1] ?? match[3])
	const endLine = match[2] ?? match[4]
	return {
		path: trimmed.slice(0, match.index),
		startLine,
		endLine: endLine === undefined ? undefined : Math.max(startLine, Number(endLine)),
	}
}

/**
 * Describes a line range for a prompt, e.g. " (lines 12-20)" or " (line 7)".
 * Returns "" when the start line is unknown.
 */
export function formatLineRange(startLine?: number, endLine?: number): string {
	if (!startLine || startLine < 1) {
		return ""
	}
	if (!endLine || endLine <= startLine) {
		return ` (line ${startLine})`
	}
	return ` (lines ${startLine}-${endLine})`
}

function expandHome(filePath: string): string {
	if (filePath === "~") {
		return os.homedir()
	}
	if (filePath.startsWith("~/") || filePath.startsWith("~\\")) {
		return path.join(os.homedir(), filePath.slice(2))
	}
	return filePath
}

function isFile(filePath: string): boolean {
	try {
		return fs.statSync(filePath).isFile()
	} catch {
		return false
	}
}

/**
 * Resolves a referenced path to an existing file. Absolute and ~/ paths are used as is;
 * relative paths are tried against each root in order. Returns undefined when no file matches.
 */
export function resolveReferencedFile(filePath: string, roots: string[]): string | undefined {
	const expanded = expandHome(filePath)
	if (path.isAbsolute(expanded)) {
		return isFile(expanded) ? path.normalize(expanded) : undefined
	}
	for (const root of roots) {
		const candidate = path.resolve(root, expanded)
		if (isFile(candidate)) {
			return candidate
		}
	}
	return undefined
}
