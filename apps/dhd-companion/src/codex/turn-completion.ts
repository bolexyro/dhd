import {
  selectFinalAgentMessageText,
  type AgentMessageState,
  type AgentMessageStreamUpdate,
} from "./agent-messages.js";
import type { PhoneToolFailure } from "./dynamic-tools.js";
import { extractText } from "./extract.js";

export interface TurnResult {
  text: string;
  threadId: string;
  phoneToolFailures: PhoneToolFailure[];
}

function ignoreRejectionBeforeRunTurnAwaits(): void {}

export class TurnCompletion {
  readonly result: Promise<TurnResult>;
  readonly agentMessages = new Map<string, AgentMessageState>();
  nextAgentMessageOrder = 0;
  readonly phoneToolFailures: PhoneToolFailure[] = [];
  resolve!: (result: TurnResult) => void;
  reject!: (error: Error) => void;

  constructor(
    private readonly onAgentMessageDelta?: (update: AgentMessageStreamUpdate) => void,
  ) {
    this.result = new Promise<TurnResult>((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
    this.result.catch(ignoreRejectionBeforeRunTurnAwaits);
  }

  completedResult(params: unknown, threadId: string): TurnResult {
    return {
      text: selectFinalAgentMessageText(this.agentMessages) || extractText(params),
      threadId,
      phoneToolFailures: [...this.phoneToolFailures],
    };
  }

  streamFinalAnswer(state: AgentMessageState | null): void {
    if (
      !state ||
      state.phase !== "final_answer" ||
      !state.text.trim() ||
      !this.onAgentMessageDelta
    ) {
      return;
    }
    this.onAgentMessageDelta({ itemId: state.id, text: state.text });
  }
}
