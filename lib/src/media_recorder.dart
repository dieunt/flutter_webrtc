import '../flutter_webrtc.dart';
import './interface/media_recorder.dart' as _interface;

class MediaRecorder extends _interface.MediaRecorder {
  MediaRecorder() : _delegate = mediaRecorder();
  final _interface.MediaRecorder _delegate;

  @override
  Future<void> start(int mode,String path,String peerConnectionId,
          {MediaStreamTrack? videoTrack, MediaStreamTrack? audioTrack,RecorderAudioChannel? audioChannel}) =>
      _delegate.start(mode,path,peerConnectionId, videoTrack: videoTrack, audioTrack: audioTrack,audioChannel: audioChannel);

  @override
  Future stop() => _delegate.stop();

  @override
  void startWeb(
    MediaStream stream, {
    Function(dynamic blob, bool isLastOne)? onDataChunk,
    String? mimeType,
  }) =>
      _delegate.startWeb(stream,
          onDataChunk: onDataChunk, mimeType: mimeType ?? 'video/webm');
}
