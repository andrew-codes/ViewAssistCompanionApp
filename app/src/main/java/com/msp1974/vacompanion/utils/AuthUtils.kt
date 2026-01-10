package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AuthToken(
        val tokenType: String = "",
        val accessToken: String = "",
        val expires: Long = 0,
        val refreshToken: String = ""
)

class AuthUtils(val config: APPConfig) {
    val log = Logger()

    init {
        log.d("Creating new AuthUtils instance with config: ${config.homeAssistantURL}")
    }

    // Add external auth callback for HA authentication
    val externalAuthCallback =
            object : ExternalAuthCallback {
                override fun onRequestExternalAuth(view: WebView) {
                    log.d("=== AUTHUTILS EXTERNAL AUTH CALLBACK TRIGGERED ===")
                    // JavaScript interface runs on JavaBridge thread, but WebView methods must be
                    // called on main thread
                    Handler(Looper.getMainLooper()).post {
                        try {
                            log.d("External auth callback running on main thread")
                            val currentUrl = view.url ?: ""
                            log.d("External auth callback triggered for URL: $currentUrl")

                            // Check if we have HA configured - if not, this can't be for HA
                            if (config.homeAssistantURL.isEmpty() &&
                                            config.homeAssistantConnectedIP.isEmpty()
                            ) {
                                log.d("No HA configured - skipping HA authentication")
                                setAuthStage(view, PageLoadingStage.LOADED)
                                return@post
                            }

                            // Check if this URL is actually a Home Assistant page
                            // Any URL on the HA domain should authenticate
                            val isActualHAPage = isHomeAssistantMode(currentUrl, config)

                            if (!isActualHAPage) {
                                log.d("URL is not on HA domain - skipping authentication: $currentUrl")
                                setAuthStage(view, PageLoadingStage.LOADED)
                                return@post
                            }

                            log.d("URL is on HA domain - proceeding with authentication: $currentUrl")

                            // If getExternalAuth() is being called, it means HA's page has loaded
                            // and is requesting auth
                            // We should always handle this when HA is configured
                            log.d(
                                    "HA is configured and requesting external authentication - proceeding with auth flow"
                            )

                            log.d("=== AUTH TOKEN STATUS ===")
                            log.d(
                                    "Access token exists: ${config.accessToken.isNotEmpty()} (length: ${config.accessToken.length})"
                            )
                            log.d(
                                    "Refresh token exists: ${config.refreshToken.isNotEmpty()} (length: ${config.refreshToken.length})"
                            )
                            log.d(
                                    "Token expiry: ${config.tokenExpiry}, Current time: ${System.currentTimeMillis()}, Valid: ${config.tokenExpiry > System.currentTimeMillis()}"
                            )
                            log.d("========================")

                            setAuthStage(view, PageLoadingStage.AUTHORISING)
                            if (config.refreshToken.isEmpty()) {
                                log.w("!!! NO REFRESH TOKEN - This will trigger login screen !!!")
                                log.w(
                                        "Access token: ${if (config.accessToken.isEmpty()) "EMPTY" else "EXISTS (${config.accessToken.take(20)}...)"}"
                                )
                                loadUrl(
                                        view,
                                        getAuthUrl(getHAUrl(config, withDashboardPath = false)),
                                        clearCache = true
                                )
                                setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                                return@post
                            } else if (System.currentTimeMillis() > config.tokenExpiry &&
                                            config.refreshToken != ""
                            ) {
                                // Need to get new access token as it has expired
                                log.d(
                                        "Auth token has expired.  Requesting new token using refresh token"
                                )
                                val success: Boolean = reAuthWithRefreshToken()
                                if (success) {
                                    log.d("Authorising with new token")
                                    callAuthJS(view)
                                } else {
                                    log.d(
                                            "Failed to refresh auth token.  Proceeding to login screen"
                                    )
                                    setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                                    loadUrl(
                                            view,
                                            getAuthUrl(getHAUrl(config, withDashboardPath = false)),
                                            clearCache = true
                                    )
                                }
                            } else if (config.accessToken != "") {
                                log.d("Auth token is still valid - authorising")
                                log.d("Calling AuthJS with token")
                                callAuthJS(view)
                            } else {
                                log.e("No access token or refresh token available!")
                                setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                            }
                            log.d("=== EXTERNAL AUTH CALLBACK COMPLETED ===")
                        } catch (e: Exception) {
                            log.e("ERROR in external auth callback: ${e.message}")
                            log.e("Stack trace: ${e.stackTrace.contentToString()}")
                            setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                        }
                    }
                }

