package soy.engindearing.omnitak.mobile.data.net

import android.util.Log
import soy.engindearing.omnitak.mobile.data.CaTrust
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Single source of truth for TLS policy on every TAK-server-facing
 * connection — the CoT streaming socket ([soy.engindearing.omnitak.mobile.data.TAKConnection]),
 * the Marti REST client ([soy.engindearing.omnitak.mobile.data.TakRestApiClient]),
 * and CSR enrollment ([soy.engindearing.omnitak.mobile.data.CSREnrollmentService]).
 *
 * Trust resolution (strictest available wins):
 *  1. **Enrollment CA pin** — `TAKServer.caCertificateName` → [CertVault] →
 *     [CaTrust.trustManagerFor]. The pin written during Quick Connect is the
 *     per-server root of trust; a chain that validates against it IS the
 *     server's identity, so hostname verification is satisfied by the pin
 *     (TAK servers are routinely addressed by IP or internal names that
 *     never appear in their cert SANs).
 *  2. **System trust store** + hostname verification — for servers with
 *     publicly-trusted certs (Let's Encrypt etc.) and legacy `.p12` imports
 *     without a pin.
 *  3. **Explicit bypass** — trust-all + accept-all-hostnames, ONLY when the
 *     operator set `TAKServer.allowUntrustedTls` (default false), or during
 *     first-contact CSR enrollment with the trust-self-signed switch ON
 *     (a one-time TOFU window; the CA chain captured there becomes the pin).
 *
 * Replaces the per-file `TrustAll` / `AcceptAllHostnames` objects that used
 * to live in TakRestApiClient and CSREnrollmentService — the REST plane had
 * silently kept trust-all long after the streaming socket moved to CA
 * pinning. Keeping the policy here means it cannot diverge again.
 */
object TakTls {

    private const val TAG = "TakTls"

    /**
     * Resolved server-trust policy for one connection attempt.
     *
     * [verifiesHostname] is true only on the system-trust path: the pin is
     * a stronger, server-specific identity than a hostname match, and the
     * explicit bypass skips verification by definition.
     */
    class ServerTrust internal constructor(
        val trustManager: X509TrustManager,
        /** Trust is anchored to the per-server enrollment CA pin. */
        val pinned: Boolean,
        /** Operator explicitly disabled validation for this server. */
        val untrusted: Boolean,
    ) {
        val verifiesHostname: Boolean get() = !pinned && !untrusted
    }

    /**
     * Build the server-trust policy for [server]: pinned CA when one was
     * captured at enrollment, otherwise system trust — and trust-all ONLY
     * when the operator explicitly opted out via [TAKServer.allowUntrustedTls].
     */
    fun serverTrust(server: TAKServer, certVault: CertVault?): ServerTrust {
        if (server.allowUntrustedTls) {
            Log.w(TAG, "TLS trust: VALIDATION DISABLED for ${server.host} (allowUntrustedTls=true)")
            return ServerTrust(InsecureTrustManager, pinned = false, untrusted = true)
        }
        val chain = pinnedChain(server.caCertificateName, certVault)
        if (chain != null) {
            Log.i(TAG, "TLS trust: pinned CA(s) from '${server.caCertificateName}' (${chain.size} cert)")
            return ServerTrust(CaTrust.trustManagerFor(chain), pinned = true, untrusted = false)
        }
        Log.i(TAG, "TLS trust: system trust store (no CA pin for ${server.host})")
        return ServerTrust(CaTrust.systemTrustManager(), pinned = false, untrusted = false)
    }

    /**
     * Client KeyManagers from the operator-imported `.p12`, or null when the
     * server has no cert configured / the vault is missing / the load fails.
     * Null falls through to TLS without client auth (most TAK servers reject
     * that at handshake, but non-mTLS deployments exist).
     */
    fun clientKeyManagers(
        certificateName: String?,
        certificatePassword: String?,
        certVault: CertVault?,
    ): Array<KeyManager>? {
        val name = certificateName ?: return null
        val pass = certificatePassword ?: return null
        val vault = certVault ?: run {
            Log.w(TAG, "Cert configured but no vault available — skipping client auth.")
            return null
        }
        val bytes = vault.read(name) ?: run {
            Log.w(TAG, "Cert '$name' missing from vault — skipping client auth.")
            return null
        }
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(bytes).use { ks.load(it, pass.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pass.toCharArray())
            kmf.keyManagers
        }.onFailure {
            Log.w(TAG, "Cert load failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** SSLContext wiring [keyManagers] (nullable) to [trustManager]. */
    fun sslContext(keyManagers: Array<KeyManager>?, trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

    /**
     * Apply the full per-server policy (mTLS identity + server trust +
     * hostname verification) to an [HttpsURLConnection]. This is the one
     * call REST-plane code should make — no local SSLContext rituals.
     */
    fun configure(conn: HttpsURLConnection, server: TAKServer, certVault: CertVault?) {
        val keyManagers = clientKeyManagers(
            server.certificateName, server.certificatePassword, certVault,
        )
        val trust = serverTrust(server, certVault)
        conn.sslSocketFactory = sslContext(keyManagers, trust.trustManager).socketFactory
        when {
            // Pin validated the chain at handshake; the pin is the identity.
            trust.pinned -> conn.hostnameVerifier = AcceptAllHostnames
            trust.untrusted -> conn.hostnameVerifier = AcceptAllHostnames
            // System trust: leave the platform default verifier in place.
            else -> Unit
        }
    }

    /** TLS pieces for an OkHttp client; see [forOkHttp]. */
    class OkHttpTls internal constructor(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
        /** Non-null when the platform hostname check must be bypassed (pinned / untrusted). */
        val hostnameVerifier: HostnameVerifier?,
    )

    /**
     * The same per-server policy as [configure], packaged for OkHttp —
     * used by the video player to fetch HLS from the TAK server's own
     * nginx (OpenTAKServer serves `/hls/` with the server certificate
     * signed by its internal CA).
     */
    fun forOkHttp(server: TAKServer, certVault: CertVault?): OkHttpTls {
        val keyManagers = clientKeyManagers(server.certificateName, server.certificatePassword, certVault)
        val trust = serverTrust(server, certVault)
        return OkHttpTls(
            socketFactory = sslContext(keyManagers, trust.trustManager).socketFactory,
            trustManager = trust.trustManager,
            hostnameVerifier = if (trust.verifiesHostname) null else AcceptAllHostnames,
        )
    }

    /**
     * First-contact enrollment bypass (TOFU): trust-all + accept-all
     * hostnames, used ONLY while CSR-enrolling against a server whose
     * internal CA we don't know yet — the CA chain returned by that
     * enrollment becomes the pin for every subsequent connection. Callers
     * must gate this behind an explicit, default-off operator choice.
     */
    fun configureUntrusted(conn: HttpsURLConnection) {
        Log.w(TAG, "TLS trust: VALIDATION DISABLED for ${conn.url.host} (enrollment TOFU bypass)")
        conn.sslSocketFactory = sslContext(null, InsecureTrustManager).socketFactory
        conn.hostnameVerifier = AcceptAllHostnames
    }

    private fun pinnedChain(caCertificateName: String?, certVault: CertVault?): List<X509Certificate>? {
        val caName = caCertificateName ?: return null
        val vault = certVault ?: return null
        val bytes = vault.read(caName) ?: run {
            Log.w(TAG, "CA pin '$caName' missing from vault — falling back to system trust")
            return null
        }
        val chain = runCatching { CaTrust.decodePemChain(bytes) }.getOrElse {
            Log.w(TAG, "CA pin '$caName' parse failed: ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
        if (chain.isEmpty()) {
            Log.w(TAG, "CA pin '$caName' present but yielded no certs — falling back to system trust")
            return null
        }
        return chain
    }

    /**
     * The ONLY trust-all manager in the codebase. Private on purpose —
     * reachable solely through [serverTrust] (allowUntrustedTls=true) and
     * [configureUntrusted] (enrollment TOFU), both explicit opt-ins.
     */
    private object InsecureTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object AcceptAllHostnames : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?) = true
    }
}
