/*
 * Copyright (c) 2015 Mario Guggenberger <mg@itec.aau.at>
 *
 * This file is part of ITEC MediaPlayer.
 *
 * ITEC MediaPlayer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ITEC MediaPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ITEC MediaPlayer.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.aau.itec.android.mediaplayer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Mario on 13.09.2015.
 */
class Decoder {

    static class VideoFrameInfo {
        int buffer;
        long presentationTimeUs;
        boolean endOfStream;
        boolean representationChanged;
        int width;
        int height;

        public VideoFrameInfo() {
            clear();
        }

        public void clear() {
            buffer = -1;
            presentationTimeUs = -1;
            endOfStream = false;
            representationChanged = false;
            width = -1;
            height = -1;
        }
    }

    private static final String TAG = Decoder.class.getSimpleName();

    private static final long TIMEOUT_US = 5000;
    public static final int INDEX_NONE = -1;

    private MediaExtractor mVideoExtractor;
    private int mVideoTrackIndex;
    private Surface mVideoSurface;
    private MediaFormat mVideoFormat;

    private MediaExtractor mAudioExtractor;
    private int mAudioTrackIndex;
    private AudioPlayback mAudioPlayback;
    private MediaFormat mAudioFormat;

    private MediaCodec mVideoCodec;
    private MediaCodec mAudioCodec;

    private ByteBuffer[] mVideoCodecInputBuffers;
    private ByteBuffer[] mVideoCodecOutputBuffers;
    private ByteBuffer[] mAudioCodecInputBuffers;
    private ByteBuffer[] mAudioCodecOutputBuffers;
    private MediaCodec.BufferInfo mVideoInfo;
    private MediaCodec.BufferInfo mAudioInfo;
    private boolean mVideoInputEos;
    private boolean mVideoOutputEos;
    private boolean mAudioInputEos;
    private boolean mAudioOutputEos;
    private boolean mBuffering;
    private List<VideoFrameInfo> mEmptyVideoFrameInfos;

    /* Flag notifying that the representation has changed in the extractor and needs to be passed
     * to the decoder. This transition state is only needed in playback, not when seeking. */
    private boolean mRepresentationChanging;
    /* Flag notifying that the decoder has changed to a new representation, post-actions need to
     * be carried out. */
    private boolean mRepresentationChanged;

    public Decoder(MediaExtractor videoExtractor, int videoTrackIndex, Surface videoSurface,
                   MediaExtractor audioExtractor, int audioTrackIndex, AudioPlayback audioPlayback)
            throws IllegalStateException, IOException
    {
        if(videoExtractor == null || videoTrackIndex == INDEX_NONE) {
            if(audioTrackIndex != INDEX_NONE) {
                throw new IllegalArgumentException("audio-only not supported yet");
            }
            throw new IllegalArgumentException("no video track specified");
        }

        mVideoExtractor = videoExtractor;
        mVideoTrackIndex = videoTrackIndex;
        mVideoSurface = videoSurface;
        mVideoFormat = videoExtractor.getTrackFormat(mVideoTrackIndex);

        mAudioExtractor = audioExtractor;
        mAudioTrackIndex = audioTrackIndex;
        mAudioPlayback = audioPlayback;

        if(audioTrackIndex != INDEX_NONE) {
            if(mAudioExtractor == null) {
                mAudioExtractor = mVideoExtractor;
            }
            if(mAudioPlayback == null) {
                throw new IllegalArgumentException("audio playback missing");
            }
            mAudioFormat = mAudioExtractor.getTrackFormat(mAudioTrackIndex);
        }

        mVideoCodec = MediaCodec.createDecoderByType(mVideoFormat.getString(MediaFormat.KEY_MIME));

        if(mAudioFormat != null) {
            mAudioCodec = MediaCodec.createDecoderByType(mAudioFormat.getString(MediaFormat.KEY_MIME));
        }

        reinitCodecs();

        // Create VideoFrameInfo objects for later reuse
        mEmptyVideoFrameInfos = new ArrayList<>();
        for(int i = 0; i < mVideoCodecOutputBuffers.length; i++) {
            mEmptyVideoFrameInfos.add(new VideoFrameInfo());
        }
    }

