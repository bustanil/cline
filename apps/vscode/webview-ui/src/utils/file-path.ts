// Detects inline code that may be a file path: relative, absolute or ~/, with an optional
// :line, :line:col, :start-end or #Lstart-Lend suffix. The core then checks the file exists.
const FILE_PATH_REGEX = /^(?:~\/)?[\w\-./]+(?<!\/)(?::\d+(?:-\d+|:\d+)?|#L\d+(?:-L?\d+)?)?$/

export function isPotentialFilePath(text: string): boolean {
	return !text.includes("\n") && FILE_PATH_REGEX.test(text)
}
