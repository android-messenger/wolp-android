package org.wolpmessenger.p1

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Stand-in for S2 clientCredentials()/credentialsVersion/okHttpClient(): a non-exportable EC P-256 key
 * in AndroidKeyStore, certified by the stand's device CA (POST enroll.<host>/p1/enroll).
 */
object ClientCreds {
    class Creds(val alias: String, val key: PrivateKey, val chain: Array<X509Certificate>, val serial: String)

    private const val PREFS = "p1-creds"
    @Volatile private var cached: Creds? = null
    @Volatile var credentialsVersion = 0
        private set

    private val ks: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun current(): Creds? = cached

    /** Blocking (network). Loads the stored credential or enrolls a new one. */
    @Synchronized fun ensure(ctx: Context, host: String): Creds {
        cached?.let { return it }
        val p = ctx.getSharedPreferences(PREFS, 0)
        val alias = p.getString("alias", null)
        val chainPem = p.getString("chain", null)
        if (alias != null && chainPem != null && ks.containsAlias(alias)) {
            val key = ks.getKey(alias, null) as PrivateKey
            val chain = parseChain(chainPem)
            if (chain[0].notAfter.time > System.currentTimeMillis()) {
                cached = Creds(alias, key, chain, serialOf(chain[0]))
                credentialsVersion = p.getInt("version", 1)
                P1Log.i("creds: loaded alias=$alias serial=${cached!!.serial} v=$credentialsVersion")
                return cached!!
            }
        }
        return rotate(ctx, host)
    }

    /** Blocking (network). New key + certificate; old key deleted; credentialsVersion++ (S2 §4). */
    @Synchronized fun rotate(ctx: Context, host: String): Creds {
        val alias = "p1-client-${System.currentTimeMillis()}"
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .build()
        )
        val kp = kpg.generateKeyPair()
        val body = JSONObject()
            .put("spki", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .put("label", alias)
            .toString().toRequestBody("application/json".toMediaType())
        val resp = plainClient.newCall(Request.Builder().url("https://enroll.$host/p1/enroll").post(body).build()).execute()
        val json = resp.use { r ->
            check(r.isSuccessful) { "enroll HTTP ${r.code}" }
            JSONObject(r.body!!.string())
        }
        val arr = json.getJSONArray("chain")
        val chainPem = (0 until arr.length()).joinToString("") { arr.getString(it) }
        val chain = parseChain(chainPem)
        val old = cached?.alias
        val p = ctx.getSharedPreferences(PREFS, 0)
        credentialsVersion = p.getInt("version", 0) + 1
        p.edit().putString("alias", alias).putString("chain", chainPem).putInt("version", credentialsVersion).apply()
        cached = Creds(alias, ks.getKey(alias, null) as PrivateKey, chain, json.getString("serial"))
        if (old != null && old != alias) runCatching { ks.deleteEntry(old) }
        P1Log.i("creds: enrolled alias=$alias serial=${cached!!.serial} v=$credentialsVersion (old=$old deleted)")
        return cached!!
    }

    private fun parseChain(pem: String): Array<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            .map { it as X509Certificate }.toTypedArray()

    private fun serialOf(c: X509Certificate) = c.serialNumber.toString(16).uppercase()

    // ---- native HTTP (S2 updateHttpClient()/okHttpClient() stand-ins) ----

    private val trustManager: X509TrustManager by lazy {
        // Platform default = honours network_security_config (trust anchors, pin-set)
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private fun base() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)

    /** No client cert (enroll host). */
    val plainClient: OkHttpClient by lazy { base().build() }

    /** mTLS with the current credential. Rebuilt per call so rotation is picked up. */
    fun mtlsClient(): OkHttpClient {
        val km = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = cached?.alias
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = cached?.alias
            override fun getCertificateChain(alias: String?) = cached?.chain
            override fun getPrivateKey(alias: String?) = cached?.key
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = cached?.let { arrayOf(it.alias) }
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(trustManager), null) }
        return base().sslSocketFactory(ctx.socketFactory, trustManager).build()
    }
}
