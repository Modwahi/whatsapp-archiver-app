package com.modwahi.waarchiver.ui

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch
import com.modwahi.waarchiver.io.GoogleAuth

/** واجهة تتحكم بها الشاشات: اختيار المجلدات، تسجيل الدخول، وصلاحيات النظام. */
interface Actions {
    fun pickWhatsAppFolder()
    fun pickLocalFolder()
    fun signInGoogle()
    fun exportLogs()
    fun requestBatteryExemption()
    fun requestNotifications()
    fun requestLegacyStorage()
    fun openUrl(url: String)
    fun requestContacts()
    fun openDownloads()
    fun openAppSettings()
}

class MainActivity : ComponentActivity(), Actions {
    private val vm: AppViewModel by viewModels()

    private lateinit var pickWa: ActivityResultLauncher<Uri?>
    private lateinit var pickLocal: ActivityResultLauncher<Uri?>
    private lateinit var chooseAccount: ActivityResultLauncher<Intent>
    private lateinit var authResolution: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var createLog: ActivityResultLauncher<String>
    private lateinit var permission: ActivityResultLauncher<String>
    private lateinit var contactsPermission: ActivityResultLauncher<String>
    private var pendingAccount: String? = null

    /** مكان مجلد واتساب: Android/media/com.whatsapp/WhatsApp (يُفتح منتقي الملفات عليه مباشرة) */
    private val waInitial: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Android/media/com.whatsapp/WhatsApp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pickWa = registerForActivityResult(OpenTree(readOnly = true)) { uri ->
            if (uri != null) launchIo { vm.setWaFolder(uri)?.let { vm.message = it } }
        }
        pickLocal = registerForActivityResult(OpenTree(readOnly = false)) { uri ->
            if (uri != null) vm.setLocalFolder(uri)
        }
        chooseAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val email = r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return@registerForActivityResult
            pendingAccount = email
            authorize(email)
        }
        authResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            try {
                val res = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data)
                if (res.accessToken != null) onAuthorized() else vm.message = "لم تكتمل الموافقة على Google Drive"
            } catch (e: Exception) {
                vm.message = "لم تكتمل الموافقة على Google Drive: ${e.message}"
            }
        }
        createLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) launchIo { vm.message = vm.exportLogs(uri) ?: "تم تصدير السجل" }
        }
        permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.reload() }
        contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.updateImportContacts(granted)
            if (!granted) vm.message = "لم تسمح بقراءة جهات الاتصال، فلن تُستخدم أسماؤها."
        }

        setContent {
            AppTheme(vm.themeMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root(vm, this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
    }

    private fun launchIo(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun authorize(email: String) {
        GoogleAuth.authorize(this, email)
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) {
                    authResolution.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else onAuthorized()
            }
            .addOnFailureListener { e -> vm.message = "تعذّر تسجيل الدخول إلى Google: ${e.message}" }
    }

    private fun onAuthorized() {
        val email = pendingAccount ?: return
        vm.chooseAccount(email)
        launchIo {
            val err = vm.checkDrive()
            vm.message = err?.let { "تم تسجيل الدخول، لكن تعذّر الوصول إلى Drive:\n$it" }
                ?: "تم الاتصال بـ Google Drive وإنشاء مجلد التطبيق ✓"
        }
    }

    override fun pickWhatsAppFolder() = pickWa.launch(waInitial)
    override fun pickLocalFolder() = pickLocal.launch(null)

    override fun signInGoogle() {
        @Suppress("DEPRECATION")
        val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        chooseAccount.launch(intent)
    }

    override fun exportLogs() = createLog.launch("سجل-أرشيف-واتساب.txt")

    @SuppressLint("BatteryLife")
    override fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            vm.message = "التطبيق مستثنى من تحسين البطارية ✓"
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun requestLegacyStorage() {
        if (Build.VERSION.SDK_INT < 29) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun requestContacts() = contactsPermission.launch(Manifest.permission.READ_CONTACTS)

    override fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun openDownloads() {
        runCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

/** منتقي مجلد مع صلاحية دائمة (قراءة فقط لمجلد واتساب). */
class OpenTree(private val readOnly: Boolean) : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (readOnly) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
        }
}