                override fun onRequestRevokeExternalAuth(view: WebView) {
                    // Only handle auth revocation for Home Assistant mode - check current URL
                    val currentUrl = view.url ?: ""
                    if (currentUrl.isNotEmpty() && !isHomeAssistantMode(currentUrl, config)) {
                        log.d("Direct URL mode - skipping HA auth revocation for: $currentUrl")
                        return
                    }

                    log.d("External auth revoke callback in progress...")
                    config.accessToken = ""
                    config.refreshToken = ""
                    config.tokenExpiry = 0
                    setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                    loadUrl(view, getAuthUrl(getHAUrl(config)))
                }

                private fun setAuthStage(view: WebView, stage: PageLoadingStage) {
                    Handler(Looper.getMainLooper())
                            .post({
                                val w = view as CustomWebView
                                w.setPageLoadingState(stage)
                            })
                }

                private fun loadUrl(view: WebView, url: String, clearCache: Boolean = false) {
                    log.d("Loading URL: $url")
                    Handler(Looper.getMainLooper())
                            .post({
                                if (clearCache) {
                                    view.clearCache(true)
                                }
                                view.loadUrl(url)
                            })
                }

                private fun callAuthJS(view: WebView) {
                    log.d("Calling AuthJS - Setting token in WebView")
                    Handler(Looper.getMainLooper())
                            .post({
                                val jsCode =
                                        """
                    window.externalAuthSetToken(true, {
                        "access_token": "${config.accessToken}",
                        "token_type": "Bearer",
                        "expires_in": 1800,
                        "external_app": true,
                        "trusted": true
                    });
                """.trimIndent()
                                log.d("Executing enhanced JavaScript auth token injection")
                                view.evaluateJavascript(
                                        jsCode,
                                        { result ->
                                            log.d("JavaScript execution result: $result")
                                            // Delay before setting LOADED to give kiosk plugin time
                                            // to hide HA chrome
                                            // This prevents flash of HA chrome before kiosk mode
                                            // activates
                                            log.d(
                                                    "Waiting for kiosk plugin to activate before showing WebView..."
                                            )
                                            Handler(Looper.getMainLooper())
                                                    .postDelayed(
                                                            {
                                                                log.d(
                                                                        "Setting page state to LOADED - WebView should now be visible"
                                                                )
                                                                setAuthStage(
                                                                        view,
                                                                        PageLoadingStage.LOADED
                                                                )
                                                            },
                                                            500
                                                    ) // 500ms delay for kiosk plugin to activate
                                        }
                                )
                            })
                }

                private fun reAuthWithRefreshToken(): Boolean {
                    log.d("Auth token has expired.  Requesting new token using refresh token")
                    val auth =
                            refreshAccessToken(
                                    getHAUrl(config),
                                    config.refreshToken,
                                    !config.ignoreSSLErrors
                            )
                    if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                        log.d("Received new auth token")
                        config.accessToken = auth.accessToken
                        config.tokenExpiry = auth.expires
                        return true
                    } else {
                        return false
                    }
                }

