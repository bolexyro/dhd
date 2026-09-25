import { mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  disabledConfiguredMcpOverrides,
  emptyToolAnswers,
  extractDynamicToolFailure,
  normalizeCodexEffort,
  normalizeDynamicArguments,
  parsePollInterval,
  quoteWindowsCommand,
  resolveCodexBin,
} from "../src/assistant-companion.js";
import {
  extractCompanionPlanUpdatedEvent,
  extractText,
  extractThreadId,
  extractTurnError,
  extractTurnId,
} from "../src/codex/extract.js";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("App Server payload extractors", () => {
  it.each([
    [{ thread: { id: "nested" }, threadId: "flat", id: "bare" }, "nested"],
    [{ thread: { id: "" }, threadId: "flat", id: "bare" }, "flat"],
    [{ thread: "not-an-object", id: "bare" }, "bare"],
    [{ threadId: "", id: "" }, null],
    [{}, null],
    [null, null],
    ["thread-string", null],
  ])("extractThreadId(%j) is %j", (value, expected) => {
    expect(extractThreadId(value)).toBe(expected);
  });

  it.each([
    [{ turn: { id: "nested" }, id: "bare" }, "nested"],
    [{ turn: { id: "" }, id: "bare" }, "bare"],
    [{ turn: {} }, null],
    [{ id: 42 }, null],
    [undefined, null],
  ])("extractTurnId(%j) is %j", (value, expected) => {
    expect(extractTurnId(value)).toBe(expected);
  });

  it.each([
    [{ error: { message: "nested" }, turn: { error: { message: "turn" } }, message: "flat" }, "nested"],
    [{ turn: { error: { message: "turn" } }, message: "flat" }, "turn"],
    [{ error: { message: 7 }, message: "flat" }, "flat"],
    [{ error: "text" }, ""],
    [null, ""],
  ])("extractTurnError(%j) is %j", (value, expected) => {
    expect(extractTurnError(value)).toBe(expected);
  });

  it.each([
    [{ delta: "d", text: "t", message: "m" }, "d"],
    [{ text: "t", message: "m" }, "t"],
    [{ message: "m" }, "m"],
    [{ delta: "" }, ""],
    [{ item: { text: "item text", message: "item message" } }, "item text"],
    [{ item: { message: "item message" } }, "item message"],
    [{ item: { text: 1 } }, ""],
    [{}, ""],
    [null, ""],
  ])("extractText(%j) is %j", (value, expected) => {
    expect(extractText(value)).toBe(expected);
  });

  it.each([
    [undefined, { value: {} }],
    [null, { value: {} }],
    [{ query: "tea" }, { value: { query: "tea" } }],
    ['{"query":"tea"}', { value: { query: "tea" } }],
    ["[1,2]", { value: [1, 2] }],
    ["not-json", { value: { __invalidArguments: "not-json" }, rawArguments: "not-json" }],
    [
      `{${"x".repeat(300)}`,
      { value: { __invalidArguments: `{${"x".repeat(239)}` }, rawArguments: `{${"x".repeat(300)}` },
    ],
  ])("normalizeDynamicArguments(%j)", (value, expected) => {
    expect(normalizeDynamicArguments(value)).toEqual(expected);
  });

  it.each([
    [
      [{ type: "inputText", text: JSON.stringify({ message: " Shizuku down ", code: " SHIZUKU ", outcome: " failed " }) }],
      { message: "Shizuku down", code: "SHIZUKU", outcome: "failed" },
    ],
    [
      [{ type: "inputText", text: JSON.stringify({ message: "  ", code: "", outcome: 3 }) }],
      { message: "DHD phone tool failed." },
    ],
    [
      [
        { type: "inputImage", imageUrl: "data:image/png;base64,AAAA" },
        { type: "inputText", text: "not json" },
        { type: "inputText", text: "null" },
        { type: "inputText", text: JSON.stringify({ message: "second" }) },
      ],
      { message: "second" },
    ],
    [[], { message: "DHD phone tool failed." }],
  ])("extractDynamicToolFailure(%j)", (contentItems, expected) => {
    expect(
      extractDynamicToolFailure({
        contentItems: contentItems as Parameters<typeof extractDynamicToolFailure>[0]["contentItems"],
        success: false,
      }),
    ).toEqual(expected);
  });

  it.each([
    [undefined, {}],
    [{ questions: "q" }, {}],
    [{ questions: [{ id: "a" }, { id: "" }, { id: 3 }, null, { id: "b" }] }, { a: { answers: [] }, b: { answers: [] } }],
  ])("emptyToolAnswers(%j)", (value, expected) => {
    expect(emptyToolAnswers(value)).toEqual(expected);
  });

  it("maps plan updates only for the expected turn and known statuses", () => {
    const message = (params: Record<string, unknown>) => ({ method: "turn/plan/updated", params });
    const plan = [{ step: "One", status: "in_progress" }];

    expect(extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan }), "thread", null, 5)).toEqual({
      type: "dhd_plan",
      phase: "updated",
      threadId: "thread",
      turnId: "t",
      steps: [{ step: "One", status: "in_progress" }],
      timestamp: 5,
    });
    expect(extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan, explanation: "" }), "thread", "t", 5))
      .not.toHaveProperty("explanation");
    expect(extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan }), null, null)).toBeNull();
    expect(extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan }), "thread", "other")).toBeNull();
    expect(extractCompanionPlanUpdatedEvent(message({ plan }), "thread", null)).toBeNull();
    expect(extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan: {} }), "thread", null)).toBeNull();
    expect(
      extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan: [{ step: "One", status: "done" }] }), "thread", null),
    ).toBeNull();
    expect(
      extractCompanionPlanUpdatedEvent(message({ turnId: "t", plan: [{ status: "pending" }] }), "thread", null),
    ).toBeNull();
    expect(extractCompanionPlanUpdatedEvent({ method: "turn/started", params: {} }, "thread", null)).toBeNull();
  });
});

