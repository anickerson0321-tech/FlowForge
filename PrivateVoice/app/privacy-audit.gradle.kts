import java.util.zip.ZipFile

/*
 * Automated privacy audit of the built APK and the app sources.
 * Run: ./gradlew privacyAudit   (report: app/build/reports/privacy-audit.txt)
 * Fails the build on any violation.
 */
tasks.register("privacyAudit") {
    group = "verification"
    description = "Checks the APK and sources for network access, logging, persistence of message content, and exported components."
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/PrivateVoice-debug.apk")
    val desugared = layout.buildDirectory.file("intermediates/desugar/desugared.jar")
    val report = layout.buildDirectory.file("reports/privacy-audit.txt")
    val srcDir = layout.projectDirectory.dir("src/main/kotlin")
    val aapt2 = (findProperty("pv.aapt2") as String?) ?: "/usr/bin/aapt2"
    doLast {
        val lines = ArrayList<String>()
        var failures = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            lines += (if (ok) "PASS  " else "FAIL  ") + name + if (detail.isNotEmpty()) " — $detail" else ""
            if (!ok) failures++
        }
        fun aapt(vararg args: String): String {
            val p = ProcessBuilder(listOf(aapt2) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(); return out
        }
        val apkFile = apk.get().asFile
        val perms = aapt("dump", "permissions", apkFile.absolutePath)
        val manifest = aapt("dump", "xmltree", "--file", "AndroidManifest.xml", apkFile.absolutePath)

        // 1. Permissions
        val forbidden = listOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE", "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        )
        forbidden.forEach { p -> check("APK does not request $p", !perms.contains("name='$p'")) }
        lines += "      requested: " + Regex("name='([^']+)'").findAll(perms).map { it.groupValues[1] }.toList().joinToString(", ")

        // 2. Backups disabled
        check("android:allowBackup=false", Regex("allowBackup\\(0x[0-9a-f]+\\)=false").containsMatchIn(manifest))

        // 3. Exported components are only the launcher and system-bound services guarded by permissions
        val blocks = manifest.split(Regex("(?=\\n\\s+E: (activity|service|receiver|provider) )"))
        for (b in blocks.drop(1)) {
            val name = Regex("android:name\\(0x[0-9a-f]+\\)=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: continue
            val exported = Regex("exported\\(0x[0-9a-f]+\\)=true").containsMatchIn(b.substringBefore("E: intent-filter"))
            if (!exported) { check("$name not exported", true); continue }
            val perm = Regex("android:permission\\(0x[0-9a-f]+\\)=\"([^\"]+)\"").find(b)?.groupValues?.get(1)
            val ok = name.endsWith(".MainActivity") || perm == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" ||
                perm == "android.permission.BIND_QUICK_SETTINGS_TILE"
            check("$name exported safely", ok, perm ?: "launcher activity")
        }

        // 4. No analytics / ads / crash-reporting / cloud SDKs in the dex input
        val sdkPrefixes = listOf(
            "com/google/firebase/", "com/google/android/gms/", "com/crashlytics/", "io/sentry/", "com/bugsnag/",
            "com/facebook/appevents/", "com/amplitude/", "com/mixpanel/", "com/segment/", "com/appsflyer/", "com/adjust/",
            "com/google/ads/", "com/microsoft/appcenter/", "okhttp3/", "retrofit2/", "com/android/volley/",
        )
        val classNames = ZipFile(desugared.get().asFile).use { z -> z.entries().toList().map { it.name } }
        sdkPrefixes.forEach { p -> check("No ${p.trimEnd('/')} classes", classNames.none { it.startsWith(p) }) }

        // 5. Source checks
        val sources = srcDir.asFile.walkTopDown().filter { it.extension == "kt" }.toList()
        fun grep(pattern: Regex, allow: Set<String> = emptySet()): List<String> = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val code = l.substringBefore("//")
                if (pattern.containsMatchIn(code) && f.name !in allow) "${f.name}:${i + 1}" else null
            }
        }
        val logs = grep(Regex("\\bLog\\.[vdiwe]\\(|\\bprintln\\(|\\.printStackTrace\\(|System\\.(out|err)"))
        check("No logging calls in app code", logs.isEmpty(), logs.joinToString())
        val network = grep(Regex("java\\.net\\.|HttpURLConnection|URLConnection|Socket\\(|okhttp|WebView"))
        check("No networking APIs in app code", network.isEmpty(), network.joinToString())
        val files = grep(
            Regex("FileOutputStream|openFileOutput|writeBytes\\(|writeText\\(|createTempFile|SQLiteDatabase|getSharedPreferences|RandomAccessFile\\(.*\"rw\""),
            allow = setOf("VoiceAssignments.kt", "AppSettings.kt"),
        )
        check("Persistent writes only in settings and encrypted voice assignments", files.isEmpty(), files.joinToString())
        // READ_SMS is allowed only for the opt-in "Text Messages" history, requested at runtime from that screen.
        val smsRequests = grep(Regex("Manifest\\.permission\\.READ_SMS"))
        check("READ_SMS is only requested from the opt-in Text Messages flow",
            smsRequests.all { it.startsWith("MainActivity.kt") || it.startsWith("SmsInbox.kt") || it.startsWith("Screens.kt") || it.startsWith("PrivacyController.kt") },
            smsRequests.joinToString())
        val clipboard = grep(Regex("ClipboardManager|setPrimaryClip"))
        check("No clipboard use", clipboard.isEmpty(), clipboard.joinToString())
        val audioFiles = grep(Regex("\\.wav\"|\\.pcm\"|\\.mp3\"|MediaRecorder|synthesizeToFile"))
        check("No audio files written", audioFiles.isEmpty(), audioFiles.joinToString())
        val prefsKeys = File(srcDir.asFile, "com/privatevoice/app/settings/AppSettings.kt").readText()
        check("Settings never store message text", !Regex("put(String|StringSet)\\(\"(text|message|body|content)").containsMatchIn(prefsKeys))

        val header = "PrivateVoice privacy audit — ${java.time.LocalDateTime.now()}\nAPK: ${apkFile.name}\n"
        val text = header + lines.joinToString("\n") + "\n\nResult: " + (if (failures == 0) "ALL CHECKS PASSED" else "$failures FAILURE(S)") + "\n"
        report.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
        println(text)
        if (failures > 0) throw GradleException("Privacy audit failed with $failures issue(s)")
    }
}
