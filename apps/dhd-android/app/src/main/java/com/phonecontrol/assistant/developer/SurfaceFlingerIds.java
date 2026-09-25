package com.phonecontrol.assistant.developer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SurfaceFlingerIds {
    private static final Pattern DISPLAY_INFO_ID_PATTERN = Pattern.compile(
            "\\b(?:Display\\s+id|displayId|mDisplayId)\\s*[:=]?\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SURFACE_FLINGER_DISPLAY_PATTERN = Pattern.compile(
            "^\\s*(?:Virtual\\s+)?Display\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SURFACE_FLINGER_VIRTUAL_HEADER_PATTERN = Pattern.compile(
            "^\\s*Display\\s+(\\d+)\\s+\\(virtual",
            Pattern.CASE_INSENSITIVE);

    private SurfaceFlingerIds() {}

    static String findLogicalUniqueId(String output, int logicalDisplayId) {
        if (output == null) return null;
        boolean inRequestedDisplay = false;
        for (String line : output.split("\\r?\\n")) {
            Integer id = parseDisplayId(line);
            if (id != null) {
                inRequestedDisplay = id == logicalDisplayId;
            }
            if (!inRequestedDisplay) continue;
            String uniqueId = parseUniqueId(line);
            if (uniqueId != null) return uniqueId;
            if (id != null && id != logicalDisplayId) return null;
        }
        return null;
    }

    private static String parseUniqueId(String line) {
        Matcher quoted = Pattern.compile(
                "\\buniqueId\\s*[\"'=:\\s]+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
                .matcher(line);
        if (quoted.find()) return quoted.group(1);
        Matcher unquoted = Pattern.compile(
                "\\buniqueId\\s*[\"'=:\\s]+([^,}\\s]+)", Pattern.CASE_INSENSITIVE)
                .matcher(line);
        return unquoted.find() ? unquoted.group(1) : null;
    }

    static String findUniqueSurfaceFlingerVirtualDisplayId(
            String output,
            String expectedDisplayName
    ) {
        if (output == null || expectedDisplayName == null || expectedDisplayName.isEmpty()) return null;
        Set<String> candidates = new HashSet<>();
        String currentVirtualId = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher singleLine = Pattern.compile(
                    "^\\s*Display\\s+(\\d+)\\b.*?(?:Virtual\\s+display|DisplayDevice)"
                            + ".*?(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (singleLine.find()) {
                if (expectedDisplayName.equalsIgnoreCase(singleLine.group(2))) {
                    candidates.add(singleLine.group(1));
                }
                currentVirtualId = null;
                continue;
            }
            Matcher header = Pattern.compile(
                    "^\\s*(?:Virtual\\s+)?Display\\s+(\\d+)\\b.*(?:virtual)?",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (header.find() && line.toLowerCase(Locale.ROOT).contains("virtual")) {
                currentVirtualId = header.group(1);
                Matcher inlineName = Pattern.compile(
                        "\\b(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (inlineName.find()) {
                    if (expectedDisplayName.equalsIgnoreCase(inlineName.group(1))) {
                        candidates.add(currentVirtualId);
                    }
                    currentVirtualId = null;
                }
                continue;
            }
            if (currentVirtualId != null) {
                Matcher name = Pattern.compile(
                        "\\b(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (name.find()) {
                    if (expectedDisplayName.equalsIgnoreCase(name.group(1))) {
                        candidates.add(currentVirtualId);
                    }
                    currentVirtualId = null;
                } else if (SURFACE_FLINGER_DISPLAY_PATTERN.matcher(line).find()) {
                    currentVirtualId = null;
                }
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    static String findSurfaceFlingerId(String output, int logicalDisplayId, String uniqueId) {
        if (output == null || uniqueId == null || uniqueId.isEmpty()) return null;
        String currentDisplay = null;
        String uniqueMatch = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher display = SURFACE_FLINGER_DISPLAY_PATTERN.matcher(line);
            if (display.find()) {
                currentDisplay = display.group(1);
            }
            if (currentDisplay != null && line.contains(uniqueId)) {
                if (uniqueMatch != null && !uniqueMatch.equals(currentDisplay)) return null;
                uniqueMatch = currentDisplay;
            }
        }
        return uniqueMatch;
    }

    private static String findSurfaceFlingerLayerStack(String output, int logicalDisplayId) {
        if (output == null) return null;
        String current = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher display = SURFACE_FLINGER_VIRTUAL_HEADER_PATTERN.matcher(line);
            if (display.find()) {
                current = display.group(1);
                continue;
            }
            if (current != null) {
                Matcher stack = Pattern.compile("layerFilter=\\{layerStack=(\\d+)\\b",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (stack.find()) {
                    if (stack.group(1).equals(Integer.toString(logicalDisplayId))) return current;
                    current = null;
                } else if (line.matches("^\\s*(?:Virtual\\s+)?Display\\s+\\d+.*")) {
                    current = null;
                }
            }
        }
        return null;
    }

    private static Integer parseDisplayId(String line) {
        Matcher display = DISPLAY_INFO_ID_PATTERN.matcher(line);
        return display.find() ? Integer.valueOf(display.group(1)) : null;
    }
}
