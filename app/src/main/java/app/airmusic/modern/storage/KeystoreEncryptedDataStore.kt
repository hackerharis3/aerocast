package app.airmusic.modern.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.airmusic.modern.model.EqPreset
import app.airmusic.modern.model.GlobalSettingsState
import app.airmusic.modern.model.ReceiverDeviceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.airMusicDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "airmusic_modern_secure_datastore"
)

/**
 * Clean-room replacement for AirMusic's 7 XML SharedPreferences stores.
 *
 * Remediates SEC-06 (Plaintext receiver passwords & allowBackup="true"):
 * - Uses a hardware-backed 256-bit AES-GCM key inside AndroidKeyStore ("airmusic_modern_master_aes256")
 *   to encrypt and authenticate all sensitive secrets (AirPlay RTSP passwords, Apple TV Ed25519 keys,
 *   Home Assistant API tokens, and MQTT credentials) with a random 12-byte IV and 128-bit GCM tag.
 */
class KeystoreEncryptedDataStore(private val context: Context) {

    private val dataStore = context.airMusicDataStore

    // 1. Encrypted Secret Storage (AirPlay Passwords, Pairing Keys, Automation Tokens)
    suspend fun putEncryptedSecret(secretId: String, plaintext: String) {
        val cipherTextBase64 = encryptWithKeystoreAesGcm(plaintext)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("secret_$secretId")] = cipherTextBase64
        }
    }

    suspend fun getDecryptedSecret(secretId: String): String? {
        val prefs = dataStore.data.first()
        val encoded = prefs[stringPreferencesKey("secret_$secretId")] ?: return null
        return decryptWithKeystoreAesGcm(encoded)
    }

    suspend fun saveReceiverPassword(receiverId: String, passwordPlaintext: String) {
        putEncryptedSecret("airplay_pwd_$receiverId", passwordPlaintext)
    }

    suspend fun loadReceiverPassword(receiverId: String): String {
        return getDecryptedSecret("airplay_pwd_$receiverId").orEmpty()
    }

    // 2. Per-Receiver Calibration, Alias, Volume & 10-Band EQ Persistence
    suspend fun saveReceiverProfile(receiver: ReceiverDeviceState) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("alias_${receiver.id}")] = receiver.alias
            prefs[intPreferencesKey("vol_${receiver.id}")] = receiver.volumePercent
            prefs[intPreferencesKey("delay_${receiver.id}")] = receiver.delayOffsetMs
            prefs[stringPreferencesKey("codec_${receiver.id}")] = receiver.activeCodec.name
            prefs[booleanPreferencesKey("mono_${receiver.id}")] = receiver.stereoToMono
            prefs[booleanPreferencesKey("eq_on_${receiver.id}")] = receiver.eqEnabled
            prefs[stringPreferencesKey("eq_preset_${receiver.id}")] = receiver.eqPreset.name
            prefs[stringPreferencesKey("eq_gains_${receiver.id}")] =
                receiver.eqBands.joinToString(",") { "%.2f".format(it.gainDb) }
        }
    }

    suspend fun applySavedProfileToReceiver(receiver: ReceiverDeviceState): ReceiverDeviceState {
        val prefs = dataStore.data.first()
        val savedAlias = prefs[stringPreferencesKey("alias_${receiver.id}")] ?: receiver.alias
        val savedVol = prefs[intPreferencesKey("vol_${receiver.id}")] ?: receiver.volumePercent
        val savedDelay = prefs[intPreferencesKey("delay_${receiver.id}")] ?: receiver.delayOffsetMs
        val savedMono = prefs[booleanPreferencesKey("mono_${receiver.id}")] ?: receiver.stereoToMono
        val savedEqOn = prefs[booleanPreferencesKey("eq_on_${receiver.id}")] ?: receiver.eqEnabled
        val savedPresetName = prefs[stringPreferencesKey("eq_preset_${receiver.id}")]
        val savedPreset = savedPresetName?.let {
            runCatching { EqPreset.valueOf(it) }.getOrNull()
        } ?: receiver.eqPreset

        val savedGainsCsv = prefs[stringPreferencesKey("eq_gains_${receiver.id}")]
        val updatedBands = if (!savedGainsCsv.isNullOrBlank()) {
            val parts = savedGainsCsv.split(',').mapNotNull { it.toFloatOrNull() }
            receiver.eqBands.mapIndexed { idx, band ->
                band.copy(gainDb = parts.getOrElse(idx) { band.gainDb })
            }
        } else {
            receiver.eqBands
        }

        return receiver.copy(
            alias = savedAlias,
            volumePercent = savedVol,
            delayOffsetMs = savedDelay,
            stereoToMono = savedMono,
            eqEnabled = savedEqOn,
            eqPreset = savedPreset,
            eqBands = updatedBands
        )
    }

    // 3. Global Settings Persistence Flow
    val globalSettingsFlow: Flow<GlobalSettingsState> = dataStore.data.map { prefs ->
        GlobalSettingsState(
            autoDisconnectSilenceMinutes = prefs[KEY_AUTO_DISCONNECT_MIN] ?: 5,
            adaptiveBitrateOnLowWifi = prefs[KEY_ADAPTIVE_BITRATE] ?: true,
            exposeUpnpMediaServerXboxMode = prefs[KEY_UPNP_XBOX_MODE] ?: false,
            keepLocalStreamVolumeMax = prefs[KEY_KEEP_LOCAL_VOL_MAX] ?: true,
            appLocaleTag = prefs[KEY_APP_LOCALE] ?: "en"
        )
    }

    suspend fun saveGlobalSettings(settings: GlobalSettingsState) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_DISCONNECT_MIN] = settings.autoDisconnectSilenceMinutes
            prefs[KEY_ADAPTIVE_BITRATE] = settings.adaptiveBitrateOnLowWifi
            prefs[KEY_UPNP_XBOX_MODE] = settings.exposeUpnpMediaServerXboxMode
            prefs[KEY_KEEP_LOCAL_VOL_MAX] = settings.keepLocalStreamVolumeMax
            prefs[KEY_APP_LOCALE] = settings.appLocaleTag
        }
    }

    // 4. Hardware-Backed AndroidKeyStore AES-256-GCM Implementation
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithKeystoreAesGcm(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv // 12-byte GCM IV generated by Keystore
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val combined = ByteBuffer.allocate(1 + iv.size + cipherBytes.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(cipherBytes)
            .array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptWithKeystoreAesGcm(encodedBase64: String): String? {
        return try {
            val combined = Base64.decode(encodedBase64, Base64.NO_WRAP)
            val bb = ByteBuffer.wrap(combined)
            val ivLen = (bb.get().toInt() and 0xFF).coerceIn(12, 16)
            val iv = ByteArray(ivLen)
            bb.get(iv)
            val cipherBytes = ByteArray(bb.remaining())
            bb.get(cipherBytes)

            val cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "airmusic_modern_master_aes256"
        private const val TRANSFORMATION_AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private val KEY_AUTO_DISCONNECT_MIN = intPreferencesKey("auto_disconnect_minutes")
        private val KEY_ADAPTIVE_BITRATE = booleanPreferencesKey("adaptive_bitrate_wifi")
        private val KEY_UPNP_XBOX_MODE = booleanPreferencesKey("upnp_xbox_mode")
        private val KEY_KEEP_LOCAL_VOL_MAX = booleanPreferencesKey("keep_local_vol_max")
        private val KEY_APP_LOCALE = stringPreferencesKey("app_locale_tag")
    }
}
