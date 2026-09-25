package com.phonecontrol.assistant.bridge.pairing

import com.phonecontrol.assistant.core.Clock
import com.phonecontrol.assistant.core.DeviceInfo
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PendingCompanionPairing(
    val requestId: String,
    val deviceId: String,
    val desktopName: String,
    val expiresAtEpochMs: Long,
)

internal sealed interface PairingStep {
    data object Ignored : PairingStep
    data class Reply(val response: JSONObject) : PairingStep
    data class AwaitingApproval(val response: JSONObject, val requestId: String) : PairingStep
}

internal data class PairingDelivery<P>(val peer: P, val response: JSONObject)

internal class PairingProtocol<P : Any>(
    private val deviceId: String,
    private val authenticationToken: String,
    private val listeningPort: Int,
    private val deviceInfo: DeviceInfo,
    private val clock: Clock,
    private val newUuid: () -> UUID,
    private val lanAddresses: () -> List<String>,
) {
    private val pairingStateLock = Any()
    private val discoveryNonces = LinkedHashMap<String, Long>()
    private val completedPairingResponses = LinkedHashMap<String, CompletedCompanionPairingResponse>()
    @Volatile private var pendingCompanionPairingRequest: PendingCompanionPairingRequest<P>? = null
    private val _pendingCompanionPairing = MutableStateFlow<PendingCompanionPairing?>(null)

    val pendingCompanionPairing: StateFlow<PendingCompanionPairing?> =
        _pendingCompanionPairing.asStateFlow()

    private data class PendingCompanionPairingRequest<P>(
        val publicRequest: PendingCompanionPairing,
        val pairingNonce: String,
        val peer: P,
    )

    private data class CompletedCompanionPairingResponse(
        val pairingNonce: String,
        val expiresAtEpochMs: Long,
        val response: JSONObject,
    )

    fun receive(payload: String, peer: P): PairingStep {
        val request = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return PairingStep.Ignored
        }
        if (request.optInt("version", -1) != PAIRING_PROTOCOL_VERSION) return PairingStep.Ignored
        return when (request.optString("type")) {
            "dhd_discover_request" -> handlePhoneDiscoveryRequest(request)
            "dhd_pair_approval_request" -> handlePairingApprovalRequest(peer, request)
            else -> PairingStep.Ignored
        }
    }

    private fun handlePhoneDiscoveryRequest(request: JSONObject): PairingStep {
        val requestId = request.optString("requestId").trim()
        if (requestId.isBlank()) return PairingStep.Ignored

        val pairingNonce = newUuid().toString().replace("-", "")
        rememberDiscoveryNonce(pairingNonce)
        val response = JSONObject()
            .put("type", "dhd_discover_offer")
            .put("version", PAIRING_PROTOCOL_VERSION)
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("deviceName", companionDeviceName())
            .put("model", deviceInfo.model.trim())
            .put("port", listeningPort)
            .put("pairingNonce", pairingNonce)
        addLanAddresses(response)
        return PairingStep.Reply(response)
    }

    private fun handlePairingApprovalRequest(
        peer: P,
        request: JSONObject,
    ): PairingStep {
        val requestId = request.optString("requestId").trim()
        val candidateDeviceId = request.optString("deviceId").trim()
        val pairingNonce = request.optString("pairingNonce").trim()
        if (requestId.isBlank() || candidateDeviceId != deviceId || pairingNonce.isBlank()) return PairingStep.Ignored

        val completedResponse = synchronized(pairingStateLock) {
            val now = clock.wallMillis()
            completedPairingResponses.entries.removeIf { (_, value) -> value.expiresAtEpochMs <= now }
            completedPairingResponses[requestId]
                ?.takeIf { it.pairingNonce == pairingNonce }
        }
        if (completedResponse != null) {
            return PairingStep.Reply(completedResponse.response)
        }

        val now = clock.wallMillis()
        val expiresAt = now + PAIRING_APPROVAL_TIMEOUT_MS
        val desktopName = request.optString("desktopName").trim()
            .ifBlank { "DHD Companion" }
            .take(MAX_DESKTOP_NAME_CHARS)
        val publicRequest = PendingCompanionPairing(
            requestId = requestId,
            deviceId = deviceId,
            desktopName = desktopName,
            expiresAtEpochMs = expiresAt,
        )
        val pending = PendingCompanionPairingRequest(
            publicRequest = publicRequest,
            pairingNonce = pairingNonce,
            peer = peer,
        )

        val existing = synchronized(pairingStateLock) { pendingCompanionPairingRequest }
        if (existing != null && existing.publicRequest.expiresAtEpochMs > now) {
            if (existing.publicRequest.requestId == requestId && existing.pairingNonce == pairingNonce) {
                // UDP retries from the same desktop are expected. Re-send the
                // acknowledgement instead of turning a lost packet into a
                // false pairing rejection.
                return PairingStep.Reply(
                    JSONObject()
                        .put("type", "dhd_pair_approval_pending")
                        .put("version", PAIRING_PROTOCOL_VERSION)
                        .put("requestId", requestId)
                        .put("deviceId", deviceId)
                        .put("expiresAtEpochMs", existing.publicRequest.expiresAtEpochMs),
                )
            }
            return PairingStep.Reply(
                approvalRejection(requestId, "Another desktop pairing request is already waiting for approval."),
            )
        }

        if (!consumeDiscoveryNonce(pairingNonce)) {
            return PairingStep.Reply(
                approvalRejection(requestId, "This discovery request has expired. Refresh the phone list and try again."),
            )
        }

        synchronized(pairingStateLock) {
            pendingCompanionPairingRequest = pending
            _pendingCompanionPairing.value = publicRequest
        }

        return PairingStep.AwaitingApproval(
            response = JSONObject()
                .put("type", "dhd_pair_approval_pending")
                .put("version", PAIRING_PROTOCOL_VERSION)
                .put("requestId", requestId)
                .put("deviceId", deviceId)
                .put("expiresAtEpochMs", expiresAt),
            requestId = requestId,
        )
    }

    fun expireApproval(requestId: String) {
        synchronized(pairingStateLock) {
            if (pendingCompanionPairingRequest?.publicRequest?.requestId == requestId) {
                pendingCompanionPairingRequest = null
                _pendingCompanionPairing.value = null
            }
        }
    }

    fun respond(approved: Boolean): PairingDelivery<P>? {
        val pending = synchronized(pairingStateLock) {
            val current = pendingCompanionPairingRequest
            if (current == null || current.publicRequest.expiresAtEpochMs <= clock.wallMillis()) {
                pendingCompanionPairingRequest = null
                _pendingCompanionPairing.value = null
                null
            } else {
                pendingCompanionPairingRequest = null
                _pendingCompanionPairing.value = null
                current
            }
        } ?: return null

        val response = if (approved) {
            JSONObject()
                .put("type", "dhd_pair_approval_offer")
                .put("version", PAIRING_PROTOCOL_VERSION)
                .put("requestId", pending.publicRequest.requestId)
                .put("deviceId", deviceId)
                .put("port", listeningPort)
                .put("token", authenticationToken)
                .also(::addLanAddresses)
        } else {
            approvalRejection(
                pending.publicRequest.requestId,
                "The phone declined the desktop companion pairing request.",
            )
        }
        synchronized(pairingStateLock) {
            completedPairingResponses[pending.publicRequest.requestId] = CompletedCompanionPairingResponse(
                pairingNonce = pending.pairingNonce,
                expiresAtEpochMs = clock.wallMillis() + COMPLETED_PAIRING_RESPONSE_TTL_MS,
                response = response,
            )
            while (completedPairingResponses.size > MAX_COMPLETED_PAIRING_RESPONSES) {
                completedPairingResponses.remove(completedPairingResponses.keys.first())
            }
        }
        return PairingDelivery(pending.peer, response)
    }

    fun clear() {
        synchronized(pairingStateLock) {
            pendingCompanionPairingRequest = null
            _pendingCompanionPairing.value = null
            discoveryNonces.clear()
            completedPairingResponses.clear()
        }
    }

    private fun addLanAddresses(response: JSONObject) {
        val addresses = JSONArray()
        lanAddresses().forEach(addresses::put)
        response.put("addresses", addresses)
    }

    private fun approvalRejection(requestId: String, message: String): JSONObject = JSONObject()
        .put("type", "dhd_pair_approval_rejected")
        .put("version", PAIRING_PROTOCOL_VERSION)
        .put("requestId", requestId)
        .put("deviceId", deviceId)
        .put("message", message)

    private fun rememberDiscoveryNonce(nonce: String) {
        val now = clock.wallMillis()
        synchronized(pairingStateLock) {
            discoveryNonces.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            discoveryNonces[nonce] = now + DISCOVERY_NONCE_TTL_MS
            while (discoveryNonces.size > MAX_DISCOVERY_NONCES) {
                discoveryNonces.remove(discoveryNonces.keys.first())
            }
        }
    }

    private fun consumeDiscoveryNonce(nonce: String): Boolean {
        val now = clock.wallMillis()
        synchronized(pairingStateLock) {
            discoveryNonces.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
            return discoveryNonces.remove(nonce)?.let { it > now } == true
        }
    }

    private fun companionDeviceName(): String {
        val manufacturer = deviceInfo.manufacturer.trim()
        val model = deviceInfo.model.trim()
        return listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .ifBlank { "DHD phone" }
    }

    companion object {
        const val PAIRING_PROTOCOL_VERSION = 1
        const val PAIRING_APPROVAL_TIMEOUT_MS = 60_000L
        const val DISCOVERY_NONCE_TTL_MS = 90_000L
        const val MAX_DISCOVERY_NONCES = 32
        const val COMPLETED_PAIRING_RESPONSE_TTL_MS = 10_000L
        const val MAX_COMPLETED_PAIRING_RESPONSES = 16
        const val MAX_DESKTOP_NAME_CHARS = 80
    }
}
