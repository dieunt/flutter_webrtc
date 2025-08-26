package com.rhrtc.flutter_webrtc.record;

import androidx.annotation.Nullable;
import android.util.Log;

import com.rhrtc.flutter_webrtc.utils.EglUtils;
import org.webrtc.AudioTrack;
import org.webrtc.VideoTrack;

import java.io.File;

public class MediaRecorderImpl {

    private final Integer id;
    private final Integer record_mode;
    private final VideoTrack videoTrack;
    private final AudioTrack audioTrack;
    private final AudioSamplesInterceptor audioInterceptor;
    private VideoFileRenderer videoFileRenderer;
    private boolean isRunning = false;
    private File recordFile;

    public MediaRecorderImpl(Integer mode,Integer id, @Nullable VideoTrack videoTrack,  @Nullable AudioTrack audioTrack,@Nullable AudioSamplesInterceptor audioInterceptor) {
        this.record_mode = mode;
        this.id = id;
        this.videoTrack = videoTrack;
         this.audioTrack = audioTrack;
        this.audioInterceptor = audioInterceptor;
    }

    public void startRecording(File file) throws Exception {
        //Log.e(TAG, "startRecording  -----------------------------------------------");
          Log.e(TAG, "startRecording  -----------------------------------------------record_mode = "+record_mode);
        recordFile = file;
        if (isRunning)
            return;
        isRunning = true;
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        if (videoTrack != null) {
            videoFileRenderer = new VideoFileRenderer(
                file.getAbsolutePath(),
                EglUtils.getRootEglBaseContext(),
                record_mode
            );
           if(record_mode ==0){
                videoTrack.addSink(videoFileRenderer);
                if (audioInterceptor != null) {
                    audioInterceptor.attachCallback(id, videoFileRenderer);
                }
            }else if(record_mode ==1){
                videoTrack.addEncodedSink(videoFileRenderer);
                videoTrack.GenerateKeyFrame();
                if (audioInterceptor != null) {
                    audioInterceptor.attachCallback(id, videoFileRenderer);
                }
            }else if(record_mode ==2){
              
                videoTrack.addSink(videoFileRenderer);
                audioTrack.addSink(videoFileRenderer);
            }else if(record_mode ==3){
                videoTrack.addEncodedSink(videoFileRenderer);
                videoTrack.GenerateKeyFrame();
                audioTrack.addSink(videoFileRenderer);
            }else{
                videoTrack.addSink(videoFileRenderer);
                if (audioInterceptor != null) {
                    audioInterceptor.attachCallback(id, videoFileRenderer);
                }
            }
        } else {
            Log.e(TAG, "Video track is null");
            if (audioInterceptor != null) {
                //TODO(rostopira): audio only recording
                throw new Exception("Audio-only recording not implemented yet");
            }
        }
    }

    public File getRecordFile() { return recordFile; }

    public void stopRecording() {
        isRunning = false;
        if (audioInterceptor != null){
                       audioInterceptor.detachCallback(id);
        }
  
        if (videoTrack != null && videoFileRenderer != null) {

            if(record_mode ==1 || record_mode ==3) {
                videoTrack.removeEncodedSink(videoFileRenderer);
            }else{
                videoTrack.removeSink(videoFileRenderer);
            }
        }
        if (audioTrack != null && videoFileRenderer != null) {
            if(record_mode ==2) {
                audioTrack.removeSink(videoFileRenderer);
            }else if(record_mode ==3){
                audioTrack.removeSink(videoFileRenderer);
            }
        }
        if(videoFileRenderer!= null){
            videoFileRenderer.release();
            videoFileRenderer = null;
        }
    }

    private static final String TAG = "MediaRecorderImpl";

}
