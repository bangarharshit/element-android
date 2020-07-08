/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.media

import android.os.Parcelable
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.isVisible
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.file.FileService
import im.vector.matrix.android.internal.crypto.attachments.ElementToDecrypt
import im.vector.riotx.R
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.error.ErrorFormatter
import kotlinx.android.parcel.Parcelize
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class VideoContentRenderer @Inject constructor(private val activeSessionHolder: ActiveSessionHolder,
                                               private val errorFormatter: ErrorFormatter) {

    @Parcelize
    data class Data(
            val eventId: String,
            val filename: String,
            val mimeType: String?,
            val url: String?,
            val elementToDecrypt: ElementToDecrypt?,
            val thumbnailMediaData: ImageContentRenderer.Data
    ) : Parcelable

    fun render(data: Data,
               thumbnailView: ImageView,
               loadingView: ProgressBar,
               videoView: VideoView,
               errorView: TextView) {
        val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()

        if (data.elementToDecrypt != null) {
            Timber.v("Decrypt video")
            videoView.isVisible = false

            if (data.url == null) {
                loadingView.isVisible = false
                errorView.isVisible = true
                errorView.setText(R.string.unknown_error)
            } else {
                thumbnailView.isVisible = true
                loadingView.isVisible = true

                activeSessionHolder.getActiveSession().fileService()
                        .downloadFile(
                                downloadMode = FileService.DownloadMode.FOR_INTERNAL_USE,
                                id = data.eventId,
                                fileName = data.filename,
                                mimeType = data.mimeType,
                                url = data.url,
                                elementToDecrypt = data.elementToDecrypt,
                                callback = object : MatrixCallback<File> {
                                    override fun onSuccess(data: File) {
                                        thumbnailView.isVisible = false
                                        loadingView.isVisible = false
                                        videoView.isVisible = true

                                        videoView.setVideoPath(data.path)
                                        videoView.start()
                                    }

                                    override fun onFailure(failure: Throwable) {
                                        loadingView.isVisible = false
                                        errorView.isVisible = true
                                        errorView.text = errorFormatter.toHumanReadable(failure)
                                    }
                                })
            }
        } else {
            val resolvedUrl = contentUrlResolver.resolveFullSize(data.url)

            if (resolvedUrl == null) {
                thumbnailView.isVisible = false
                loadingView.isVisible = false
                errorView.isVisible = true
                errorView.setText(R.string.unknown_error)
            } else {
                // Temporary code, some remote videos are not played by videoview setVideoUri
                // So for now we download them then play
                thumbnailView.isVisible = true
                loadingView.isVisible = true

                activeSessionHolder.getActiveSession().fileService()
                        .downloadFile(
                                downloadMode = FileService.DownloadMode.FOR_INTERNAL_USE,
                                id = data.eventId,
                                fileName = data.filename,
                                mimeType = data.mimeType,
                                url = data.url,
                                elementToDecrypt = null,
                                callback = object : MatrixCallback<File> {
                                    override fun onSuccess(data: File) {
                                        thumbnailView.isVisible = false
                                        loadingView.isVisible = false
                                        videoView.isVisible = true

                                        videoView.setVideoPath(data.path)
                                        videoView.start()
                                    }

                                    override fun onFailure(failure: Throwable) {
                                        loadingView.isVisible = false
                                        errorView.isVisible = true
                                        errorView.text = errorFormatter.toHumanReadable(failure)
                                    }
                                })
            }
        }
    }
}
