/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
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

package im.vector.riotx.features.crypto.keysrequest

import android.content.Context
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.keyshare.GossipingRequestListener
import im.vector.matrix.android.api.session.crypto.verification.SasVerificationTransaction
import im.vector.matrix.android.api.session.crypto.verification.VerificationService
import im.vector.matrix.android.api.session.crypto.verification.VerificationTransaction
import im.vector.matrix.android.api.session.crypto.verification.VerificationTxState
import im.vector.matrix.android.internal.crypto.IncomingRequestCancellation
import im.vector.matrix.android.internal.crypto.IncomingRoomKeyRequest
import im.vector.matrix.android.internal.crypto.IncomingSecretShareRequest
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import im.vector.matrix.android.internal.crypto.model.CryptoDeviceInfo
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap
import im.vector.matrix.android.internal.crypto.model.rest.DeviceInfo
import im.vector.riotx.R
import im.vector.riotx.features.popup.DefaultVectorAlert
import im.vector.riotx.features.popup.PopupAlertManager
import timber.log.Timber
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage the key share events.
 * Listens for incoming key request and display an alert to the user asking him to ignore / verify
 * calling device / or accept without verifying.
 * If several requests come from same user/device, a single alert is displayed (this alert will accept/reject all request
 * depending on user action)
 */

