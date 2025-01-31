package io.homeassistant.companion.android.data.authentication

import com.fasterxml.jackson.databind.ObjectMapper
import io.homeassistant.companion.android.data.LocalStorage
import io.homeassistant.companion.android.domain.authentication.AuthenticationRepository
import io.homeassistant.companion.android.domain.authentication.SessionState
import java.net.URL
import javax.inject.Inject
import javax.inject.Named
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.threeten.bp.Instant

class AuthenticationRepositoryImpl @Inject constructor(
    private val authenticationService: AuthenticationService,
    @Named("session") private val localStorage: LocalStorage
) : AuthenticationRepository {

    companion object {
        private const val PREF_URL = "url"
        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_EXPIRED_DATE = "expires_date"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_TOKEN_TYPE = "token_type"
    }

    override suspend fun saveUrl(url: URL) {
        localStorage.putString(PREF_URL, url.toString())
    }

    override suspend fun registerAuthorizationCode(authorizationCode: String) {
        authenticationService.getToken(
            AuthenticationService.GRANT_TYPE_CODE,
            authorizationCode,
            AuthenticationService.CLIENT_ID
        ).let {
            saveSession(
                Session(
                    it.accessToken,
                    Instant.now().epochSecond + it.expiresIn,
                    it.refreshToken!!,
                    it.tokenType
                )
            )
        }
    }

    override suspend fun retrieveExternalAuthentication(): String {
        return convertSession(ensureValidSession())
    }

    override suspend fun revokeSession() {
        val session = retrieveSession() ?: throw AuthorizationException()
        authenticationService.revokeToken(session.refreshToken, AuthenticationService.REVOKE_ACTION)
        saveSession(null)
    }

    override suspend fun getSessionState(): SessionState {
        return if (retrieveSession() != null) {
            SessionState.CONNECTED
        } else {
            SessionState.ANONYMOUS
        }
    }

    override suspend fun getUrl(): URL? {
        return localStorage.getString(PREF_URL)?.toHttpUrlOrNull()?.toUrl()
    }

    override suspend fun buildAuthenticationUrl(callbackUrl: String): URL {
        val url = localStorage.getString(PREF_URL) ?: throw AuthorizationException()

        return url.toHttpUrl()
            .newBuilder()
            .addPathSegments("auth/authorize")
            .addEncodedQueryParameter("response_type", "code")
            .addEncodedQueryParameter("client_id", AuthenticationService.CLIENT_ID)
            .addEncodedQueryParameter("redirect_uri", callbackUrl)
            .build()
            .toUrl()
    }

    override suspend fun buildBearerToken(): String {
        return "Bearer " + ensureValidSession().accessToken
    }

    private fun convertSession(session: Session): String {
        return ObjectMapper().writeValueAsString(
            mapOf(
                "access_token" to session.accessToken,
                "expires_in" to session.expiresIn()
            )
        )
    }

    private suspend fun retrieveSession(): Session? {
        val accessToken = localStorage.getString(PREF_ACCESS_TOKEN)
        val expiredDate = localStorage.getLong(PREF_EXPIRED_DATE)
        val refreshToken = localStorage.getString(PREF_REFRESH_TOKEN)
        val tokenType = localStorage.getString(PREF_TOKEN_TYPE)

        return if (accessToken != null && expiredDate != null && refreshToken != null && tokenType != null) {
            Session(accessToken, expiredDate, refreshToken, tokenType)
        } else {
            null
        }
    }

    private suspend fun ensureValidSession(): Session {
        val session = retrieveSession() ?: throw AuthorizationException()

        if (session.isExpired()) {
            return authenticationService.refreshToken(
                AuthenticationService.GRANT_TYPE_REFRESH,
                session.refreshToken,
                AuthenticationService.CLIENT_ID
            ).let {
                val refreshSession = Session(
                    it.accessToken,
                    Instant.now().epochSecond + it.expiresIn,
                    session.refreshToken,
                    it.tokenType
                )
                saveSession(refreshSession)
                refreshSession
            }
        }

        return session
    }

    private suspend fun saveSession(session: Session?) {
        localStorage.putString(PREF_ACCESS_TOKEN, session?.accessToken)
        localStorage.putLong(PREF_EXPIRED_DATE, session?.expiresTimestamp)
        localStorage.putString(PREF_REFRESH_TOKEN, session?.refreshToken)
        localStorage.putString(PREF_TOKEN_TYPE, session?.tokenType)
    }
}
