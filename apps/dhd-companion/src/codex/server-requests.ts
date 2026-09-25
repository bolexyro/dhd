import { asRecord } from "../shared/guards.js";

export function emptyToolAnswers(
  value: unknown,
): Record<string, { answers: string[] }> {
  const questions = asRecord(value ?? {})?.questions;
  if (!Array.isArray(questions)) return {};
  const answers: Record<string, { answers: string[] }> = {};
  for (const question of questions) {
    const id = asRecord(question)?.id;
    if (typeof id === "string" && id) answers[id] = { answers: [] };
  }
  return answers;
}

export type ServerRequestAnswer =
  | { result: unknown }
  | { error: { code: number; message: string } };

export function answerServerRequest(method: string, params: unknown): ServerRequestAnswer {
  switch (method) {
    case "item/commandExecution/requestApproval":
      console.error(
        "[codex-app-server] declined a command approval; phone turns may only use typed phone tools",
      );
      return { result: { decision: "decline" } };
    case "item/fileChange/requestApproval":
      console.error(
        "[codex-app-server] declined a file-change approval; the phone companion is not a coding host",
      );
      return { result: { decision: "decline" } };
    case "item/tool/requestUserInput":
      console.error(
        "[codex-app-server] answered tool user-input request with empty answers",
      );
      return { result: { answers: emptyToolAnswers(params) } };
    case "item/permissions/requestApproval":
      console.error(
        "[codex-app-server] declined an additional permission request",
      );
      return {
        result: {
          permissions: { network: null, fileSystem: null },
          scope: "turn",
        },
      };
    case "mcpServer/elicitation/request":
      console.error(
        "[codex-app-server] declined an MCP elicitation request",
      );
      return { result: { action: "decline", content: null } };
    case "account/chatgptAuthTokens/refresh":
      return {
        error: {
          code: -32001,
          message: "The phone companion does not manage ChatGPT auth token refresh.",
        },
      };
    case "attestation/generate":
      return {
        error: {
          code: -32001,
          message: "The phone companion does not provide upstream attestation.",
        },
      };
    default:
      console.error(
        `[codex-app-server] unsupported server request: ${method}`,
      );
      return {
        error: {
          code: -32601,
          message: `Unsupported App Server request: ${method}`,
        },
      };
  }
}
