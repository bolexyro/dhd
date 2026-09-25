package com.phonecontrol.assistant.bridge.handlers

import com.phonecontrol.assistant.bridge.BridgePlatform
import com.phonecontrol.assistant.bridge.protocol.BridgeErrorCodes
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_APP_BROWSE_RESULTS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.MAX_APP_QUERY_CHARS
import com.phonecontrol.assistant.bridge.protocol.BridgeLimits.PACKAGE_PATTERN
import com.phonecontrol.assistant.bridge.protocol.buildAllowedAppsResponse
import com.phonecontrol.assistant.bridge.protocol.buildAppDisplayLayoutResponse
import com.phonecontrol.assistant.bridge.protocol.buildBrowseAppsResponse
import com.phonecontrol.assistant.bridge.protocol.errorResponse
import com.phonecontrol.assistant.bridge.transport.BridgeReply
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.session.SessionCoordinator
import java.util.Locale
import org.json.JSONObject

internal class AppCatalogHandlers(
    private val coordinator: SessionCoordinator,
    private val platform: BridgePlatform,
    private val allowedPackagesProvider: () -> Set<String>,
    private val fullAccessProvider: () -> Boolean,
) {
    fun allowedApps(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val fullAccess = fullAccessProvider()
        val includeAll = json.optBoolean("includeAll", false)
        val allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider()
        coordinator.recordPurpose(
            purpose = when {
                includeAll && fullAccess -> "Listing all launchable apps"
                includeAll -> "Listing all allowed launchable apps"
                else -> "Listing allowed apps"
            },
            toolName = ToolNames.LIST_ALLOWED_APPS,
        )
        reply.write(
            buildAllowedAppsResponse(
                requestId = requestId,
                fullAccess = fullAccess,
                includeAll = includeAll,
                allowedPackages = allowedPackages,
                apps = if (includeAll) {
                    platform.launchableApps()
                        .filter { fullAccess || it.packageName in allowedPackages }
                } else {
                    emptyList()
                },
            ),
        )
    }

    fun browseApps(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val query = json.optString("query").trim()
        if (query.isEmpty() || query.length > MAX_APP_QUERY_CHARS) {
            reply.write(
                errorResponse(requestId, "App search requires a query between 1 and $MAX_APP_QUERY_CHARS characters.")
                    .put("code", BridgeErrorCodes.INVALID_APP_QUERY),
            )
            return
        }

        coordinator.recordPurpose(
            purpose = "Browsing installed apps",
            targetDescription = query,
            toolName = ToolNames.BROWSE_APP,
        )

        val fullAccess = fullAccessProvider()
        val allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider()
        val candidates = platform.launchableApps()
            .asSequence()
            .filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
        val returnedApps = candidates.take(MAX_APP_BROWSE_RESULTS)
        reply.write(
            buildBrowseAppsResponse(
                requestId = requestId,
                query = query,
                fullAccess = fullAccess,
                allowedPackages = allowedPackages,
                apps = returnedApps,
                truncated = candidates.size > returnedApps.size,
            ),
        )
    }

    fun setAppDisplayLayout(
        requestId: String,
        json: JSONObject,
        reply: BridgeReply,
    ) {
        val packageName = json.optString("packageName").trim()
        if (!PACKAGE_PATTERN.matches(packageName)) {
            reply.write(
                errorResponse(requestId, "packageName is not a valid Android package name.")
                    .put("code", BridgeErrorCodes.INVALID_PACKAGE),
            )
            return
        }

        val layout = json.optString("layout").trim().lowercase(Locale.ROOT)
        val enabled = when (layout) {
            "full_size" -> true
            "standard" -> false
            else -> {
                reply.write(
                    errorResponse(requestId, "layout must be either full_size or standard.")
                        .put("code", BridgeErrorCodes.INVALID_APP_DISPLAY_LAYOUT),
                )
                return
            }
        }

        val app = platform.launchableApps()
            .firstOrNull { it.packageName == packageName }
        if (app == null) {
            reply.write(
                errorResponse(requestId, "No launchable app matches packageName=$packageName.")
                    .put("code", BridgeErrorCodes.APP_NOT_FOUND),
            )
            return
        }

        val fullAccess = fullAccessProvider()
        val allowed = fullAccess || packageName in allowedPackagesProvider()
        if (!allowed) {
            reply.write(
                errorResponse(requestId, "The app is not allowed for the current DHD access mode.")
                    .put("code", BridgeErrorCodes.APP_NOT_ALLOWED),
            )
            return
        }

        coordinator.recordPurpose(
            purpose = if (enabled) "Saving full-size app layout" else "Restoring standard app layout",
            targetDescription = app.label,
            toolName = ToolNames.SET_APP_DISPLAY_LAYOUT,
        )
        val changed = platform.isFullSizeLayoutEnabled(packageName) != enabled
        platform.setFullSizeLayoutEnabled(packageName, enabled)
        reply.write(
            buildAppDisplayLayoutResponse(
                requestId = requestId,
                packageName = packageName,
                appLabel = app.label,
                layout = layout,
                changed = changed,
            ),
        )
    }
}
