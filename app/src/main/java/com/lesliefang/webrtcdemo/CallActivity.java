package com.lesliefang.webrtcdemo;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import pub.devrel.easypermissions.EasyPermissions;

public class CallActivity extends AppCompatActivity {
    static final String TAG = "WebRtc";
    String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    Button btnCall;
    TextView tvUser;
    SurfaceViewRenderer surfaceViewRendererLocal, surfaceViewRendererRemote;
    WebSocketChannel webSocketChannel;

    PeerConnectionFactory peerConnectionFactory;
    PeerConnection peerConnection;
    AudioTrack audioTrack;
    VideoTrack videoTrack;
    VideoCapturer videoCapturer;
    SurfaceTextureHelper surfaceTextureHelper;

    EglBase.Context eglBaseContext;

    final String LOCAL_USER_ID = "aaaa";
    final String RECEIVER_USER_ID = "bbbb";

    final String WEBSOCKET_URL = "ws://192.168.0.112:5520/websocket";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        btnCall = findViewById(R.id.btn_call);
        tvUser = findViewById(R.id.tv_user);
        surfaceViewRendererLocal = findViewById(R.id.surfaceviewrender_local);
        surfaceViewRendererRemote = findViewById(R.id.surfaceviewrender_remote);

        tvUser.setText("当前用户：" + LOCAL_USER_ID + "  对端用户：" + RECEIVER_USER_ID);

        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            return;
        }

        eglBaseContext = EglBase.create().getEglBaseContext();

        surfaceViewRendererLocal.init(eglBaseContext, null);
        surfaceViewRendererLocal.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        surfaceViewRendererLocal.setKeepScreenOn(true);

        surfaceViewRendererRemote.init(eglBaseContext, null);
        surfaceViewRendererRemote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        surfaceViewRendererRemote.setKeepScreenOn(true);

        peerConnectionFactory = createPeerConnectionFactory();
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
//        videoCapturer.startCapture(1280, 720, 30);

        videoTrack = peerConnectionFactory.createVideoTrack("102", videoSource);
        videoTrack.addSink(surfaceViewRendererLocal);

        btnCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                call();
            }
        });

        webSocketChannel = new WebSocketChannel();
        webSocketChannel.setWebSocketCallback(webSocketCallback);
        webSocketChannel.connect(WEBSOCKET_URL);

        if (!EasyPermissions.hasPermissions(CallActivity.this, perms)) {
            EasyPermissions.requestPermissions(CallActivity.this, "需要相机和录音权限",
                    100, perms);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webSocketChannel.close();
        if (peerConnection != null) {
            peerConnection.close();
        }
        surfaceViewRendererLocal.release();
        surfaceViewRendererRemote.release();
        videoCapturer.dispose();
        surfaceTextureHelper.dispose();
    }

    private void call() {
        if (!EasyPermissions.hasPermissions(CallActivity.this, perms)) {
            EasyPermissions.requestPermissions(CallActivity.this, "需要相机和录音权限",
                    100, perms);
            return;
        }
        createOffer();
    }

    private VideoCapturer createVideoCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            return createCameraCapturer(new Camera2Enumerator(this));
        } else {
            return createCameraCapturer(new Camera1Enumerator(true));
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Log.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Log.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private PeerConnectionFactory createPeerConnectionFactory() {
        final VideoEncoderFactory encoderFactory;
        final VideoDecoderFactory decoderFactory;

        encoderFactory = new DefaultVideoEncoderFactory(
                eglBaseContext, false /* enableIntelVp8Encoder */, true);
        decoderFactory = new DefaultVideoDecoderFactory(eglBaseContext);

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions());

        PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory);
        builder.setOptions(null);

        return builder.createPeerConnectionFactory();
    }

    private PeerConnection createPeerConnection() {
        List<PeerConnection.IceServer> iceServerList = new ArrayList<>();
        // 使用 google 的 stun 服务器做NAT内网穿透
        iceServerList.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(iceServerList, peerConnectionObserver);
        peerConnection.addTrack(audioTrack);
        peerConnection.addTrack(videoTrack);
        return peerConnection;
    }

    private PeerConnection.Observer peerConnectionObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
            Log.d(TAG, "onSignalingChange " + newState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.d(TAG, "onIceConnectionChange " + newState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "onIceConnectionReceivingChange " + receiving);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Log.d(TAG, "onIceGatheringChange " + newState);
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                // trickle complete
                videoCapturer.startCapture(1280, 720, 30);
            }
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Log.d(TAG, "onIceCandidate");
            // 发送 IceCandidate 到对端
            try {
                JSONObject msgObj = new JSONObject();
                msgObj.put("event", "trickle");
                msgObj.put("sender", LOCAL_USER_ID); // 发送者
                msgObj.put("receiver", RECEIVER_USER_ID); // 接收者
                JSONObject candidateObj = new JSONObject();
                candidateObj.put("sdpMid", candidate.sdpMid);
                candidateObj.put("sdpMLineIndex", candidate.sdpMLineIndex);
                candidateObj.put("sdp", candidate.sdp);
                msgObj.put("candidate", candidateObj);
                webSocketChannel.sendMessage(msgObj.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Log.d(TAG, "onIceCandidatesRemoved");
            peerConnection.removeIceCandidates(candidates);
        }

        @Override
        public void onAddStream(MediaStream stream) {
            Log.d(TAG, "onAddStream");
            // 接收远程远程视频流
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (stream.videoTracks.size() > 0) {
                        stream.videoTracks.get(0).addSink(surfaceViewRendererRemote);
                        Log.d(TAG, "onAddStream addVideoTrack");
                    }
                }
            });
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
            Log.d(TAG, "onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "onAddTrack ");
        }
    };

    private void createOffer() {
        if (peerConnection == null) {
            peerConnection = createPeerConnection();
        }

        MediaConstraints mediaConstraints = new MediaConstraints();
//                mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
//                mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
//                mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "createOffer onCreateSuccess " + sdp.toString());
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        Log.d(TAG, "createOffer setLocalDescription onCreateSuccess");
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "createOffer setLocalDescription onSetSuccess");
                        // 发送 offer 到对端
                        try {
                            JSONObject msgObj = new JSONObject();
                            msgObj.put("event", "sdp");
                            msgObj.put("type", sdp.type.toString());
                            msgObj.put("description", sdp.description);
                            msgObj.put("sender", LOCAL_USER_ID); // 发送者
                            msgObj.put("receiver", RECEIVER_USER_ID); // 接收者
                            webSocketChannel.sendMessage(msgObj.toString());
                            Log.d(TAG, "send offer");
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onCreateFailure(String error) {
                        Log.d(TAG, "createOffer setLocalDescription onCreateFailure " + error);
                    }

                    @Override
                    public void onSetFailure(String error) {
                        Log.d(TAG, "createOffer setLocalDescription onSetFailure " + error);
                    }
                }, sdp);
            }

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "createOffer onSetSuccess");
            }

            @Override
            public void onCreateFailure(String error) {
                Log.d(TAG, "createOffer onCreateFailure " + error);
            }

            @Override
            public void onSetFailure(String error) {
                Log.d(TAG, "createOffer onSetFailure " + error);
            }
        }, mediaConstraints);
    }

    private void createAnswer() {
        if (peerConnection == null) {
            peerConnection = createPeerConnection();
        }

        MediaConstraints mediaConstraints = new MediaConstraints();
//                mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
//                mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
//                mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));

        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                    }

                    @Override
                    public void onSetSuccess() {
                        // send answer sdp
                        Log.d(TAG, "createAnswer setLocalDescription onSetSuccess");
                        try {
                            JSONObject msgObj = new JSONObject();
                            msgObj.put("event", "sdp");
                            msgObj.put("type", sdp.type.toString());
                            msgObj.put("description", sdp.description);
                            msgObj.put("sender", LOCAL_USER_ID); // 发送者
                            msgObj.put("receiver", RECEIVER_USER_ID); // 接收者
                            webSocketChannel.sendMessage(msgObj.toString());
                            Log.d(TAG, "createAnswer send answer sdp");
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.d(TAG, "createAnswer setLocalDescription onCreateFailure " + s);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.d(TAG, "createAnswer setLocalDescription onSetFailure " + s);
                    }
                }, sdp);
            }

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "createAnswer onSetSuccess");
            }

            @Override
            public void onCreateFailure(String s) {
                Log.d(TAG, "createAnswer onCreateFailure " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.d(TAG, "createAnswer onSetFailure " + s);
            }
        }, mediaConstraints);
    }

    private WebSocketChannel.WebSocketCallback webSocketCallback = new WebSocketChannel.WebSocketCallback() {
        @Override
        public void onOpen() {
            // 成功连接到 websocket server 后注册自己的 UserId
            try {
                JSONObject registerMsg = new JSONObject();
                registerMsg.put("event", "register");
                registerMsg.put("userId", LOCAL_USER_ID);
                webSocketChannel.sendMessage(registerMsg.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onMessage(String text) {
            try {
                JSONObject msgObj = new JSONObject(text);
                String event = msgObj.getString("event");
                if ("sdp".equals(event)) {
                    String sdpType = msgObj.getString("type");
                    String sdpDescription = msgObj.getString("description");
                    if (sdpType.toLowerCase().equals("offer")) {
                        // 收到 offer
                        if (peerConnection == null) {
                            peerConnection = createPeerConnection();
                        }
                        SessionDescription offerSdp = new SessionDescription(SessionDescription.Type.OFFER, sdpDescription);
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription sessionDescription) {
                                Log.d(TAG, "receive Offer setRemoteDescription onCreateSuccess");
                            }

                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "receive Offer setRemoteDescription onSetSuccess");
                                // 发送 answer
                                createAnswer();
                            }

                            @Override
                            public void onCreateFailure(String s) {
                                Log.d(TAG, "receive Offer setRemoteDescription onCreateFailure " + s);
                            }

                            @Override
                            public void onSetFailure(String s) {
                                Log.d(TAG, "receive Offer setRemoteDescription onSetFailure " + s);
                            }
                        }, offerSdp);
                    } else if (sdpType.toLowerCase().equals("answer")) {
                        // 收到 answer
                        SessionDescription answerSdp = new SessionDescription(SessionDescription.Type.ANSWER, sdpDescription);
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription sessionDescription) {
                                Log.d(TAG, "receive Answer setRemoteDescription onCreateSuccess");
                            }

                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "receive Answer setRemoteDescription onSetSuccess");
                            }

                            @Override
                            public void onCreateFailure(String s) {
                                Log.d(TAG, "receive Answer setRemoteDescription onCreateFailure " + s);
                            }

                            @Override
                            public void onSetFailure(String s) {
                                Log.d(TAG, "receive Answer setRemoteDescription onSetFailure " + s);
                            }
                        }, answerSdp);
                    }
                } else if ("trickle".equals(event)) {
                    // 收到 ICE trickle 信息
                    JSONObject candidateObj = msgObj.getJSONObject("candidate");
                    String sdpMid = candidateObj.getString("sdpMid");
                    int sdpMLineIndex = candidateObj.getInt("sdpMLineIndex");
                    String sdp = candidateObj.getString("sdp");
                    IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                    peerConnection.addIceCandidate(candidate);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onClosed() {

        }
    };
}