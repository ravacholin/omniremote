package com.example.external

import android.content.pm.PackageManager
import android.os.Build

internal class InstoAuthorization(
    private val packages: PackageManager,
    private val packageName: String,
    private val certificate: String
) {
    // An APK's current signer is pinned. Historical/rotated signers and shared UIDs
    // cannot attest which installed package is making a Binder call.
    @Suppress("DEPRECATION")
    fun allows(uid: Int): Boolean {
        if (packageName.isBlank() || certificate.isBlank()) return false
        return try {
            if (packages.getPackagesForUid(uid)?.singleOrNull() != packageName) return false
            val info = packages.getPackageInfo(packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES)
            if (info.applicationInfo?.uid != uid || info.sharedUserId != null) return false
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else info.signatures
            signatures?.singleOrNull()?.let { digest(it.toByteArray()) == certificate } == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