    private boolean queueVideoSampleToCodec() {
        /* NOTE the track index checks only for debugging
         * when enabled, they prevent the EOS detection and handling below */
//        int trackIndex = mVideoExtractor.getSampleTrackIndex();
//        if(trackIndex == -1) {
//            throw new IllegalStateException("EOS");
//        }
//        if(trackIndex != mVideoTrackIndex) {
//            throw new IllegalStateException("wrong track index: " + trackIndex);
//        }
        boolean sampleQueued = false;
        int inputBufIndex = mVideoCodec.dequeueInputBuffer(TIMEOUT_US);
        if (inputBufIndex >= 0) {
            ByteBuffer inputBuffer = mVideoCodecInputBuffers[inputBufIndex];
            int sampleSize = mVideoExtractor.readSampleData(inputBuffer, 0);
            long presentationTimeUs = 0;

            if(sampleSize == 0) {
                if(mVideoExtractor.getCachedDuration() == 0) {
                    mBuffering = true;
//                    mEventHandler.sendMessage(mEventHandler.obtainMessage(MEDIA_INFO,
//                            MEDIA_INFO_BUFFERING_START, 0));
                    // TODO notify of buffering
                }
                if(mVideoExtractor.hasTrackFormatChanged()) {
                    /* The mRepresentationChanging flag and BUFFER_FLAG_END_OF_STREAM flag together
                     * notify the decoding loop that the representation changes and the codec
                     * needs to be reconfigured.
                     */
                    mRepresentationChanging = true;
                    mVideoCodec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } else {
                if (sampleSize < 0) {
                    Log.d(TAG, "EOS video input");
                    mVideoInputEos = true;
                    sampleSize = 0;
                } else {
                    presentationTimeUs = mVideoExtractor.getSampleTime();
                    sampleQueued = true;
                }

                mVideoCodec.queueInputBuffer(
                        inputBufIndex,
                        0,
                        sampleSize,
                        presentationTimeUs,
                        mVideoInputEos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                if (!mVideoInputEos) {
                    mVideoExtractor.advance();
                }
            }
        }
        return sampleQueued;
    }

    private boolean queueAudioSampleToCodec(MediaExtractor extractor) {
        if(mAudioCodec == null) {
            throw new IllegalStateException("no audio track configured");
        }
        /* NOTE the track index checks only for debugging
         * when enabled, they prevent the EOS detection and handling below */
//        int trackIndex = extractor.getSampleTrackIndex();
//        if(trackIndex == -1) {
//            throw new IllegalStateException("EOS");
//        }
//        if(trackIndex != mAudioTrackIndex) {
//            throw new IllegalStateException("wrong track index: " + trackIndex);
//        }
        boolean sampleQueued = false;
        int inputBufIndex = mAudioCodec.dequeueInputBuffer(TIMEOUT_US);
        if (inputBufIndex >= 0) {
            ByteBuffer inputBuffer = mAudioCodecInputBuffers[inputBufIndex];
            int sampleSize = extractor.readSampleData(inputBuffer, 0);
            long presentationTimeUs = 0;
            if (sampleSize < 0) {
                Log.d(TAG, "EOS audio input");
                mAudioInputEos = true;
                sampleSize = 0;
            } else if(sampleSize == 0) {
                if(extractor.getCachedDuration() == 0) {
                    mBuffering = true;
//                    mEventHandler.sendMessage(mEventHandler.obtainMessage(MEDIA_INFO,
//                            MEDIA_INFO_BUFFERING_START, 0));
                    // TODO notify of buffering
                }
            } else {
                presentationTimeUs = extractor.getSampleTime();
                sampleQueued = true;
            }
            mAudioCodec.queueInputBuffer(
                    inputBufIndex,
                    0,
                    sampleSize,
                    presentationTimeUs,
                    mAudioInputEos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

            if (!mAudioInputEos) {
                extractor.advance();
            }
        }
        return sampleQueued;
    }

    private void decodeAudioSample() {
        int output = mAudioCodec.dequeueOutputBuffer(mAudioInfo, TIMEOUT_US);
        if (output >= 0) {
            // http://bigflake.com/mediacodec/#q11
            ByteBuffer outputData = mAudioCodecOutputBuffers[output];
            if (mAudioInfo.size != 0) {
                outputData.position(mAudioInfo.offset);
                outputData.limit(mAudioInfo.offset + mAudioInfo.size);
                //Log.d(TAG, "raw audio data bytes: " + mVideoInfo.size);
            }
            mAudioPlayback.write(outputData, mAudioInfo.presentationTimeUs);
            mAudioCodec.releaseOutputBuffer(output, false);

            if ((mAudioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                mAudioOutputEos = true;
                Log.d(TAG, "EOS audio output");
            }
        } else if (output == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            Log.d(TAG, "audio output buffers have changed.");
            mAudioCodecOutputBuffers = mAudioCodec.getOutputBuffers();
        } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = mAudioCodec.getOutputFormat();
            Log.d(TAG, "audio output format has changed to " + format);
            mAudioPlayback.init(format);
        } else if (output == MediaCodec.INFO_TRY_AGAIN_LATER) {
            Log.d(TAG, "audio dequeueOutputBuffer timed out");
        }
    }

    private boolean queueMediaSampleToCodec(boolean videoOnly) {
        boolean result = false;
        if(mAudioCodec != null) {
            if (videoOnly) {
                /* VideoOnly mode skips audio samples, e.g. while doing a seek operation. */
                int trackIndex;
                while ((trackIndex = mVideoExtractor.getSampleTrackIndex()) != -1 && trackIndex != mVideoTrackIndex && !mVideoInputEos) {
                    mVideoExtractor.advance();
                }
            } else {
                while (mVideoExtractor == mAudioExtractor && mAudioExtractor.getSampleTrackIndex() == mAudioTrackIndex) {
                    result = queueAudioSampleToCodec(mAudioExtractor);
                    decodeAudioSample();
                }
            }
        }
        if(!mVideoInputEos) {
            result = queueVideoSampleToCodec();
        }
        return result;
    }

    /**
     * Restarts the codecs with a new format, e.g. after a representation change.
     */
    private void reinitCodecs() {
        long t1 = SystemClock.elapsedRealtime();

        // Get new video format and restart video codec with this format
        mVideoFormat = mVideoExtractor.getTrackFormat(mVideoTrackIndex);

        mVideoCodec.stop();
        mVideoCodec.configure(mVideoFormat, mVideoSurface, null, 0);
        mVideoCodec.start();
        mVideoCodecInputBuffers = mVideoCodec.getInputBuffers();
        mVideoCodecOutputBuffers = mVideoCodec.getOutputBuffers();
        mVideoInfo = new MediaCodec.BufferInfo();
        mVideoInputEos = false;
        mVideoOutputEos = false;

        if(mAudioFormat != null) {
            mAudioFormat = mAudioExtractor.getTrackFormat(mAudioTrackIndex);

            mAudioCodec.stop();
            mAudioCodec.configure(mAudioFormat, null, null, 0);
            mAudioCodec.start();
            mAudioCodecInputBuffers = mAudioCodec.getInputBuffers();
            mAudioCodecOutputBuffers = mAudioCodec.getOutputBuffers();
            mAudioInfo = new MediaCodec.BufferInfo();
            mAudioInputEos = false;
            mAudioOutputEos = false;

            mAudioPlayback.init(mAudioFormat);
        }

        Log.d(TAG, "reinitCodecs " + (SystemClock.elapsedRealtime() - t1) + "ms");
    }

    private long fastSeek(long targetTime) throws IOException {
        mVideoCodec.flush();
        if(mAudioFormat != null) mAudioCodec.flush();
        mVideoExtractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        if(mVideoExtractor.getSampleTime() == targetTime) {
            Log.d(TAG, "skip fastseek, already there");
            return targetTime;
        }

        // 1. Queue first sample which should be the sync/I frame
        queueMediaSampleToCodec(true);

        // 2. Then, fast forward to target frame
        /* 2.1 Search for the best candidate frame, which is the one whose
         *     right/positive/future distance is minimized
         */
        mVideoExtractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        /* Specifies how many frames we continue to check after the first candidate,
         * to account for DTS picture reordering (this value is arbitrarily chosen) */
        int maxFrameLookahead = 20;

        long candidatePTS = 0;
        long candidateDistance = Long.MAX_VALUE;
        int lookaheadCount = 0;

        while (mVideoExtractor.advance() && lookaheadCount < maxFrameLookahead) {
            long distance = targetTime - mVideoExtractor.getSampleTime();
            if (distance >= 0 && distance < candidateDistance) {
                candidateDistance = distance;
                candidatePTS = mVideoExtractor.getSampleTime();
                //Log.d(TAG, "candidate " + candidatePTS + " d=" + candidateDistance);
            }
            if (distance < 0) {
                lookaheadCount++;
            }
        }
        targetTime = candidatePTS; // set best candidate frame as exact seek target

        // 2.2 Fast forward to chosen candidate frame
        mVideoExtractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (mVideoExtractor.getSampleTime() != targetTime) {
            mVideoExtractor.advance();
        }
        Log.d(TAG, "exact fastseek match:       " + mVideoExtractor.getSampleTime());

        return targetTime;
    }

    public int getVideoWidth() {
        return mVideoFormat != null ? (int)(mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                * mVideoFormat.getFloat(MediaExtractor.MEDIA_FORMAT_EXTENSION_KEY_DAR)) : 0;
    }

    public int getVideoHeight() {
        return mVideoFormat != null ? mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
    }

    /**
     * Runs the decoder until a new frame is available. The returned VideoFrameInfo object keeps
     * metadata of the decoded frame. To render the frame to the screen and/or dismiss its data,
     * call {@link #releaseFrame(VideoFrameInfo, boolean)}.
     */
    public VideoFrameInfo decodeFrame(boolean videoOnly) {
        while (!mVideoOutputEos) {
            if (!mVideoInputEos && !mRepresentationChanging) {
                queueMediaSampleToCodec(videoOnly);
            }

            int res = mVideoCodec.dequeueOutputBuffer(mVideoInfo, TIMEOUT_US);
            mVideoOutputEos = res >= 0 && (mVideoInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

            if(mVideoOutputEos && mRepresentationChanging) {
                /* Here, the video output is not really at its end, it's just the end of the
                 * current representation segment, and the codec needs to be reconfigured to
                 * the following representation format to carry on.
                 */

                reinitCodecs();

                mVideoOutputEos = false;
                mRepresentationChanging = false;
                mRepresentationChanged = true;
            }
            else if (res >= 0) {
                // Frame decoded. Fill video frame info object and return to caller...
                //Log.d(TAG, "pts=" + info.presentationTimeUs);

                VideoFrameInfo vfi = mEmptyVideoFrameInfos.get(0);
                vfi.buffer = res;
                vfi.presentationTimeUs = mVideoInfo.presentationTimeUs;
                vfi.endOfStream = mVideoOutputEos;

                if(mRepresentationChanged) {
                    mRepresentationChanged = false;
                    vfi.representationChanged = true;
                    vfi.width = getVideoWidth();
                    vfi.height = getVideoHeight();
                }

                Log.d(TAG, "PTS " + vfi.presentationTimeUs);

                // Buffer some audio from separate source...
                if (mAudioFormat != null & mAudioExtractor != mVideoExtractor && !videoOnly) {
                    // TODO rewrite; this is just a quick and dirty hack
                    while (mAudioPlayback.getBufferTimeUs() < 100000) {
                        if (queueAudioSampleToCodec(mAudioExtractor)) {
                            decodeAudioSample();
                        } else {
                            break;
                        }
                    }
                }

                if(vfi.endOfStream) Log.d(TAG, "EOS");

                return vfi;
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                mVideoCodecOutputBuffers = mVideoCodec.getOutputBuffers();
                Log.d(TAG, "output buffers have changed.");
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // NOTE: this is the format of the raw video output, not the video format as specified by the container
                MediaFormat oformat = mVideoCodec.getOutputFormat();
                Log.d(TAG, "output format has changed to " + oformat);
            } else if (res == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG, "dequeueOutputBuffer timed out");
            }
        }

        Log.d(TAG, "EOS NULL");
        return null; // EOS already reached, no video frame left to return
    }

    /**
     * Releases all data belonging to a frame and optionally renders it to the configured surface.
     */
    public void releaseFrame(VideoFrameInfo videoFrameInfo, boolean render) {
        mVideoCodec.releaseOutputBuffer(videoFrameInfo.buffer, render); // render picture

        videoFrameInfo.clear();
        mEmptyVideoFrameInfos.add(videoFrameInfo);
    }

    /**
     * Releases all codecs. This must be called to free decoder resources when this object is no longer in use.
     */
    public void release() {
        mVideoCodec.stop();
        mVideoCodec.release();
        if(mAudioFormat != null) {
            mAudioCodec.stop();
            mAudioCodec.release();
        }
        Log.d(TAG, "decoder released");
    }
}
