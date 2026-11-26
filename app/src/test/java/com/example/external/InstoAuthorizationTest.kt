package com.example.external

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.security.cert.CertificateFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSigningInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstoAuthorizationTest {
    @Test fun unrecognizedCallerCannotUseExportedBinder() {
        val service = Robolectric.buildService(RemoteCommandService::class.java).create().get()
        val component = ComponentName(service, RemoteCommandService::class.java)
        val api = bind(service)
        assertThrows(SecurityException::class.java) { api.getApiVersion() }
        assertThrows(SecurityException::class.java) { api.listDevices(1, 0) }
        assertThrows(SecurityException::class.java) { api.getDevice(1, 1) }
        assertThrows(SecurityException::class.java) { api.listApps(1, 1, 0) }
        assertThrows(SecurityException::class.java) { api.execute(1, "request-000001", 1, "POWER_ON", "{}") }
        assertThrows(SecurityException::class.java) { api.getExecution(1, "request-000001") }
        assertNull(service.onBind(Intent(RemoteCommandService.BIND_ACTION)))
        val info: ServiceInfo = service.packageManager.getServiceInfo(component, 0)
        assertTrue(info.exported)
        assertFalse(info.directBootAware)
    }

    @Test
    @Config(sdk = [24, 36])
    fun instoDistributionSignerCanCallBinder() {
        val api = bindAs(INSTO_PACKAGE, certificate("external/insto-release-cert.pem"))
        assertEquals(1, api.getApiVersion())
    }

    @Test fun androidDebugSignerCannotCallBinder() {
        val api = bindAs(INSTO_PACKAGE, certificate("external/android-debug-cert.pem"))
        assertThrows(SecurityException::class.java) { api.getApiVersion() }
    }

    @Test fun wrongCurrentSignerCannotCallBinderWithInstoInSigningHistory() {
        val api = bindAs(INSTO_PACKAGE, certificate("expired-tv-cert.der"),
            certificate("external/insto-release-cert.pem"))
        assertThrows(SecurityException::class.java) { api.getApiVersion() }
    }

    @Test fun differentPackageCannotCallBinderWithInstoSigner() {
        val api = bindAs("org.example.other", certificate("external/insto-release-cert.pem"))
        assertThrows(SecurityException::class.java) { api.getApiVersion() }
    }

    @Test
    @Suppress("DEPRECATION")
    fun currentCertificateAndUniquePackageMustBothMatch() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val goodCert = Signature(byteArrayOf(1, 10, 25))
        val badCert = Signature(byteArrayOf(2, 11, 26))
        val name = "org.example.insto"
        val uid = 10942
        val shadow = shadowOf(pm)
        fun install(pkg: String, signer: Signature, sharedId: String? = null) {
            shadow.installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; this.uid = uid }
                signingInfo = SigningInfo().also {
                    Shadow.extract<ShadowSigningInfo>(it).apply {
                        setSignatures(arrayOf(signer))
                        setPastSigningCertificates(arrayOf(goodCert))
                    }
                }
                sharedUserId = sharedId
            })
            shadow.setPackagesForUid(uid, pkg)
        }
        install(name, goodCert)
        val verified = InstoAuthorization(pm, name, digest(goodCert.toByteArray()))
        assertTrue(verified.allows(uid))
        assertFalse(verified.allows(uid + 1))
        assertFalse(InstoAuthorization(pm, "", "").allows(uid))
        shadow.setPackagesForUid(uid, name, "org.example.sibling")
        assertFalse(verified.allows(uid))
        install(name, goodCert, "org.example.shared")
        assertFalse(verified.allows(uid))
        install(name, badCert)
        assertFalse(verified.allows(uid))
        install("org.example.sibling", goodCert)
        assertFalse(verified.allows(uid))
    }

    @Suppress("DEPRECATION")
    private fun bindAs(pkg: String, signer: Signature, pastSigner: Signature? = null): IRemoteCommandService {
        val service = Robolectric.buildService(RemoteCommandService::class.java).create().get()
        val uid = 10942
        val packages = shadowOf(service.packageManager)
        packages.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.uid = uid }
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = SigningInfo().also {
                    Shadow.extract<ShadowSigningInfo>(it).apply {
                        setSignatures(arrayOf(signer))
                        setPastSigningCertificates(listOfNotNull(pastSigner, signer).toTypedArray())
                    }
                }
            } else {
                signatures = arrayOf(signer)
            }
        })
        packages.setPackagesForUid(uid, pkg)
        ShadowBinder.setCallingUid(uid)
        return bind(service)
    }

    private fun bind(service: RemoteCommandService): IRemoteCommandService {
        val intent = Intent(RemoteCommandService.BIND_ACTION)
            .setComponent(ComponentName(service, RemoteCommandService::class.java))
        return IRemoteCommandService.Stub.asInterface(checkNotNull(service.onBind(intent)))
    }

    private fun certificate(resource: String): Signature =
        checkNotNull(javaClass.getResourceAsStream("/$resource")).use {
            Signature(CertificateFactory.getInstance("X.509").generateCertificate(it).encoded)
        }

    private companion object {
        const val INSTO_PACKAGE = "com.aistudio.pingassist.qvrtkm"
    }
}
