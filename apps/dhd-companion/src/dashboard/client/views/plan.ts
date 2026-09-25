import type { CompanionPlanSnapshot } from "../api.js";
import { elements } from "../dom.js";

let renderedPlanSignature: string | undefined;

export function renderPlan(plan: CompanionPlanSnapshot | undefined): void {
  const signature = JSON.stringify(plan ?? null);
  if (signature === renderedPlanSignature) return;
  renderedPlanSignature = signature;

  elements.agentPlan.hidden = !plan;
  elements.toolsTab.classList.toggle("has-plan", Boolean(plan));
  if (!plan) return;

  const completedCount = plan.steps.filter((step) => step.status === "completed").length;
  elements.agentPlanProgress.textContent = plan.steps.length === 0
    ? "No steps"
    : completedCount === plan.steps.length
      ? "Complete"
      : `${completedCount}/${plan.steps.length} complete`;
  elements.agentPlanExplanation.textContent = plan.explanation ?? "";
  elements.agentPlanExplanation.hidden = !plan.explanation;
  elements.agentPlanSteps.replaceChildren();

  for (const [index, step] of plan.steps.entries()) {
    const item = document.createElement("li");
    item.className = `agent-plan-step ${step.status}`;

    const number = document.createElement("span");
    number.className = "agent-plan-step-number font-mono";
    number.textContent = step.status === "completed" ? "✓" : String(index + 1);
    number.setAttribute("aria-hidden", "true");

    const label = document.createElement("span");
    label.className = "agent-plan-step-label";
    label.textContent = step.step;

    const status = document.createElement("span");
    status.className = `agent-plan-step-status font-mono ${step.status}`;
    status.textContent = step.status === "in_progress"
      ? "In progress"
      : step.status === "completed"
        ? "Done"
        : "Pending";

    item.append(number, label, status);
    elements.agentPlanSteps.append(item);
  }
}