@Singleton
class KeyRequestHandler @Inject constructor(private val context: Context, private val popupAlertManager: PopupAlertManager)
    : GossipingRequestListener,
        VerificationService.Listener {

    private val alertsToRequests = HashMap<String, ArrayList<IncomingRoomKeyRequest>>()

    var session: Session? = null

    fun start(session: Session) {
        this.session = session
        session.cryptoService().verificationService().addListener(this)
        session.cryptoService().addRoomKeysRequestListener(this)
    }

    fun stop() {
        session?.cryptoService()?.verificationService()?.removeListener(this)
        session?.cryptoService()?.removeRoomKeysRequestListener(this)
        session = null
    }

    override fun onSecretShareRequest(request: IncomingSecretShareRequest): Boolean {
        // By default riotX will not prompt if the SDK has decided that the request should not be fulfilled
        Timber.v("## onSecretShareRequest() : Ignoring $request")
        request.ignore?.run()
        return true
    }

    /**
     * Handle incoming key request.
     *
     * @param request the key request.
     */
    override fun onRoomKeyRequest(request: IncomingRoomKeyRequest) {
        val userId = request.userId
        val deviceId = request.deviceId
        val requestId = request.requestId

        if (userId.isNullOrBlank() || deviceId.isNullOrBlank() || requestId.isNullOrBlank()) {
            Timber.e("## handleKeyRequest() : invalid parameters")
            return
        }

        // Do we already have alerts for this user/device
        val mappingKey = keyForMap(userId, deviceId)
        if (alertsToRequests.containsKey(mappingKey)) {
            // just add the request, there is already an alert for this
            alertsToRequests[mappingKey]?.add(request)
            return
        }

        alertsToRequests[mappingKey] = ArrayList<IncomingRoomKeyRequest>().apply { this.add(request) }

        // Add a notification for every incoming request
        session?.cryptoService()?.downloadKeys(listOf(userId), false, object : MatrixCallback<MXUsersDevicesMap<CryptoDeviceInfo>> {
            override fun onSuccess(data: MXUsersDevicesMap<CryptoDeviceInfo>) {
                val deviceInfo = data.getObject(userId, deviceId)

                if (null == deviceInfo) {
                    Timber.e("## displayKeyShareDialog() : No details found for device $userId:$deviceId")
                    // ignore
                    return
                }

                if (deviceInfo.isUnknown) {
                    session?.cryptoService()?.setDeviceVerification(DeviceTrustLevel(crossSigningVerified = false, locallyVerified = false), userId, deviceId)

                    deviceInfo.trustLevel = DeviceTrustLevel(crossSigningVerified = false, locallyVerified = false)

                    // can we get more info on this device?
                    session?.cryptoService()?.getMyDeviceInfo()?.firstOrNull { it.deviceId == deviceId }?.let {
                        postAlert(context, userId, deviceId, true, deviceInfo, it)
                    } ?: kotlin.run {
                        postAlert(context, userId, deviceId, true, deviceInfo)
                    }
                } else {
                    postAlert(context, userId, deviceId, false, deviceInfo)
                }
            }

            override fun onFailure(failure: Throwable) {
                // ignore
                Timber.e(failure, "## displayKeyShareDialog : downloadKeys")
            }
        })
    }

    private fun postAlert(context: Context,
                          userId: String,
                          deviceId: String,
                          wasNewDevice: Boolean,
                          deviceInfo: CryptoDeviceInfo?,
                          moreInfo: DeviceInfo? = null) {
        val deviceName = if (deviceInfo!!.displayName().isNullOrEmpty()) deviceInfo.deviceId else deviceInfo.displayName()
        val dialogText: String?

        if (moreInfo != null) {
            val lastSeenIp = if (moreInfo.lastSeenIp.isNullOrBlank()) {
                context.getString(R.string.encryption_information_unknown_ip)
            } else {
                moreInfo.lastSeenIp
            }

            val lastSeenTime = moreInfo.lastSeenTs?.let { ts ->
                val dateFormatTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val date = Date(ts)

                val time = dateFormatTime.format(date)
                val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())

                dateFormat.format(date) + ", " + time
            } ?: "-"

            val lastSeenInfo = context.getString(R.string.devices_details_last_seen_format, lastSeenIp, lastSeenTime)
            dialogText = if (wasNewDevice) {
                context.getString(R.string.you_added_a_new_device_with_info, deviceName, lastSeenInfo)
            } else {
                context.getString(R.string.your_unverified_device_requesting_with_info, deviceName, lastSeenInfo)
            }
        } else {
            dialogText = if (wasNewDevice) {
                context.getString(R.string.you_added_a_new_device, deviceName)
            } else {
                context.getString(R.string.your_unverified_device_requesting, deviceName)
            }
        }

        val alert = DefaultVectorAlert(
                alertManagerId(userId, deviceId),
                context.getString(R.string.key_share_request),
                dialogText,
                R.drawable.key_small
        )

        alert.colorRes = R.color.key_share_req_accent_color

        val mappingKey = keyForMap(userId, deviceId)
        alert.dismissedAction = Runnable {
            denyAllRequests(mappingKey)
        }

        alert.addButton(context.getString(R.string.share_without_verifying_short_label), Runnable {
            shareAllSessions(mappingKey)
        })

        alert.addButton(context.getString(R.string.ignore_request_short_label), Runnable {
            denyAllRequests(mappingKey)
        })

        popupAlertManager.postVectorAlert(alert)
    }

    private fun denyAllRequests(mappingKey: String) {
        alertsToRequests[mappingKey]?.forEach {
            it.ignore?.run()
        }
        alertsToRequests.remove(mappingKey)
    }

    private fun shareAllSessions(mappingKey: String) {
        alertsToRequests[mappingKey]?.forEach {
            it.share?.run()
        }
        alertsToRequests.remove(mappingKey)
    }

    /**
     * Manage a cancellation request.
     *
     * @param request the cancellation request.
     */
    override fun onRoomKeyRequestCancellation(request: IncomingRequestCancellation) {
        // see if we can find the request in the queue
        val userId = request.userId
        val deviceId = request.deviceId
        val requestId = request.requestId

        if (userId.isNullOrEmpty() || deviceId.isNullOrEmpty() || requestId.isNullOrEmpty()) {
            Timber.e("## handleKeyRequestCancellation() : invalid parameters")
            return
        }

        val alertMgrUniqueKey = alertManagerId(userId, deviceId)
        alertsToRequests[alertMgrUniqueKey]?.removeAll {
            it.deviceId == request.deviceId
                    && it.userId == request.userId
                    && it.requestId == request.requestId
        }
        if (alertsToRequests[alertMgrUniqueKey]?.isEmpty() == true) {
            popupAlertManager.cancelAlert(alertMgrUniqueKey)
            alertsToRequests.remove(keyForMap(userId, deviceId))
        }
    }

    override fun transactionUpdated(tx: VerificationTransaction) {
        if (tx is SasVerificationTransaction) {
            val state = tx.state
            if (state == VerificationTxState.Verified) {
                // ok it's verified, see if we have key request for that
                shareAllSessions("${tx.otherDeviceId}${tx.otherUserId}")
                popupAlertManager.cancelAlert("ikr_${tx.otherDeviceId}${tx.otherUserId}")
            }
        }
        // should do it with QR tx also
        // TODO -> Probably better to listen to device trust changes?
    }

    override fun markedAsManuallyVerified(userId: String, deviceId: String) {
        // accept related requests
        shareAllSessions(keyForMap(userId, deviceId))
        popupAlertManager.cancelAlert(alertManagerId(userId, deviceId))
    }

    private fun keyForMap(userId: String, deviceId: String) = "$deviceId$userId"

    private fun alertManagerId(userId: String, deviceId: String) = "ikr_$deviceId$userId"
}
