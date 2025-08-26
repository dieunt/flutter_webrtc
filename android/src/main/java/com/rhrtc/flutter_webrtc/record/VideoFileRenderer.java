package com.hanet.flutter_webrtc.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;
import org.webrtc.AudioSink;
import org.webrtc.RecordableEncodedFrame;
import org.webrtc.VideoEncodedSink;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;
import org.webrtc.VideoCodecType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.io.FileOutputStream;
import java.io.IOException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
class VideoFileRenderer implements VideoSink,AudioSink,VideoEncodedSink, SamplesReadyCallback {
    private static final String TAG = "VideoFileRenderer";
   private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;
    private int outputFileWidth = -1;
    private int outputFileHeight = -1;
    private ByteBuffer[] encoderOutputBuffers;
    private ByteBuffer[] audioInputBuffers;
    private ByteBuffer[] audioOutputBuffers;
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;
    private boolean video_add_tracked = false;
    private boolean audio_add_tracked = false;
    private boolean havevideo = false;
    private boolean haveaudio = false;
    private boolean resetaudiostartNs = false;
    private boolean onlyaudio = false;
    private int record_mode = 0;
    private int video_frames = 0;
    private int audio_frames = 0;
    private int write_video_frames = 0;
    private int write_audio_frames = 0;
    private int video_key_frames = 0;
    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";//"video/avc";  "video/hevc";  // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private  MediaMuxer mediaMuxer;
    private MediaCodec encoder;
    private final MediaCodec.BufferInfo bufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;
    private int videotrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;
    private MediaCodec audioEncoder;
    private FileOutputStream fos;
    private long audioStartTimeNs = 0;
    private long videostartTimeNs = 0;
    private ByteBuffer h264sps = null;
    private ByteBuffer h264pps= null;
    private ByteBuffer h265sps= null;
    private ByteBuffer h265pps= null;
    private ByteBuffer h265vps= null;
    private boolean waitvideokeyframe = true;
    final int FRAME_SIZE = 1024;
    ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream(10*1024);
    private  ReentrantLock lock = new ReentrantLock();
    VideoFileRenderer(String outputFile, final EglBase.Context sharedContext, int mode) throws IOException {
        Log.i(TAG, "=====>>>VideoFileRenderer" + outputFile);
        record_mode = mode;
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());

        audioThread = new HandlerThread(TAG + "AudioThread");
        audioThread.start();
        audioThreadHandler = new Handler(audioThread.getLooper());

        bufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        mediaMuxer = new MediaMuxer(outputFile,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }
public  ByteBuffer extracth264SpsPps(ByteBuffer dataBuffer) {
        int len = dataBuffer.remaining();
        byte[] data = new byte[dataBuffer.remaining()];
        dataBuffer.get(data); // 将 ByteBuffer 转为字节数组
        int index = 0;
        ByteBuffer newdata = ByteBuffer.wrap(data);
        while (index < data.length - 4) {
            // 1. 查找起始码: 0x000001 或 0x00000001
            if (data[index] == 0x00 && data[index+1] == 0x00) {
                if (data[index+2] == 0x01) { // 3字节起始码
                    processh264Nalu(data, index + 3,3);
                    index += 3;
                } else if (index < data.length - 5 &&
                        data[index+2] == 0x00 && data[index+3] == 0x01) { // 4字节起始码
                    processh264Nalu(data, index + 4,4);
                    index += 4;
                }
            }
            index++;
        }
        return newdata;
    }

    private  void processh264Nalu(byte[] data, int start,int nalhead) {
        // 2. 解析 NALU 类型 (低5位)
        int nalType = data[start] & 0x1F;
        //Log.e(TAG, "processNalu nalType = "+nalType+" start = "+start);
        // 3. 提取 SPS (类型7)
        if (nalType == 7) {
            int len = findNaluEnd(data, start) - start+nalhead;
            byte[] nalData = new byte[len];
            System.arraycopy(data, start-nalhead, nalData, 0, len);
            h264sps = ByteBuffer.wrap(nalData);


        }else if (nalType == 8) {// 4. 提取 PPS (类型8)
            int len = findNaluEnd(data, start) - start+nalhead;
            byte[] nalData = new byte[len];
            System.arraycopy(data, start-nalhead, nalData, 0, len);
            h264pps = ByteBuffer.wrap(nalData);
        }
    }
    public  ByteBuffer extracth265SpsPpsVps(ByteBuffer dataBuffer) {
        int len = dataBuffer.remaining();
        byte[] data = new byte[dataBuffer.remaining()];
        dataBuffer.get(data); // 将 ByteBuffer 转为字节数组
        int index = 0;
        ByteBuffer newdata = ByteBuffer.wrap(data);
        while (index < data.length - 4) {
            // 1. 查找起始码: 0x000001 或 0x00000001
            if (data[index] == 0x00 && data[index+1] == 0x00) {
                if (data[index+2] == 0x01) { // 3字节起始码
                    processh265Nalu(data, index + 3,3);
                    index += 3;
                } else if (index < data.length - 5 &&
                        data[index+2] == 0x00 && data[index+3] == 0x01) { // 4字节起始码
                    processh265Nalu(data, index + 4,4);
                    index += 4;
                }
            }
            index++;
        }
        return newdata;
    }

