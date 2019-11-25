/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.auth.login

import android.util.Patterns
import dagger.Lazy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.auth.data.HomeServerConnectionConfig
import im.vector.matrix.android.api.auth.login.LoginWizard
import im.vector.matrix.android.api.auth.registration.RegisterThreePid
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.NoOpCancellable
import im.vector.matrix.android.internal.auth.AuthAPI
import im.vector.matrix.android.internal.auth.SessionCreator
import im.vector.matrix.android.internal.auth.data.PasswordLoginParams
import im.vector.matrix.android.internal.auth.data.ThreePidMedium
import im.vector.matrix.android.internal.auth.registration.AddThreePidRegistrationParams
import im.vector.matrix.android.internal.auth.registration.AddThreePidRegistrationResponse
import im.vector.matrix.android.internal.auth.registration.RegisterAddThreePidTask
import im.vector.matrix.android.internal.network.RetrofitFactory
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.launchToCallback
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.*

// Container to store the data when a reset password is in the email validation step
internal data class ResetPasswordData(
        val newPassword: String,
        val addThreePidRegistrationResponse: AddThreePidRegistrationResponse
)

internal class DefaultLoginWizard(
        private val homeServerConnectionConfig: HomeServerConnectionConfig,
        okHttpClient: Lazy<OkHttpClient>,
        retrofitFactory: RetrofitFactory,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val sessionCreator: SessionCreator
) : LoginWizard {

    private var clientSecret = UUID.randomUUID().toString()
    private var sendAttempt = 0

    private var resetPasswordData: ResetPasswordData? = null

    private val authAPI = retrofitFactory.create(okHttpClient, homeServerConnectionConfig.homeServerUri.toString())
            .create(AuthAPI::class.java)

    override fun login(login: String,
                       password: String,
                       deviceName: String,
                       callback: MatrixCallback<Session>): Cancelable {
        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            loginInternal(login, password, deviceName)
        }
    }

    private suspend fun loginInternal(login: String,
                                      password: String,
                                      deviceName: String) = withContext(coroutineDispatchers.computation) {
        val loginParams = if (Patterns.EMAIL_ADDRESS.matcher(login).matches()) {
            PasswordLoginParams.thirdPartyIdentifier(ThreePidMedium.EMAIL, login, password, deviceName)
        } else {
            PasswordLoginParams.userIdentifier(login, password, deviceName)
        }
        val credentials = executeRequest<Credentials> {
            apiCall = authAPI.login(loginParams)
        }

        sessionCreator.createSession(credentials, homeServerConnectionConfig)
    }

    override fun resetPassword(email: String, newPassword: String, callback: MatrixCallback<Unit>): Cancelable {
        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            resetPasswordInternal(email, newPassword)
        }
    }

    private suspend fun resetPasswordInternal(email: String, newPassword: String) {
        val param = RegisterAddThreePidTask.Params(
                RegisterThreePid.Email(email),
                clientSecret,
                sendAttempt++
        )
        val result = executeRequest<AddThreePidRegistrationResponse> {
            apiCall = authAPI.resetPassword(AddThreePidRegistrationParams.from(param))
        }
        resetPasswordData = ResetPasswordData(newPassword, result)
    }

    override fun resetPasswordMailConfirmed(callback: MatrixCallback<Unit>): Cancelable {
        val safeResetPasswordData = resetPasswordData ?: run {
            callback.onFailure(IllegalStateException("developer error, no reset password in progress"))
            return NoOpCancellable
        }
        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            resetPasswordMailConfirmedInternal(safeResetPasswordData)
        }
    }

    private suspend fun resetPasswordMailConfirmedInternal(resetPasswordData: ResetPasswordData) {
        val param = ResetPasswordMailConfirmed.create(
                clientSecret,
                resetPasswordData.addThreePidRegistrationResponse.sid,
                resetPasswordData.newPassword
        )

        executeRequest<Unit> {
            apiCall = authAPI.resetPasswordMailConfirmed(param)
        }

        // Set to null?
        // resetPasswordData = null
    }
}
