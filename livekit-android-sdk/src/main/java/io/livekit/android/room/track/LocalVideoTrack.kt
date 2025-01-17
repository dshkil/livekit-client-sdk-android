/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.memory.CloseableManager
import io.livekit.android.memory.SurfaceTextureHelperCloser
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.video.*
import io.livekit.android.room.track.video.CameraCapturerUtils.createCameraEnumerator
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraCapturerUtils.getCameraPosition
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import java.util.*

/**
 * A representation of a local video track (generally input coming from camera or screen).
 *
 * [startCapture] should be called before use.
 */
open class LocalVideoTrack
@AssistedInject
constructor(
    @Assisted private var capturer: VideoCapturer,
    @Assisted private var source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: org.webrtc.VideoTrack,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val defaultsManager: DefaultsManager,
    private val trackFactory: Factory,
) : VideoTrack(name, rtcTrack) {

    override var rtcTrack: org.webrtc.VideoTrack = rtcTrack
        internal set

    @FlowObservable
    @get:FlowObservable
    var options: LocalVideoTrackOptions by flowDelegate(options)

    val dimensions: Dimensions
        get() {
            (capturer as? VideoCapturerWithSize)?.let { capturerWithSize ->
                val size = capturerWithSize.findCaptureFormat(
                    options.captureParams.width,
                    options.captureParams.height
                )
                return Dimensions(size.width, size.height)
            }
            return Dimensions(options.captureParams.width, options.captureParams.height)
        }

    internal var transceiver: RtpTransceiver? = null
    internal val sender: RtpSender?
        get() = transceiver?.sender

    private val closeableManager = CloseableManager()

    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps
        )
    }

    open fun stopCapture() {
        capturer.stopCapture()
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    override fun dispose() {
        super.dispose()
        capturer.dispose()
        closeableManager.close()
    }

    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

    /**
     * Switch to a different camera. Only works if this track is backed by a camera capturer.
     *
     * If neither deviceId or position is provided, or the specified camera cannot be found,
     * this will switch to the next camera, if one is available.
     */
    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {
        val cameraCapturer = capturer as? CameraVideoCapturer ?: run {
            LKLog.w { "Attempting to switch camera on a non-camera video track!" }
            return
        }

        var targetDeviceId: String? = null
        val enumerator = createCameraEnumerator(context)
        if (deviceId != null || position != null) {
            targetDeviceId = enumerator.findCamera(deviceId, position, fallback = false)
        }

        if (targetDeviceId == null) {
            val deviceNames = enumerator.deviceNames
            if (deviceNames.size < 2) {
                LKLog.w { "No available cameras to switch to!" }
                return
            }
            val currentIndex = deviceNames.indexOf(options.deviceId)
            targetDeviceId = deviceNames[(currentIndex + 1) % deviceNames.size]
        }

        fun updateCameraOptions() {
            val newOptions = options.copy(
                deviceId = targetDeviceId,
                position = enumerator.getCameraPosition(targetDeviceId)
            )
            options = newOptions
        }

        val cameraSwitchHandler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                // For cameras we control, wait until the first frame to ensure everything is okay.
                if (cameraCapturer is CameraCapturerWithSize) {
                    cameraCapturer.cameraEventsDispatchHandler
                        .registerHandler(object : CameraEventsHandler {
                            override fun onFirstFrameAvailable() {
                                updateCameraOptions()
                                cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                            }

                            override fun onCameraError(p0: String?) {
                                cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                            }

                            override fun onCameraDisconnected() {
                                cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                            }

                            override fun onCameraFreezed(p0: String?) {
                            }

                            override fun onCameraOpening(p0: String?) {
                            }

                            override fun onCameraClosed() {
                                cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                            }
                        })
                } else {
                    updateCameraOptions()
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                LKLog.w { "switching camera failed: $errorDescription" }
            }
        }
        if (targetDeviceId == null) {
            LKLog.w { "No target camera found!" }
            return
        } else {
            cameraCapturer.switchCamera(cameraSwitchHandler, targetDeviceId)
        }
    }

    /**
     * Restart a track with new options.
     */
    fun restartTrack(options: LocalVideoTrackOptions = defaultsManager.videoTrackCaptureDefaults.copy()) {
        val oldCapturer = capturer
        val oldSource = source
        val oldRtcTrack = rtcTrack

        oldCapturer.stopCapture()
        oldCapturer.dispose()
        oldSource.dispose()

        // sender owns rtcTrack, so it'll take care of disposing it.
        oldRtcTrack.setEnabled(false)

        // Close resources associated to the old track. new track resources is registered in createTrack.
        val oldCloseable = closeableManager.unregisterResource(oldRtcTrack)
        oldCloseable?.close()

        val newTrack = createTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            trackFactory
        )

        // migrate video sinks to the new track
        for (sink in sinks) {
            oldRtcTrack.removeSink(sink)
            newTrack.addRenderer(sink)
        }

        capturer = newTrack.capturer
        source = newTrack.source
        rtcTrack = newTrack.rtcTrack
        this.options = options
        startCapture()
        sender?.setTrack(newTrack.rtcTrack, true)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: org.webrtc.VideoTrack,
        ): LocalVideoTrack
    }

    companion object {

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            capturer: VideoCapturer,
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(false)
            source.setVideoProcessor(videoProcessor)
            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)
            capturer.initialize(
                surfaceTextureHelper,
                context,
                source.capturerObserver
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = rtcTrack
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper)
            )
            return track
        }

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a camera video track.")
            }

            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            source.setVideoProcessor(videoProcessor)
            val (capturer, newOptions) = CameraCapturerUtils.createCameraCapturer(context, options) ?: TODO()
            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)
            capturer.initialize(
                surfaceTextureHelper,
                context,
                source.capturerObserver
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = newOptions,
                name = name,
                rtcTrack = rtcTrack
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper)
            )

            return track
        }
    }
}
