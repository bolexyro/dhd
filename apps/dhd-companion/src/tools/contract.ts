export { GUARD_REGIONS_FEATURE_FLAG, isGuardRegionsEnabled } from "../config/env.js";

export const DHD_MAX_SEQUENCE_ACTIONS = 16;
export const DHD_MAX_TEXT_CHARS = 240;
export const DHD_MAX_GUARD_REGIONS = 8;
export const DHD_MAX_TYPE_TEXT_CHARS = 4096;
export const DHD_MAX_SWIPE_DURATION_MS = 10_000;
export const DHD_MAX_WAIT_DURATION_MS = 30_000;

export const DHD_ACTION_TYPES = {
  tap: "tap",
  type: "type",
  swipe: "swipe",
  back: "back",
  keypress: "keypress",
  wait: "wait",
} as const;
export const DHD_KEYPRESS_KEYS = ["BACK", "HOME", "ENTER", "DELETE"] as const;

export const DHD_TOOL_NAMES = [
  "dhd_list_allowed_apps",
  "dhd_browse_app",
  "dhd_set_app_display_layout",
  "dhd_list_displays",
  "dhd_close_display",
  "dhd_get_foreground_app",
  "dhd_observe",
  "dhd_open_app",
  "dhd_execute",
  "dhd_execute_sequence",
  "dhd_request_attention",
] as const;

export type DhdToolName = (typeof DHD_TOOL_NAMES)[number];

export function isDhdToolName(value: string): value is DhdToolName {
  return (DHD_TOOL_NAMES as readonly string[]).includes(value);
}
