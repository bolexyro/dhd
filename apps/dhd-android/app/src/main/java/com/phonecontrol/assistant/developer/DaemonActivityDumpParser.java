package com.phonecontrol.assistant.developer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DaemonActivityDumpParser {
    private static final Pattern ACTIVITY_DISPLAY_HEADER_PATTERN = Pattern.compile(
            "^\\s*Display\\s*#(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY_FOCUS_MARKER_PATTERN = Pattern.compile(
            "\\b(?:topResumedActivity|mResumedActivity|mCurrentFocus|mFocusedApp)\\b",
            Pattern.CASE_INSENSITIVE);

    private DaemonActivityDumpParser() {}

    static boolean hasFocusedPackage(String output, int expectedDisplayId, String expectedPackage) {
        int currentDisplay = -1;
        Pattern packagePattern = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(expectedPackage) + "(?:/|\\b)");
        for (String line : output.split("\\r?\\n")) {
            Matcher display = ACTIVITY_DISPLAY_HEADER_PATTERN.matcher(line);
            if (display.find()) currentDisplay = Integer.parseInt(display.group(1));
            if (currentDisplay == expectedDisplayId &&
                    ACTIVITY_FOCUS_MARKER_PATTERN.matcher(line).find() &&
                    packagePattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
