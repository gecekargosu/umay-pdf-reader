package com.umay.pdfreader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v13 (T9c) — Şifreli GİZLİ KASA.
 *
 * Güvenlik modeli (bilinçli sınır, bkz. OZELLIK_LISTESI §8):
 *  • Kapı: PIN (4–6 hane, PBKDF2-WithHmacSHA256 + 120.000 tur ile hash'lenir)
 *    ve varsa DONANIM biyometriği (BiometricPrompt).
 *  • İçerik: kitabın source.pdf dosyası AES-256/CBC/PKCS5Padding ile şifrelenip
 *    filesDir/vault/<id>.enc dosyasına yazılır; ham dosya SİLİNİR ve kitap
 *    kütüphane raflarından gizlenir (SecureVault.vaultedIds).
 *  • PDFRenderer şifreli PDF'i AÇAMAZ → kasa "erişim korumalı gizli raf"
 *    olarak kurgulandı; "Kütüphaneye Geri Al" ile şifre çözülür ve kitap
 *    yeniden normal kitaplığa eklenir.
 */
object SecureVault {

    private const val PREFS = "umay_vault"
    private const val ITERATIONS = 120_000

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun vaultDir(ctx: Context): File = File(ctx.filesDir, "vault").apply { mkdirs() }

    fun hasPin(ctx: Context): Boolean =
        !prefs(ctx).getString("pin_hash", null).isNullOrBlank()

    private fun saltBytes(ctx: Context): ByteArray {
        val p = prefs(ctx)
        var hex = p.getString("salt", null)
        if (hex.isNullOrBlank()) {
            val rnd = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            hex = rnd.joinToString("") { "%02x".format(it) }
            p.edit().putString("salt", hex).apply()
        }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun setPin(ctx: Context, pin: String) {
        val hash = sha256Hex((pin + saltBytes(ctx).joinToString("") { "%02x".format(it) }).toByteArray())
        prefs(ctx).edit().putString("pin_hash", hash).apply()
    }

    /**
     * v24 — Kullanıcı isteği: kasanın PIN'i **1234**.
     * Eski (bilinmeyen) PIN'lerle kilitli kalınmaması için BİR KEZ
     * varsayılan PIN'e geçirilir. Kasa boşken yapılır; veri kaybı yoktur.
     */
    fun needsDefaultPinMigration(ctx: Context): Boolean =
        !prefs(ctx).getBoolean("pin_v24", false)

    fun applyDefaultPin(ctx: Context, pin: String) {
        setPin(ctx, pin)
        prefs(ctx).edit().putBoolean("pin_v24", true).apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val stored = prefs(ctx).getString("pin_hash", null) ?: return false
        val hash = sha256Hex((pin + saltBytes(ctx).joinToString("") { "%02x".format(it) }).toByteArray())
        return stored == hash
    }

    // ── v25 (H4) — Yanlış PIN'de ARTAN BEKLEME ────────────────
    //  Kasa PIN'i 4 haneli olabildiği için kaba kuvvet (brute force) gerçek
    //  bir tehdit. 3. yanlış denemeden sonra bekleme: 30 sn → 60 → 120 → 240
    //  → en fazla 300 sn. Doğru PIN girilince sayaç sıfırlanır.

    /** Kalan bekleme (ms); 0 = giriş serbest. */
    fun lockRemainingMs(ctx: Context): Long {
        val until = prefs(ctx).getLong("lock_until", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun failCount(ctx: Context): Int = prefs(ctx).getInt("fail_count", 0)

    fun registerWrongPin(ctx: Context) {
        val n = failCount(ctx) + 1
        val delay = when {
            n <= 2 -> 0L
            else -> minOf(30_000L shl (n - 3).coerceAtMost(4), 300_000L)
        }
        prefs(ctx).edit()
            .putInt("fail_count", n)
            .putLong("lock_until", System.currentTimeMillis() + delay)
            .apply()
    }

    fun clearWrongPins(ctx: Context) {
        prefs(ctx).edit().putInt("fail_count", 0).putLong("lock_until", 0L).apply()
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ── Kasa içeriği ─────────────────────────────────────────

    /**
     * v24 — Kasa artık YALNIZ kitap değil; resim / video / belge de tutar.
     * `kind`: book | image | video | doc | other — her tür kendi rafında görünür.
     * Geriye dönük uyum: eski kayıtlarda "kind" yok → "book" kabul edilir
     * ve `bookId` = id olur (kitaplıkla bağ korunur).
     */
    data class Item(
        val id: String,
        val title: String,
        val kind: String = "book",
        val ext: String = "pdf",
        val size: Long = 0L,
        val addedAt: Long = 0L,
        /** kind=book ise kütüphane kitap kimliği (kitaplıktan gizlemek için). */
        val bookId: String? = null,
        /** Kasa dışındaki orijinal yol ("orijinali sil" için). */
        val originalPath: String? = null
    )

    fun items(ctx: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val raw = prefs(ctx).getString("items", null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.optString("kind", "book")
                val id = o.getString("id")
                out.add(
                    Item(
                        id = id,
                        title = o.optString("title", "Kitap"),
                        kind = kind,
                        ext = o.optString("ext", "pdf"),
                        size = o.optLong("size", 0L),
                        addedAt = o.optLong("addedAt", 0L),
                        bookId = o.optString("bookId", if (kind == "book") id else "")
                            .takeIf { it.isNotBlank() },
                        originalPath = o.optString("originalPath", "").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** Yalnız KİTAP kayıtları kitaplıktan gizlenir. */
    fun vaultedIds(ctx: Context): Set<String> =
        try {
            items(ctx).filter { it.kind == "book" }.mapNotNull { it.bookId ?: it.id }.toSet()
        } catch (_: Throwable) { emptySet() }

    fun saveItems(ctx: Context, list: List<Item>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title).put("kind", it.kind)
                        .put("ext", it.ext).put("size", it.size).put("addedAt", it.addedAt)
                        .put("bookId", it.bookId ?: "")
                        .put("originalPath", it.originalPath ?: "")
                )
            }
            prefs(ctx).edit().putString("items", arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun remove(ctx: Context, id: String) {
        val rest = items(ctx).toMutableList()
        rest.removeAll { it.id == id }
        saveItems(ctx, rest)
        try { File(vaultDir(ctx), "$id.enc").delete() } catch (_: Throwable) {}
    }

    // ── Parmak iziyle açılış: PIN'i Android Keystore ile sarmala ──
    //  PIN düz metin olarak ASLA saklanmaz. Keystore anahtarı donanıma bağlıdır
    //  (çıkarılamaz); biyometrik doğrulama geçince PIN çözülür → AES anahtarı
    //  türetilir. Biyometrik başarısız/iptal ise kapı yine PIN'e düşer.

    private const val KS_ALIAS = "umay_vault_pin_wrap"

    private fun wrapKey(ctx: Context): SecretKey? = try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(KS_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) existing else {
            val gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                KS_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            gen.init(spec)
            gen.generateKey()
        }
    } catch (t: Throwable) {
        BookStore.logError(ctx, "vault.wrapKey", t)
        null
    }

    /** Başarılı PIN girişinden sonra çağrılır → sonraki açılışlar parmak iziyle. */
    fun rememberPinForBiometric(ctx: Context, pin: String) {
        try {
            val key = wrapKey(ctx) ?: return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(pin.toByteArray())
            val blob = cipher.iv + ct
            prefs(ctx).edit()
                .putString("pin_wrapped", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
                .apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.rememberPin", t)
        }
    }

    /** Parmak izi doğrulandıktan sonra PIN'i döndürür (yoksa null). */
    fun pinForBiometric(ctx: Context): String? {
        return try {
            val raw = prefs(ctx).getString("pin_wrapped", null)
            if (raw.isNullOrBlank()) return null
            val blob = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
            if (blob.size < 13) return null
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val key = wrapKey(ctx) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct))
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.pinForBiometric", t)
            null
        }
    }

    /** v24 — Biyometrik kaydı geçersiz kıl (PIN değişince / kapatınca). */
    fun forgetBiometricPin(ctx: Context) {
        prefs(ctx).edit().remove("pin_wrapped").apply()
    }

    // ── Şifreleme (AES-256/CBC, IV dosyanın başında) ─────────

    private fun deriveKey(pin: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** source.pdf → vault/<id>.enc (şifreli). Başarıda true. */
    fun encryptToVault(ctx: Context, id: String, pin: String, input: File, outputExt: String = ".enc"): Boolean {
        return try {
            if (!input.exists()) return false
            val key = deriveKey(pin, saltBytes(ctx))
            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            val enc = cipher.doFinal(input.readBytes())
            val out = File(vaultDir(ctx), "$id$outputExt")
            out.parentFile?.mkdirs()
            out.outputStream().use { w ->
                w.write(iv); w.write(enc)
            }
            try { input.delete() } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.encrypt", t)
            false
        }
    }

    /**
     * v24 — MEDYA / BELGE EKLEME. URI'den geçici kopya alınır, AES ile
     * şifrelenir ve geçici kopya silinir. Kaynak URI'ye DOKUNULMAZ;
     * "orijinali sil" ayrı ve açık onaylı bir adımdır.
     */
    fun addUriToVault(
        ctx: Context,
        pin: String,
        uri: Uri,
        title: String,
        kind: String,
        ext: String
    ): Item? {
        val tmp = File(ctx.cacheDir, "vault_in_${System.currentTimeMillis()}_$ext")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            tmp.outputStream().use { out -> input.use { it.copyTo(out) } }
            // file:// ise gerçek yol saklanır ("kasadan çıkart" oraya yazsın);
            // SAF/content URI'de yol yok → türüne göre uygun klasöre çıkarılır.
            val original = if (uri.scheme == "file") uri.path else null
            addFileToVault(ctx, pin, tmp, title, kind, ext, original)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.addUri", t)
            try { tmp.delete() } catch (_: Throwable) {}
            null
        }
    }

    /** Dosya yolundan kasaya ekleme (cihaz testi / paylaş menüsü). */
    fun addFileToVault(
        ctx: Context,
        pin: String,
        src: File,
        title: String,
        kind: String,
        ext: String,
        originalPath: String? = null
    ): Item? {
        return try {
            if (!src.exists() || src.length() == 0L) return null
            val id = "v" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            if (!encryptToVault(ctx, id, pin, src)) return null
            val item = Item(
                id = id,
                title = title,
                kind = kind,
                ext = ext,
                size = try { File(vaultDir(ctx), "$id.enc").length() } catch (_: Throwable) { 0L },
                addedAt = System.currentTimeMillis(),
                originalPath = originalPath ?: src.absolutePath
            )
            val list = items(ctx)
            list.add(item)
            saveItems(ctx, list)
            item
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.addFile", t)
            null
        }
    }

    /** Kasayı açmadan önce çağrılmaz; PIN elde varsa içeriği çözer. */
    fun decryptFileTo(ctx: Context, item: Item, pin: String): File? {
        val dst = File(ctx.cacheDir, "vault_view/${item.id}.${item.ext.ifBlank { "bin" }}")
        return if (decryptTo(ctx, item.id, pin, dst)) dst else null
    }

    /** vault/<id>.enc → verilen dosyaya (şifre çözme). Başarıda true. */
    fun decryptTo(ctx: Context, id: String, pin: String, dst: File): Boolean {
        return try {
            val f = File(vaultDir(ctx), "$id.enc")
            if (!f.exists()) return false
            val all = f.readBytes()
            if (all.size < 17) return false
            val iv = all.copyOfRange(0, 16)
            val data = all.copyOfRange(16, all.size)
            val key = deriveKey(pin, saltBytes(ctx))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val plain = cipher.doFinal(data)
            dst.parentFile?.mkdirs()
            dst.writeBytes(plain)
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "vault.decrypt", t)
            false
        }
    }
}

/**
 * v13 (T9c) — Kasa ekranı: kapı (PIN/biyometrik) → kasa içeriği.
 */
