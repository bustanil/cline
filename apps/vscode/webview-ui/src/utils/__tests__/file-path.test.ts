import { describe, expect, it } from "vitest"
import { isPotentialFilePath } from "../file-path"

describe("isPotentialFilePath", () => {
	it.each([
		"src/app.ts",
		"README.md",
		"/Users/me/project/src/app.ts",
		"~/.buzz/projects/morefin/skills/morefin-dev-plan/SKILL.md",
		"src/app.ts:12",
		"src/app.ts:12:5",
		"src/app.ts:12-20",
		"src/app.ts#L12",
		"src/app.ts#L12-L20",
	])("accepts %s", (text) => {
		expect(isPotentialFilePath(text)).toBe(true)
	})

	it.each(["src/", "/", "npm run build", "a.ts\nb.ts", "<name>/SKILL.md", "foo()", "src/app.ts:"])("rejects %s", (text) => {
		expect(isPotentialFilePath(text)).toBe(false)
	})
})
