package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.SourceFamily
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.domain.TrustRecord
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class StoredTrust(
    val record: TrustRecord,
    val profileId: String,
    val sourceName: String,
    val platform: SourcePlatform,
    val family: SourceFamily,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

sealed interface TrustLoadResult {
    data class Found(val trust: StoredTrust) : TrustLoadResult
    data class DifferentPeer(val trust: StoredTrust) : TrustLoadResult
    data object Missing : TrustLoadResult
    data object EncryptionKeysChanged : TrustLoadResult
}

class TrustStore(
    context: Context,
    private val keyManager: DeviceKeyManager = DeviceKeyManager(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(peerId: String, profileId: String): TrustLoadResult {
        val storedProof = preferences.getString(KEY_PROOF, null) ?: return TrustLoadResult.Missing
        val currentProof = runCatching(keyManager::currentProof)
            .getOrElse { return TrustLoadResult.EncryptionKeysChanged }
        if (!constantTimeEquals(storedProof, currentProof)) {
            return TrustLoadResult.EncryptionKeysChanged
        }

        val exactPayload = preferences.getString(recordKey(peerId), null)
        if (exactPayload != null) {
            return decrypt(exactPayload)?.let { TrustLoadResult.Found(it) }
                ?: TrustLoadResult.EncryptionKeysChanged
        }

        val previousPeerId = preferences.getString(profileKey(profileId), null)
            ?: return TrustLoadResult.Missing
        val previousPayload = preferences.getString(recordKey(previousPeerId), null)
            ?: return TrustLoadResult.Missing
        return decrypt(previousPayload)?.let { TrustLoadResult.DifferentPeer(it) }
            ?: TrustLoadResult.EncryptionKeysChanged
    }

    fun save(trust: StoredTrust) {
        val encrypted = keyManager.encrypt(trust.toJson().toString().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_PROOF, keyManager.currentProof())
            .putString(recordKey(trust.record.peerDeviceId), encrypted)
            .putString(profileKey(trust.profileId), trust.record.peerDeviceId)
            .apply()
    }

    fun updateCategories(trust: StoredTrust, categories: Set<ConsentCategory>): StoredTrust {
        val updated = trust.copy(
            record = trust.record.copy(authorizedCategories = categories),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        save(updated)
        return updated
    }

    fun revoke(trust: StoredTrust): StoredTrust {
        val now = System.currentTimeMillis()
        val revoked = trust.copy(
            record = trust.record.copy(revokedAtEpochMillis = now),
            updatedAtEpochMillis = now,
        )
        save(revoked)
        return revoked
    }

    private fun decrypt(payload: String): StoredTrust? = runCatching {
        val json = JSONObject(keyManager.decrypt(payload).toString(Charsets.UTF_8))
        json.toStoredTrust()
    }.getOrNull()

    private fun StoredTrust.toJson(): JSONObject = JSONObject().apply {
        put("peerDeviceId", record.peerDeviceId)
        put("localDeviceId", record.localDeviceId)
        put("encryptionKeyProof", record.encryptionKeyProof)
        put("authorizedCategories", JSONArray(record.authorizedCategories.map { it.name }))
        put("revokedAtEpochMillis", record.revokedAtEpochMillis ?: JSONObject.NULL)
        put("profileId", profileId)
        put("sourceName", sourceName)
        put("platform", platform.name)
        put("family", family.name)
        put("createdAtEpochMillis", createdAtEpochMillis)
        put("updatedAtEpochMillis", updatedAtEpochMillis)
    }

    private fun JSONObject.toStoredTrust(): StoredTrust {
        val categoriesJson = getJSONArray("authorizedCategories")
        val categories = buildSet {
            for (index in 0 until categoriesJson.length()) {
                add(ConsentCategory.valueOf(categoriesJson.getString(index)))
            }
        }
        return StoredTrust(
            record = TrustRecord(
                peerDeviceId = getString("peerDeviceId"),
                localDeviceId = getString("localDeviceId"),
                encryptionKeyProof = getString("encryptionKeyProof"),
                authorizedCategories = categories,
                revokedAtEpochMillis = if (isNull("revokedAtEpochMillis")) {
                    null
                } else {
                    getLong("revokedAtEpochMillis")
                },
            ),
            profileId = getString("profileId"),
            sourceName = getString("sourceName"),
            platform = SourcePlatform.valueOf(getString("platform")),
            family = SourceFamily.valueOf(getString("family")),
            createdAtEpochMillis = getLong("createdAtEpochMillis"),
            updatedAtEpochMillis = getLong("updatedAtEpochMillis"),
        )
    }

    private fun constantTimeEquals(first: String, second: String): Boolean {
        return MessageDigest.isEqual(
            first.toByteArray(Charsets.UTF_8),
            second.toByteArray(Charsets.UTF_8),
        )
    }

    private fun recordKey(peerId: String) = "record_$peerId"
    private fun profileKey(profileId: String) = "profile_$profileId"

    private companion object {
        const val PREFERENCES_NAME = "trusted_devices"
        const val KEY_PROOF = "encryption_key_proof"
    }
}