                private fun tokenExpiryRefreshTask(view: WebView) {
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        try {
                                            if (System.currentTimeMillis() > config.tokenExpiry &&
                                                            config.refreshToken != ""
                                            ) {
                                                if (reAuthWithRefreshToken()) {
                                                    callAuthJS(view)
                                                }
                                            }
                                            tokenExpiryRefreshTask(view)
                                        } catch (e: Exception) {}
                                    },
                                    30000
                            )
                }
            }

    companion object {
        internal val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        var state: String = ""

        fun getHAUrl(config: APPConfig, withDashboardPath: Boolean = true): String {
            var url = ""
            if (config.homeAssistantURL == "") {
                url = "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
            } else {
                url = config.homeAssistantURL.removeSuffix("/")
            }

            // Only add dashboard path if it's a valid HA path (not a full URL)
            if (withDashboardPath &&
                            config.homeAssistantDashboard != "" &&
                            !config.homeAssistantDashboard.contains("://")
            ) {
                return url + "/" + config.homeAssistantDashboard.removePrefix("/")
            }
            return url
        }

        fun getAppropriateUrl(config: APPConfig, withDashboardPath: Boolean = true): String {
            log.d("=== getAppropriateUrl called ===")
            log.d("  directURL: '${config.directURL}'")
            log.d("  homeAssistantURL: '${config.homeAssistantURL}'")
            log.d("  homeAssistantDashboard: '${config.homeAssistantDashboard}'")
            log.d("  withDashboardPath: $withDashboardPath")

            // If direct URL is configured, always use it
            if (config.directURL.isNotEmpty()) {
                log.d("Direct URL is configured - using: ${config.directURL}")
                log.d("=== getAppropriateUrl returning directURL ===")
                return config.directURL
            }

            // Use HA URL (no direct URL configured)
            val haUrl = getHAUrl(config, withDashboardPath)
            log.d("No direct URL configured - using HA URL: $haUrl")
            log.d("=== getAppropriateUrl returning HA URL ===")
            return haUrl
        }

        fun isHomeAssistantMode(config: APPConfig): Boolean {
            // We're in HA mode if:
            // 1. No direct URL configured, OR
            // 2. Direct URL is on the same domain as HA
            if (config.directURL.isEmpty()) {
                return true
            }

            val directUri = config.directURL.toUri()
            val haBaseUrl =
                    if (config.homeAssistantURL.isNotEmpty()) {
                        config.homeAssistantURL
                    } else {
                        "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
                    }
            val haUri = haBaseUrl.toUri()

            // If on same domain as HA, treat as HA mode
            return directUri.host == haUri.host && directUri.port == haUri.port
        }

        fun isHomeAssistantMode(url: String, config: APPConfig): Boolean {
            // Check if the given URL is on the Home Assistant domain
            val urlUri = url.toUri()
            val haBaseUrl =
                    if (config.homeAssistantURL.isNotEmpty()) {
                        config.homeAssistantURL
                    } else {
                        "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
                    }
            val haUri = haBaseUrl.toUri()

            // If on same domain as HA, treat as HA mode
            return urlUri.host == haUri.host && urlUri.port == haUri.port
        }

        fun getURL(
                baseUrl: String,
                addExternalAuth: Boolean = true,
                config: APPConfig? = null
        ): String {
            log.d("Getting URL for $baseUrl")
            val url = baseUrl.toUri().buildUpon()
            if (addExternalAuth) {
                // Add complete external auth context for WebView
                url.appendQueryParameter("external_auth", "1")
                url.appendQueryParameter("auth_provider", "trusted")
                url.appendQueryParameter("external_app", "1")
                // Additional parameters that might help with WebView integration
                url.appendQueryParameter("webview", "1")
                url.appendQueryParameter("embedded", "1")
            }
            return url.build().toString()
        }

        fun getAuthUrl(baseUrl: String): String {
            log.d("Getting Auth URL for $baseUrl")
            val url =
                    baseUrl.toUri()
                            .buildUpon()
                            .path("")
                            .appendPath("auth")
                            .appendPath("authorize")
                            .appendQueryParameter("client_id", getClientId())
                            .appendQueryParameter("redirect_uri", getRedirectUri())
                            .appendQueryParameter("response_type", "code")
                            .appendQueryParameter("state", generateState())
                            .appendQueryParameter("scope", "homeassistant")
                            .build()
            return url.toString()
        }

        fun getTokenUrl(baseUrl: String): String {
            val url = baseUrl.toUri().buildUpon().path("").appendPath("auth").appendPath("token")
            return url.build().toString()
        }

        private fun generateState(): String {
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = Random.Default
            state = ""
            repeat(32) { state += charset[random.nextInt(0, charset.length)] }
            return state
        }

        private fun getClientId(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            return builder.build().toString()
        }

        private fun getRedirectUri(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            builder.appendQueryParameter("auth_callback", "1")
            return builder.build().toString()
        }

        fun validateAuthResponse(url: String): Boolean {
            val uri = url.toUri()
            return uri.authority == CLIENT_URL && uri.getQueryParameter("state") == state
        }

        fun getReturnAuthCode(url: String): String {
            if (validateAuthResponse(url)) {
                return url.toUri().getQueryParameter("code")!!
            } else {
                return ""
            }
        }

        fun authoriseWithAuthCode(
                baseUrl: String,
                authCode: String,
                verifySSL: Boolean = true
        ): AuthToken {
            val url: String = getTokenUrl(baseUrl)
            val map: HashMap<String, String> =
                    hashMapOf(
                            "grant_type" to "authorization_code",
                            "client_id" to getClientId(),
                            "code" to authCode
                    )
            log.d("URL: ${getTokenUrl(baseUrl)} Auth code: $authCode, client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = JSONObject(response)
                val expiresIn =
                        System.currentTimeMillis() + (json.getString("expires_in").toInt() * 1000)

                return AuthToken(
                        json.getString("token_type"),
                        json.getString("access_token"),
                        expiresIn,
                        json.getString("refresh_token")
                )
            } catch (e: Exception) {
                log.e(e.message.toString())
                return AuthToken()
            }
        }

        fun refreshAccessToken(
                host: String,
                refreshToken: String,
                verifySSL: Boolean = true
        ): AuthToken {
            val url: String = getTokenUrl(host)
            val map: HashMap<String, String> =
                    hashMapOf(
                            "grant_type" to "refresh_token",
                            "client_id" to getClientId(),
                            "refresh_token" to refreshToken
                    )
            log.d(
                    "URL: ${getTokenUrl(host)} Refresh token: $refreshToken, client id: ${getClientId()}"
            )
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = JSONObject(response)
                log.d("JSON reposne: $json")
                val expiresIn =
                        System.currentTimeMillis() + (json.getString("expires_in").toInt() * 1000)

                return AuthToken(
                        json.getString("token_type"),
                        json.getString("access_token"),
                        expiresIn,
                )
            } catch (e: Exception) {
                log.e(e.message.toString())
                return AuthToken()
            }
        }

        fun httpPOST(
                url: String,
                parameters: HashMap<String, String>,
                verifySSL: Boolean = true
        ): String {
            val clientBuilder = OkHttpClient.Builder()
            val builder = FormBody.Builder()
            val it = parameters.entries.iterator()

            if (!verifySSL) {
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                clientBuilder.sslSocketFactory(
                        sslContext.socketFactory,
                        trustAllCerts[0] as X509TrustManager
                )
                clientBuilder.hostnameVerifier { hostname, session -> true }
            }
            val client = clientBuilder.build()

            while (it.hasNext()) {
                val pair = it.next() as Map.Entry<*, *>
                builder.add(pair.key.toString(), pair.value.toString())
            }

            val formBody = builder.build()
            val request = Request.Builder().url(url).post(formBody).build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.e("Unexpected code $response")
                        return ""
                    }
                    return response.body.string()
                }
            } catch (e: Exception) {
                log.e("Error authorising with HA: ${e.message.toString()}")
                return ""
            }
        }

        val trustAllCerts =
                arrayOf<TrustManager>(
                        @SuppressLint("CustomX509TrustManager")
                        object : X509TrustManager {
                            @SuppressLint("TrustAllX509TrustManager")
                            override fun checkClientTrusted(
                                    chain: Array<out java.security.cert.X509Certificate?>?,
                                    authType: String?
                            ) {}

                            @SuppressLint("TrustAllX509TrustManager")
                            override fun checkServerTrusted(
                                    chain: Array<out java.security.cert.X509Certificate?>?,
                                    authType: String?
                            ) {}

                            override fun getAcceptedIssuers():
                                    Array<java.security.cert.X509Certificate?> {
                                return arrayOf<java.security.cert.X509Certificate?>()
                            }
                        }
                )
    }
}