    private  void processh265Nalu(byte[] data, int start,int nalhead) {
        // 2. 解析 NALU 类型 (低5位)
        int nalType = (data[start] & 0x7E) >> 1;
        //Log.e(TAG, "processh265Nalu nalType = "+nalType+" start = "+start);
        // 3. 提取 SPS (类型7)
        if (nalType == 33) { //提取 SPS (类型33)
            int len = findNaluEnd(data, start) - start+nalhead;
            byte[] nalData = new byte[len];
            System.arraycopy(data, start-nalhead, nalData, 0, len);
            h265sps = ByteBuffer.wrap(nalData);


        }else if (nalType == 34) {// 4. 提取 PPS (类型34)
            int len = findNaluEnd(data, start) - start +nalhead;
            byte[] nalData = new byte[len];
            System.arraycopy(data, start-nalhead, nalData, 0, len);
            h265pps = ByteBuffer.wrap(nalData);
        }else if (nalType == 32) {// 4. 提取 VPS (类型32)
            int len = findNaluEnd(data, start) - start+nalhead;
            byte[] nalData = new byte[len];
            System.arraycopy(data, start-nalhead, nalData, 0, len);
            h265vps = ByteBuffer.wrap(nalData);
        }
    }

    // 查找下一个起始码位置作为结束点
    private  int findNaluEnd(byte[] data, int start) {
        for (int i = start; i < data.length - 4; i++) {
            if (data[i] == 0x00 && data[i+1] == 0x00 &&
                    (data[i+2] == 0x01 || (data[i+2] == 0x00 && data[i+3] == 0x01))) {
                return i; // 返回下一个NALU的起始位置
            }
        }
        return data.length; // 无后续起始码则到数据末尾
    }
    public static void printBufferInfo(ByteBuffer buffer) {
        Log.e(TAG, "=== Buffer状态 ===");
        Log.e(TAG, "类型: " + (buffer.isDirect() ? "Direct" : "Heap"));
        Log.e(TAG, "容量: " + buffer.capacity());
        Log.e(TAG, "位置: " + buffer.position());
        Log.e(TAG, "限制: " + buffer.limit());

        if (buffer.hasArray()) {
            byte[] array = buffer.array();
            Log.e(TAG, "内容: [");
            for (int i = 0; i < Math.min(128, array.length); i++) {
                Log.e(TAG, array[i] + " ");
            }
            Log.e(TAG, array.length > 128 ? "...]" : "]");
        } else {
            Log.e(TAG, "(DirectBuffer需特殊处理)");
            byte[] temp = new byte[Math.min(128, buffer.remaining())];
            buffer.mark();
            buffer.get(temp);
            Log.e(TAG, "首20字节: " + Arrays.toString(temp));
            buffer.reset();
        }
    }
    private void initVideoEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, outputFileWidth, outputFileHeight);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 3000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            renderThreadHandler.post(() -> {
                eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
                surface = encoder.createInputSurface();
                eglBase.createSurface(surface);
                eglBase.makeCurrent();
                drawer = new GlRectDrawer();
                frameDrawer = new VideoFrameDrawer();
            });
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
    }

    /**
     *
     * @param mimeType
     * @param width
     * @param height
     * @return
     */
    private Size getBestFrameSize(String mimeType, int width, int height) {
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = mediaCodecList.getCodecInfos();

        double ratio = height * 1.0 / width;

        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    // 找到了指定类型的编码器
                    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(type);
                    if (capabilities == null) {
                        continue;
                    }
                    MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
                    if (videoCapabilities.isSizeSupported(width, height)) {
                        return new Size(width, height);
                    }
                    Log.i(TAG, "=====>>>不支持分辨率" + new Size(width, height).toString());
                    Log.i(TAG, "=====>>>支持宽度范围" + videoCapabilities.getSupportedWidths().toString());
                    Log.i(TAG, "=====>>>分辨率支持高度范围" + videoCapabilities.getSupportedHeights().toString());
                    Log.i(TAG, "=====>>>宽度对齐" + videoCapabilities.getWidthAlignment());
                    Log.i(TAG, "=====>>>高度对齐" + videoCapabilities.getHeightAlignment());

                    Range<Integer> supportedWidths = videoCapabilities.getSupportedWidths();
                    for (int w = Math.min(supportedWidths.getUpper(), width); w > supportedWidths.getLower();) {
                        int maxWidth = w;
                        int maxHeight = (int) Math.floor(maxWidth * ratio);
//                        Log.i(TAG, "=====>>>" + maxWidth + "   " + maxHeight);
                        // 获取对齐宽度， getWidthAlignment的整数倍
                        int alignedWidth = (maxWidth + videoCapabilities.getWidthAlignment() - 1) / videoCapabilities.getWidthAlignment() * videoCapabilities.getWidthAlignment();
                        // 获取对齐高度，getHeightAlignment的整数倍
                        int alignedHeight = (maxHeight + videoCapabilities.getHeightAlignment() - 1) / videoCapabilities.getHeightAlignment() * videoCapabilities.getHeightAlignment();
                        try {
                            if (videoCapabilities.isSizeSupported(alignedWidth, alignedHeight)) {
                                return new Size(alignedWidth, alignedHeight);
                            } else {
//                                Log.i(TAG, "=====>>>不支持" + alignedWidth + "   " + alignedHeight);
                            }
                        } catch (Exception e) {
                        }

                        w = alignedWidth - videoCapabilities.getWidthAlignment();
                    }

                }
            }
        }
        return new Size(width, height);
    }


    @Override
    public void onFrame(VideoFrame frame) {
        video_frames ++;
        havevideo = true;
        if(audio_frames == 0 && video_frames<20){
            return;
        }else if(audio_frames>0 && !audio_add_tracked){
            return;
        }
        frame.retain();
        if (outputFileWidth == -1) {
            outputFileWidth = frame.getRotatedWidth();
            outputFileHeight = frame.getRotatedHeight();

            Size bestSize = getBestFrameSize(MIME_TYPE, outputFileWidth, outputFileHeight);
            Log.d(TAG, "=====>>>适应的分辨率: " + bestSize.getWidth() + "*" + bestSize.getHeight());
            Log.d(TAG, "=====>>>适应的分辨率， 原来的比例" + outputFileWidth * 1.0 / outputFileHeight + "新比例" + bestSize.getWidth() * 1.0 / bestSize.getHeight());
            outputFileWidth = bestSize.getWidth();
            outputFileHeight = bestSize.getHeight();



            initVideoEncoder();

        }
        renderThreadHandler.post(() -> renderFrameOnRenderThread(frame));
        
    }

    private void renderFrameOnRenderThread(VideoFrame frame) {
      
        lock.lock();
        if (frameDrawer == null) {
            frame.release();
            lock.unlock();
           return;
        }
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
        frame.release();
        drainEncoder();
        eglBase.swapBuffers();
        lock.unlock();
    
    }
    @Override
    public void onFrame(RecordableEncodedFrame frame) {
         video_frames ++;
        havevideo = true;
        lock.lock();
        if(frame.isKeyFrame() ){
            video_key_frames++;
            waitvideokeyframe = false;
        }else{
            if(waitvideokeyframe){
                Log.e(TAG, "onFrame   audio_frames= " +audio_frames+" video_frames= "+video_frames+" audio_add_tracked="+audio_add_tracked);
            }

        }

        if(audio_frames == 0 && video_key_frames<1){
            lock.unlock();
            return;
        }else if(audio_frames>0 && !audio_add_tracked){
            lock.unlock();
            return;
        }

        ByteBuffer databuf = frame.getEncodedData();
        if(frame.isKeyFrame() ){
            waitvideokeyframe = false;

            if(muxerStarted == false) {
                if (video_add_tracked == true && audio_add_tracked == false && haveaudio == false && video_frames > 10) {
                    mediaMuxer.start();
                    muxerStarted = true;
                }
            }

        }


        if(muxerStarted == false && video_add_tracked == false) {

            int width = frame.getWidth();
            int height = frame.getHeight();
            int codecType = frame.getCodecType();
            if(codecType == (int)VideoCodecType.VIDEO_CODEC_H264){

                 if(frame.isKeyFrame()){
                     Log.e(TAG, "onFrame   isKeyFrame= " +frame.isKeyFrame()+" CodecType= "+frame.getCodecType());
                     databuf = extracth264SpsPps(databuf);
                     if(h264sps!= null && h264pps!=null){
                         MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                         format.setInteger(MediaFormat.KEY_BIT_RATE, 2 * width * height);
                         format.setByteBuffer("csd-0", h264sps); // SPS 参数
                         format.setByteBuffer("csd-1", h264pps); // PPS 参数
                         format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                         format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                         format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                         format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                         Log.e(TAG, "video encoder output format changed: " + format);
                         videotrackIndex = mediaMuxer.addTrack(format);
                     }
                 }
            }else if(codecType == (int)VideoCodecType.VIDEO_CODEC_H265){
                if(frame.isKeyFrame()) {
                    databuf = extracth265SpsPpsVps(databuf);
                    if (h265vps != null && h265sps != null && h265pps != null) {
                        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, 2 * width * height);

                        ByteBuffer csd0 = ByteBuffer.allocate(
                                h265vps.remaining() + h265sps.remaining() + h265pps.remaining());
                        csd0.put(h265vps);
                        csd0.put(h265sps);
                        csd0.put(h265pps);
                        csd0.flip();

                        format.setByteBuffer("csd-0", csd0);
                        //printBufferInfo(csd0);

                        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                        videotrackIndex = mediaMuxer.addTrack(format);
                        Log.e(TAG, "video encoder output format changed: " + format);
                    }
                }
            }else if(codecType == (int)VideoCodecType.VIDEO_CODEC_VP8){
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 2*width * height);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                videotrackIndex = mediaMuxer.addTrack(format);
                Log.e(TAG, "video encoder output format changed: " + format);
            }else if(codecType == (int)VideoCodecType.VIDEO_CODEC_VP9){
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, width, height);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 2*width * height);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                videotrackIndex = mediaMuxer.addTrack(format);
                Log.e(TAG, "video encoder output format changed: " + format);
            }

            if (videotrackIndex != -1) {

                video_add_tracked = true;
                if(audio_add_tracked) {
                    Log.e(TAG, "video mediaMuxer start " );
                    mediaMuxer.start();
                    resetaudiostartNs = true;
                    muxerStarted = true;
                }else if(video_frames>100){
                    Log.e(TAG, "mediaMuxer wait for audio data timeout, start this file will no audio" );
                    mediaMuxer.start();
                    muxerStarted = true;
                }

            }
        }
        if(muxerStarted == true && video_add_tracked == true) {
            long presentationTimeUs = 0;
            bufferInfo.presentationTimeUs = presentationTimeUs;
            if (muxerStarted && videotrackIndex!= -1 && waitvideokeyframe == false) {
                     if(videostartTimeNs == 0){
                         videostartTimeNs = System.nanoTime();
                     }
                     presentationTimeUs = (System.nanoTime() - videostartTimeNs) / 1000L;
                     bufferInfo.presentationTimeUs = presentationTimeUs;
                     //Log.e(TAG, "video writeSampleData len = "+databuf.remaining()+" presentationTimeUs = "+bufferInfo.presentationTimeUs);
                     bufferInfo.size = databuf.remaining();
                     bufferInfo.offset = 0;
                     bufferInfo.flags = frame.isKeyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                     write_video_frames++;
                     mediaMuxer.writeSampleData(videotrackIndex, databuf, bufferInfo);
            }
        }
        frame.release();
        lock.unlock();
    }

    /**
     * Release all resources. All already posted frames will be rendered first.
     */
    void release() {
         Log.e(TAG, "=====>>>release:");
    
        isRunning = false;
        muxerStarted = false;
        if (audioThread != null) audioThread.quitSafely();
        if (renderThread != null) renderThread.quitSafely();

        try {
            if (audioThread != null) audioThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "audioThread join interrupted", e);
        }

        try {
            if (renderThread != null) renderThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "renderThread join interrupted", e);
        }
        try {
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
                audioEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "renderThread join interrupted", e);
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "renderThread join interrupted", e);
        }
        if(eglBase!=null){
            try {
                eglBase.release();
                eglBase = null;
            } catch (Exception e) {
                Log.e("eglBase", "eglBase release: " + e);
            }
        }

        if(mediaMuxer!= null){
            try {
               // if(muxerStarted) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                    mediaMuxer = null;
                //}
            } catch (Exception e) {
                Log.e("MediaMuxer", "Stop record: " + e);
            }

        }
        Log.e(TAG, "release end");
    }

    private boolean encoderStarted = false;
    private volatile boolean muxerStarted = false;
    private long videoFrameStart = 0;

    private void drainEncoder() {
        //  Log.e(TAG, "=====>>>drainEncoder:");
         if (!encoderStarted) {
            encoder.start();
            encoderOutputBuffers = encoder.getOutputBuffers();
            encoderStarted = true;
            return;
        }
        while (isRunning) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Log.e(TAG, "encoder encoderStatus INFO_TRY_AGAIN_LATER");
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = encoder.getOutputFormat();
                //ByteBuffer csd = newFormat.getByteBuffer("csd-0");
                //printBufferInfo(csd);
                Log.e(TAG, "video encoder output format changed: " + newFormat);

                if(muxerStarted == false) {
                    videotrackIndex = mediaMuxer.addTrack(newFormat);
                    if (videotrackIndex != -1) {
                        video_add_tracked = true;
                        if(audio_add_tracked){
                            Log.e(TAG, "video mediaMuxer start " );
                            mediaMuxer.start();
                            resetaudiostartNs = true;
                            muxerStarted = true;

                        }else if(video_frames>20){
                            Log.e(TAG, "mediaMuxer wait for audio data timeout, start this file will no audio" );
                            mediaMuxer.start();
                            muxerStarted = true;
                        }


                    }
                }

                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if(isKeyFrame){
                        waitvideokeyframe = false;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                    if(videostartTimeNs == 0){
                        videostartTimeNs = System.nanoTime();
                    }
                    //videoFrameStart
                    long presentationTimeUs = (System.nanoTime() - videostartTimeNs) / 1000L;
                    bufferInfo.presentationTimeUs = presentationTimeUs;
                    if (muxerStarted && videotrackIndex!= -1 && waitvideokeyframe == false) {
                        write_video_frames++;
                        mediaMuxer.writeSampleData(videotrackIndex, encodedData, bufferInfo);
                    }
                    isRunning = isRunning && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private long presTime = 0L;

    private void drainAudio() {
         if (audioBufferInfo == null) {
            audioBufferInfo = new MediaCodec.BufferInfo();
        }


        while (isRunning) {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                audioOutputBuffers = audioEncoder.getOutputBuffers();
                Log.w(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = audioEncoder.getOutputFormat();
                Log.e(TAG, "audio encoder output format changed: " + newFormat);
                if(!muxerStarted) {
                    audioTrackIndex = mediaMuxer.addTrack(newFormat);
                    if (audioTrackIndex != -1) {
                        audio_add_tracked = true;
                        if(onlyaudio){
                            mediaMuxer.start();
                            muxerStarted = true;
                        }
                    }
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = audioOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(audioBufferInfo.offset);
                    encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);


                    if (muxerStarted && audioTrackIndex!=-1 && !waitvideokeyframe) {
                        write_audio_frames++;
                        mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                    }
                    isRunning = isRunning && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    audioEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }
    @Override
    public void OnData(byte[] audioData, int bitsPerSample,
                int sampleRate, int channels,
                int frames){
       // Log.e(TAG, "OnData len =  " + audioData.length+"  frames= " +frames);
        haveaudio = true;
        audio_frames++;
        if (!isRunning) {
            return;
        }
        if(waitvideokeyframe){
            //Log.e(TAG, "OnData len =  " + audioData.length+"  frames= " +frames);
            if(!havevideo) {
                if(audio_frames>200){
                    onlyaudio= true;
                }else {
                    return;
                }
            }
        }

        int frameByteSize = FRAME_SIZE * channels * 2;

        if(audioData.length<=0){
            return ;
        }
        pcmBuffer.write(audioData, 0, audioData.length);
        while (pcmBuffer.size() >= frameByteSize) {

            byte[] frameData = new byte[frameByteSize];
            System.arraycopy(pcmBuffer.toByteArray(), 0, frameData, 0, frameByteSize);

            audioThreadHandler.post(() -> {
                lock.lock();
                if (audioEncoder == null) try {
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
                    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
                    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                    format.setInteger(MediaFormat.KEY_LATENCY, 1);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();
                    audioInputBuffers = audioEncoder.getInputBuffers();
                    audioOutputBuffers = audioEncoder.getOutputBuffers();
                } catch (IOException exception) {
                    Log.wtf(TAG, exception);
                }
                int bufferIndex = audioEncoder.dequeueInputBuffer(0);
                if (bufferIndex >= 0) {
                    ByteBuffer buffer = audioInputBuffers[bufferIndex];
                    buffer.clear();
                    buffer.put(frameData);

                    //long theoreticalTimeUs = (audiototalSamples * 1000000L) / aduiosampleRate;
                   // long presentationTimeUs = theoreticalTimeUs + audiotimeOffsetUs;
                    if (audioStartTimeNs == 0 || resetaudiostartNs) {
                        audioStartTimeNs = System.nanoTime();
                        if(resetaudiostartNs){
                            resetaudiostartNs = false;
                        }
                    }
                    long presentationTimeUs = (System.nanoTime() - audioStartTimeNs) / 1000L;
                    audioEncoder.queueInputBuffer(bufferIndex, 0, frameData.length, presentationTimeUs, 0);

                    // presTime += audioData.length * 1000000 / (sampleRate*2); // 1000000 microseconds / 48000hz / 2 bytes

                    //presTime = presentationTimeUs;
                }
                drainAudio();
                lock.unlock();
            });
            byte[] remaining = Arrays.copyOfRange(
                    pcmBuffer.toByteArray(),
                    frameByteSize,
                    pcmBuffer.size()
            );
            pcmBuffer.reset();
            pcmBuffer.write(remaining, 0, remaining.length);

        }
    }
    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
       // Log.e(TAG, "onWebRtcAudioRecordSamplesReady ");
        audio_frames++;
        haveaudio = true;
        int frameByteSize = FRAME_SIZE * audioSamples.getChannelCount() * 2;
        byte[] pcmdata = audioSamples.getData();
        if(pcmdata.length<=0){
            return ;
        }
        pcmBuffer.write(pcmdata, 0, pcmdata.length);
        if (!isRunning) {
            return;
        }

        while (pcmBuffer.size() >= frameByteSize) {

            byte[] frameData = new byte[frameByteSize];
            System.arraycopy(pcmBuffer.toByteArray(), 0, frameData, 0, frameByteSize);
            audioThreadHandler.post(() -> {
                lock.lock();
                if (audioEncoder == null) try {
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
                    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioSamples.getChannelCount());
                    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, audioSamples.getSampleRate());
                    format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                    format.setInteger(MediaFormat.KEY_LATENCY, 1);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();
                    audioInputBuffers = audioEncoder.getInputBuffers();
                    audioOutputBuffers = audioEncoder.getOutputBuffers();
                } catch (IOException exception) {
                    Log.wtf(TAG, exception);
                }
                int bufferIndex = audioEncoder.dequeueInputBuffer(0);
                if (bufferIndex >= 0) {
                    ByteBuffer buffer = audioInputBuffers[bufferIndex];
                    buffer.clear();
                    buffer.put(frameData);

                   // long theoreticalTimeUs = (audiototalSamples * 1000000L) / audioSamples.getSampleRate();
                    //long presentationTimeUs = theoreticalTimeUs + audiotimeOffsetUs;

                    if (audioStartTimeNs == 0 || resetaudiostartNs) {
                        audioStartTimeNs = System.nanoTime();
                        if(resetaudiostartNs){
                            resetaudiostartNs = false;
                        }
                    }
                    long presentationTimeUs = (System.nanoTime() - audioStartTimeNs) / 1000L;
                    audioEncoder.queueInputBuffer(bufferIndex, 0, frameData.length, presentationTimeUs, 0);
                }
                drainAudio();
                lock.unlock();
            });


            byte[] remaining = Arrays.copyOfRange(
                    pcmBuffer.toByteArray(),
                    frameByteSize,
                    pcmBuffer.size()
            );
            pcmBuffer.reset();
            pcmBuffer.write(remaining, 0, remaining.length);

        }
    }

}