describe("companion configuration parsers", () => {
  it.each([
    [undefined, 1_000],
    ["", 1_000],
    ["   ", 1_000],
    ["250", 250],
    [" 60000 ", 60_000],
  ])("parsePollInterval(%j) is %j", (value, expected) => {
    expect(parsePollInterval(value)).toBe(expected);
  });

  it.each([
    ["-5", "PHONE_ASSISTANT_POLL_MS must be a positive integer."],
    ["1.5", "PHONE_ASSISTANT_POLL_MS must be a positive integer."],
    ["249", "PHONE_ASSISTANT_POLL_MS must be between 250 and 60000."],
    ["60001", "PHONE_ASSISTANT_POLL_MS must be between 250 and 60000."],
  ])("parsePollInterval(%j) throws", (value, message) => {
    expect(() => parsePollInterval(value)).toThrow(message);
  });

  it.each([
    [undefined, "high"],
    ["", "high"],
    ["LOW", "low"],
    [" medium ", "medium"],
    ["xhigh", "xhigh"],
    ["max", "max"],
    ["minimal", "high"],
  ])("normalizeCodexEffort(%j) is %j", (value, expected) => {
    expect(normalizeCodexEffort(value)).toBe(expected);
  });

  it.each([
    ["codex", "codex"],
    ["C:\\Program Files\\Codex\\codex.exe", '"C:\\Program Files\\Codex\\codex.exe"'],
    ['"C:\\Program Files\\codex.exe"', '"C:\\Program Files\\codex.exe"'],
    ["a&b", '"a&b"'],
    ['say "hi" now', '"say \\"hi\\" now"'],
    ["features.apps=false", "features.apps=false"],
    ["mcp_servers={}", "mcp_servers={}"],
  ])("quoteWindowsCommand(%j) is %j", (value, expected) => {
    expect(quoteWindowsCommand(value)).toBe(expected);
  });

  it("disables each MCP server configured in the Codex home by name only", () => {
    const home = mkdtempSync(join(tmpdir(), "dhd-codex-config-"));
    expect(disabledConfiguredMcpOverrides(home)).toEqual([]);

    writeFileSync(
      join(home, "config.toml"),
      [
        "[mcp_servers.github]",
        'command = "secret"',
        "[mcp_servers.github.env]",
        "  [mcp_servers.alpha-1]",
        "[mcp_servers.bad name]",
        "[mcp_servers]",
        "# [mcp_servers.commented]",
        "[profiles.mcp_servers.nested]",
      ].join("\r\n"),
    );

    expect(disabledConfiguredMcpOverrides(home)).toEqual([
      "mcp_servers.alpha-1.enabled=false",
      "mcp_servers.github.enabled=false",
    ]);
  });

  it("prefers a configured Codex binary and falls back to PATH", () => {
    vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", "  /usr/local/bin/codex  ");
    expect(resolveCodexBin()).toBe("/usr/local/bin/codex");

    vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", " ");
    if (process.platform !== "win32") expect(resolveCodexBin()).toBe("codex");
  });

  it("picks the newest desktop-installed codex.exe on Windows", () => {
    const localAppData = mkdtempSync(join(tmpdir(), "dhd-local-app-data-"));
    const bin = join(localAppData, "OpenAI", "Codex", "bin");
    for (const [version, modifiedSeconds] of [["1.0.0", 1_000], ["2.0.0", 3_000], ["1.5.0", 2_000]] as const) {
      mkdirSync(join(bin, version), { recursive: true });
      const executable = join(bin, version, "codex.exe");
      writeFileSync(executable, "");
      utimesSync(executable, modifiedSeconds, modifiedSeconds);
    }
    mkdirSync(join(bin, "empty"), { recursive: true });
    vi.stubEnv("PHONE_ASSISTANT_CODEX_BIN", "");
    vi.stubEnv("LOCALAPPDATA", localAppData);
    const platform = vi.spyOn(process, "platform", "get").mockReturnValue("win32");
    try {
      expect(resolveCodexBin()).toBe(join(bin, "2.0.0", "codex.exe"));

      vi.stubEnv("LOCALAPPDATA", join(localAppData, "missing"));
      expect(resolveCodexBin()).toBe("codex");
    } finally {
      platform.mockRestore();
    }
  });
});
