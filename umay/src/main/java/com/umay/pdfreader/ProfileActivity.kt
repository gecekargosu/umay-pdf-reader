package com.umay.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * v13 (T9d) — Kullanıcı profili.
 *
 * Ad / Üniversite / Bölüm / Sınıf + yerel avatar (galeriden seçilen görsel
 * filesDir/profile/avatar.jpg'e kopyalanır). Her şey YALNIZ bu cihazda
 * saklanır (AppPrefs KEY_PROFILE JSON). Google Sign-In / sosyal eşleştirme
 * için sunucu + Play Console gerekir — bilinçli sınır olarak raporlanır.
 */
class ProfileActivity : AppCompatActivity() {

    private val avatarFile: File get() = File(File(filesDir, "profile"), "avatar.jpg")

    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input) ?: return@registerForActivityResult
                    // 512px'e küçült (bellek + disk dostu)
                    val size = 512
                    val scale = minOf(size.toFloat() / bmp.width, size.toFloat() / bmp.height, 1f)
                    val out = Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1), true
                    )
                    avatarFile.parentFile?.mkdirs()
                    avatarFile.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    if (out !== bmp) bmp.recycle()
                    showAvatar()
                }
            } catch (t: Throwable) {
                BookStore.logError(this, "pickAvatar", t)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        EdgeToEdge.apply(this, findViewById(android.R.id.content))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val name = findViewById<EditText>(R.id.inputName)
        val uni = findViewById<EditText>(R.id.inputUniversity)
        val dept = findViewById<EditText>(R.id.inputDepartment)
        val classSpin = findViewById<Spinner>(R.id.classSpinner)
        val avatar = findViewById<ImageView>(R.id.avatarImage)

        classSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            arrayOf("Hazırlık", "1. Sınıf", "2. Sınıf", "3. Sınıf", "4. Sınıf", "Yüksek Lisans", "Doktora")
        )

        // Kayıtlı profili yükle
        try {
            val raw = AppPrefs.getString(this, AppPrefs.KEY_PROFILE)
            if (!raw.isNullOrBlank()) {
                val o = JSONObject(raw)
                name.setText(o.optString("name"))
                uni.setText(o.optString("university"))
                dept.setText(o.optString("department"))
                val cls = o.optString("class")
                val idx = (classSpin.adapter as ArrayAdapter<String>).getPosition(cls)
                if (idx >= 0) classSpin.setSelection(idx)
            }
        } catch (_: Throwable) {
        }
        showAvatar()

        findViewById<TextView>(R.id.btnPickAvatar).setOnClickListener {
            pickAvatar.launch("image/*")
        }

        // ── v13 (AGENT 22 — A3) — Oturum durumu + ÇIKIŞ YAP ──
        val loginStatus = findViewById<TextView>(R.id.loginStatus)
        val authBlock = findViewById<android.view.View>(R.id.authBlock)
        val emailInput = findViewById<EditText>(R.id.inputAuthEmail)
        val passInput = findViewById<EditText>(R.id.inputAuthPassword)

        // v16 (YENİ GÖREV 8) — oturum durumu + giriş bloğu görünürlüğü
        fun paintSession() {
            val p = AppPrefs.getString(this, AppPrefs.KEY_SOCIAL_PROVIDER).orEmpty()
            val n = AppPrefs.getString(this, AppPrefs.KEY_SOCIAL_NAME).orEmpty()
            loginStatus.text = if (p.isNotBlank()) {
                getString(R.string.social_logged_in, n)
            } else {
                getString(R.string.social_not_logged_in)
            }
            authBlock.visibility =
                if (p.isNotBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }
        paintSession()

        fun startSession(provider: String, name: String) {
            AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_PROVIDER, provider)
            AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_NAME, name)
            paintSession()
        }

        /** Yerel hesap için SHA-256 özet (yalnız bu cihazda saklanır). */
        fun sha(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun doAuth(register: Boolean) {
            val email = emailInput.text.toString().trim().lowercase()
            val pass = passInput.text.toString()
            if (!email.contains("@") || !email.substringAfter("@", "").contains(".")) {
                Toast.makeText(this, R.string.auth_invalid_email, Toast.LENGTH_SHORT).show()
                return
            }
            if (pass.length < 4) {
                Toast.makeText(this, R.string.auth_weak, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val raw = AppPrefs.getString(this, AppPrefs.KEY_AUTH_ACCOUNTS).orEmpty()
                val accounts = if (raw.isBlank()) org.json.JSONObject()
                else org.json.JSONObject(raw)
                val digest = sha("umay|$email|$pass")
                if (register) {
                    // Aynı e-posta varsa şifre güncellenir (yerel hesap)
                    accounts.put(email, digest)
                    AppPrefs.putString(this, AppPrefs.KEY_AUTH_ACCOUNTS, accounts.toString())
                    startSession("email", email)
                    Toast.makeText(this, R.string.auth_register_ok, Toast.LENGTH_SHORT).show()
                } else {
                    val stored = accounts.optString(email, "")
                    if (stored.isEmpty() || stored != digest) {
                        Toast.makeText(this, R.string.auth_bad_credentials, Toast.LENGTH_SHORT).show()
                        return
                    }
                    startSession("email", email)
                    Toast.makeText(this, R.string.auth_login_ok, Toast.LENGTH_SHORT).show()
                }
                passInput.text.clear()
            } catch (t: Throwable) {
                BookStore.logError(this, "doAuth", t)
            }
        }

        findViewById<TextView>(R.id.btnAuthLogin).setOnClickListener { doAuth(false) }
        findViewById<TextView>(R.id.btnAuthRegister).setOnClickListener { doAuth(true) }
        // v16 (YENİ GÖREV 8) — Google oturumu: gerçek OAuth (Play Console +
        // SHA-256 istemcisi) yerel ortamda yapılandırılamaz → oturum işareti
        // cihazda açılır ve ÇIKIŞ ile kapatılır (bkz. checkpoint notu).
        findViewById<TextView>(R.id.btnAuthGoogle).setOnClickListener {
            startSession("google", "Google")
            Toast.makeText(this, R.string.auth_google_local, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.logout)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.logout_yes) { _, _ ->
                    // Oturumu kapat: sosyal bilgiler + tanıtım bayrağı temizlenir;
                    // kullanıcı İLK KAYIT EKRANINA (Onboarding) döner.
                    AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_PROVIDER, "")
                    AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_NAME, "")
                    AppPrefs.putBool(this, AppPrefs.KEY_ONBOARDING_DONE, false)
                    try {
                        val i = android.content.Intent(this, OnboardingActivity::class.java).apply {
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        }
                        startActivity(i)
                    } catch (t: Throwable) {
                        BookStore.logError(this, "logout", t)
                    }
                    finishAffinity()
                }
                .setNegativeButton(R.string.kapat, null)
                .show()
        }

        findViewById<TextView>(R.id.btnSaveProfile).setOnClickListener {
            try {
                val o = JSONObject()
                o.put("name", name.text.toString().trim())
                o.put("university", uni.text.toString().trim())
                o.put("department", dept.text.toString().trim())
                o.put("class", classSpin.selectedItem?.toString() ?: "")
                AppPrefs.putString(this, AppPrefs.KEY_PROFILE, o.toString())
                Toast.makeText(this, "Profil kaydedildi ✓", Toast.LENGTH_SHORT).show()
                finish()
            } catch (t: Throwable) {
                BookStore.logError(this, "saveProfile", t)
            }
        }
    }

    private fun showAvatar() {
        val avatar = findViewById<ImageView>(R.id.avatarImage)
        try {
            if (avatarFile.exists()) {
                val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)
                if (bmp != null) avatar.setImageBitmap(bmp)
            }
        } catch (_: Throwable) {
        }
    }
}
