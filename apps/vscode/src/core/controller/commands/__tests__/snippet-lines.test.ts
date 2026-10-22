import { beforeEach, describe, it, mock } from "bun:test"
import { Controller } from "@core/controller"
import { CommandContext } from "@shared/proto/cline/commands"
import { expect } from "chai"
import * as sinon from "sinon"

// bun loads real ESM, so module exports are replaced with sinon stubs via mock.module.
const sendAddToInputEventStub: sinon.SinonStub = sinon.stub()
mock.module("@/core/mentions", () => ({ getFileMentionFromPath: async () => "@/src/app.ts" }))
mock.module("@/integrations/diagnostics", () => ({ singleFileDiagnosticsToProblemsString: async () => "" }))
mock.module("@/services/telemetry", () => ({ telemetryService: { captureButtonClick: () => {} } }))
mock.module("../../ui/subscribeToAddToInput", () => ({ sendAddToInputEvent: sendAddToInputEventStub }))

import { addToCline } from "../addToCline"
import { explainWithCline } from "../explainWithCline"
import { fixWithCline } from "../fixWithCline"
import { improveWithCline } from "../improveWithCline"

describe("snippet commands include the selected lines", () => {
	let initTask: sinon.SinonStub
	let controller: Controller

	const snippet = (lines: Partial<CommandContext> = {}) =>
		CommandContext.create({
			filePath: "/repo/src/app.ts",
			selectedText: "const a = 1",
			language: "typescript",
			startLine: 12,
			endLine: 20,
			...lines,
		})

	beforeEach(() => {
		sendAddToInputEventStub.reset()
		initTask = sinon.stub().resolves()
		controller = { initTask } as unknown as Controller
	})

	it("add to chat puts the line range next to the file mention", async () => {
		await addToCline(controller, snippet())

		expect(sendAddToInputEventStub.firstCall.args[0]).to.match(/^@\/src\/app\.ts \(lines 12-20\)\n```/)
	})

	it("fix, explain and improve mention the line range", async () => {
		await fixWithCline(controller, snippet())
		await explainWithCline(controller, snippet())
		await improveWithCline(controller, snippet())

		const prompts = initTask.getCalls().map((call) => call.args[0] as string)
		expect(prompts).to.have.length(3)
		for (const prompt of prompts) {
			expect(prompt).to.contain("@/src/app.ts (lines 12-20)")
		}
	})

	it("keeps the old prompt when the host sends no lines", async () => {
		await fixWithCline(controller, snippet({ startLine: undefined, endLine: undefined }))

		expect(initTask.firstCall.args[0]).to.match(/^Fix the following code in @\/src\/app\.ts\n/)
	})
})
