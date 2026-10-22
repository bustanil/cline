import { afterAll, beforeAll, beforeEach, describe, it, mock } from "bun:test"
import { Controller } from "@core/controller"
import * as actualOpenFileIntegration from "@integrations/misc/open-file"
import { StringRequest } from "@shared/proto/cline/common"
import { FileLocation } from "@shared/proto/cline/file"
import * as actualPathUtils from "@utils/path"
import { expect } from "chai"
import * as fs from "fs"
import * as os from "os"
import * as path from "path"
import * as sinon from "sinon"

// bun loads real ESM, so module exports are replaced with sinon stubs via mock.module.
const openFileIntegrationStub: sinon.SinonStub = sinon.stub()
const getWorkspacePathStub: sinon.SinonStub = sinon.stub()
const getWorkspacePathsStub: sinon.SinonStub = sinon.stub()
const showMessageStub: sinon.SinonStub = sinon.stub()
const pathUtilsMock = () => ({ ...actualPathUtils, getWorkspacePath: getWorkspacePathStub })
mock.module("@integrations/misc/open-file", () => ({ ...actualOpenFileIntegration, openFile: openFileIntegrationStub }))
mock.module("@utils/path", pathUtilsMock)
mock.module("@/utils/path", pathUtilsMock)
mock.module("@/hosts/host-provider", () => ({
	HostProvider: {
		workspace: { getWorkspacePaths: getWorkspacePathsStub },
		window: { showMessage: showMessageStub },
	},
}))

import { openFileLocation } from "../openFileLocation"
import { resolveFileReference } from "../resolveFileReference"

describe("file location handlers", () => {
	const controller = {} as Controller
	let activeRoot: string
	let otherRoot: string

	beforeAll(() => {
		activeRoot = fs.mkdtempSync(path.join(os.tmpdir(), "loc-active-"))
		otherRoot = fs.mkdtempSync(path.join(os.tmpdir(), "loc-other-"))
		fs.mkdirSync(path.join(otherRoot, "lib"))
		fs.writeFileSync(path.join(otherRoot, "lib", "util.ts"), "")
	})

	afterAll(() => {
		fs.rmSync(activeRoot, { recursive: true, force: true })
		fs.rmSync(otherRoot, { recursive: true, force: true })
	})

	beforeEach(() => {
		for (const stub of [openFileIntegrationStub, getWorkspacePathStub, getWorkspacePathsStub, showMessageStub]) {
			stub.reset()
		}
		getWorkspacePathStub.resolves(activeRoot)
		getWorkspacePathsStub.resolves({ paths: [activeRoot, otherRoot] })
	})

	describe("resolveFileReference", () => {
		it("resolves a path in a non-active workspace root, with its lines", async () => {
			const result = await resolveFileReference(controller, StringRequest.create({ value: "lib/util.ts:10-14" }))

			expect(result).to.deep.equal(
				FileLocation.create({ path: path.join(otherRoot, "lib", "util.ts"), startLine: 10, endLine: 14 }),
			)
		})

		it("returns an empty path when the file does not exist", async () => {
			const result = await resolveFileReference(controller, StringRequest.create({ value: "lib/missing.ts:3" }))

			expect(result.path).to.equal("")
		})

		it("still resolves when the host cannot list workspace folders", async () => {
			getWorkspacePathStub.resolves(otherRoot)
			getWorkspacePathsStub.rejects(new Error("no host"))

			const result = await resolveFileReference(controller, StringRequest.create({ value: "lib/util.ts" }))

			expect(result.path).to.equal(path.join(otherRoot, "lib", "util.ts"))
		})
	})

	describe("openFileLocation", () => {
		it("opens the file and selects the line range", async () => {
			await openFileLocation(controller, FileLocation.create({ path: "lib/util.ts", startLine: 10, endLine: 14 }))

			sinon.assert.calledOnceWithExactly(openFileIntegrationStub, path.join(otherRoot, "lib", "util.ts"), false, false, {
				startLine: 10,
				endLine: 14,
			})
		})

		it("selects a single line when only startLine is given", async () => {
			await openFileLocation(controller, FileLocation.create({ path: "lib/util.ts", startLine: 7 }))

			expect(openFileIntegrationStub.firstCall.args[3]).to.deep.equal({ startLine: 7, endLine: 7 })
		})

		it("opens without a selection when no lines are given", async () => {
			await openFileLocation(controller, FileLocation.create({ path: "lib/util.ts" }))

			expect(openFileIntegrationStub.firstCall.args[3]).to.equal(undefined)
		})

		it("tells the user when the file is missing instead of opening nothing", async () => {
			await openFileLocation(controller, FileLocation.create({ path: "lib/missing.ts" }))

			sinon.assert.notCalled(openFileIntegrationStub)
			sinon.assert.calledOnce(showMessageStub)
			expect(showMessageStub.firstCall.args[0].message).to.contain("lib/missing.ts")
		})
	})
})
