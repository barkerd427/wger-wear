package de.wger.spike.watch

import android.content.Context
import android.net.Uri
import androidx.wear.phone.interactions.authentication.CodeChallenge
import androidx.wear.phone.interactions.authentication.CodeVerifier
import androidx.wear.phone.interactions.authentication.OAuthRequest
import androidx.wear.phone.interactions.authentication.OAuthResponse
import androidx.wear.phone.interactions.authentication.RemoteAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth 2 auth-code + PKCE against wger 2.7's allauth.idp.oidc provider, with the browser opened
 * on the paired phone by Wear OS. The OIDC client is created by the server admin (no dynamic
 * registration) as a public client with redirect URI
 * https://wear.googleapis.com/3p_auth/<applicationId> and scopes "openid api:read api:write".
 */
class RemoteSignIn(private val context: Context, private val serverUrl: String, private val clientId: String) {

    suspend fun signIn(http: HttpClient): String {
        val verifier = CodeVerifier()
        val request = OAuthRequest.Builder(context)
            .setAuthProviderUrl(
                Uri.parse("$serverUrl/identity/o/authorize").buildUpon()
                    .appendQueryParameter("scope", "openid api:read api:write")
                    .build(),
            )
            .setClientId(clientId)
            .setCodeChallenge(CodeChallenge(verifier))
            .build()

        val responseUrl = suspendCancellableCoroutine { cont ->
            RemoteAuthClient.create(context).sendAuthorizationRequest(
                request,
                context.mainExecutor,
                object : RemoteAuthClient.Callback() {
                    override fun onAuthorizationResponse(request: OAuthRequest, response: OAuthResponse) {
                        cont.resume(response.responseUrl!!)
                    }
                    override fun onAuthorizationError(request: OAuthRequest, errorCode: Int) {
                        cont.resumeWithException(IllegalStateException("remote auth error $errorCode"))
                    }
                },
            )
        }
        val code = responseUrl.getQueryParameter("code") ?: error("no code in $responseUrl")

        // Token JSON: access_token (opaque, ~1 h) + refresh_token (~120 d). Store in EncryptedFile/Keystore.
        return http.submitForm(
            "$serverUrl/identity/o/api/token",
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", request.redirectUrl)
                append("client_id", clientId)
                append("code_verifier", verifier.value)
            },
        ).bodyAsText()
    }
}
