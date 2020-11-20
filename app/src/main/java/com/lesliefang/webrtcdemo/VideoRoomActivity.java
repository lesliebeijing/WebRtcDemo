package com.lesliefang.webrtcdemo;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

/**
 * 网状P2P聊天室
 */
public class VideoRoomActivity extends AppCompatActivity {
    final String TAG = "VideoRoomActivity";
    String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    RecyclerView recyclerView;
    TextView tvUserId;
    Button btnJoinRoom, btnLeaveRoom;
    VideoItemAdapter adapter;
    List<VideoItem> videoItemList = new ArrayList<>();

    WebSocketChannel webSocketChannel;

    PeerConnectionFactory peerConnectionFactory;
    AudioTrack audioTrack;
    VideoTrack videoTrack;
    VideoCapturer videoCapturer;
    SurfaceTextureHelper surfaceTextureHelper;

    EglBase.Context eglBaseContext;

    final String LOCAL_USER_ID = "cccc";

    final String WEBSOCKET_URL = "ws://192.168.0.101:5520/websocket";

    boolean hasJoinedRoom = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_room);
        recyclerView = findViewById(R.id.recyclerview);
        tvUserId = findViewById(R.id.tv_userid);
        btnJoinRoom = findViewById(R.id.btn_joinroom);
        btnLeaveRoom = findViewById(R.id.btn_leaveroom);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));

        tvUserId.setText("当前用户：" + LOCAL_USER_ID);

        btnJoinRoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!EasyPermissions.hasPermissions(VideoRoomActivity.this, perms)) {
                    EasyPermissions.requestPermissions(VideoRoomActivity.this, "需要相机和录音权限",
                            100, perms);
                    return;
                }
                // 加入房间
                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("event", "joinRoom");
                    jsonObject.put("userId", LOCAL_USER_ID);
                    // 发送加入房间消息
                    webSocketChannel.sendMessage(jsonObject.toString());
                    Toast.makeText(VideoRoomActivity.this, "加入房间成功", Toast.LENGTH_SHORT).show();
                    hasJoinedRoom = true;

                    VideoItem localVideoItem = addNewVideoItem(LOCAL_USER_ID);
                    localVideoItem.videoTrack = videoTrack;
                    adapter.notifyItemChanged(videoItemList.size() - 1);
                    videoCapturer.startCapture(1280, 720, 30);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        btnLeaveRoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasJoinedRoom) {
                    return;
                }
                // 离开房间
                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("event", "leaveRoom");
                    jsonObject.put("userId", LOCAL_USER_ID);
                    // 发送离开房间消息
                    webSocketChannel.sendMessage(jsonObject.toString());
                    Toast.makeText(VideoRoomActivity.this, "离开房间了", Toast.LENGTH_SHORT).show();
                    hasJoinedRoom = false;
                    try {
                        videoCapturer.stopCapture();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                for (VideoItem videoItem : videoItemList) {
                    if (videoItem.surfaceViewRenderer != null) {
                        videoItem.surfaceViewRenderer.release();
                    }
                    if (videoItem.peerConnection != null) {
                        videoItem.peerConnection.close();
                    }
                }
                videoItemList.clear();
                adapter.notifyDataSetChanged();
            }
        });

        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            return;
        }

        eglBaseContext = EglBase.create().getEglBaseContext();

        peerConnectionFactory = createPeerConnectionFactory();
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());

        videoTrack = peerConnectionFactory.createVideoTrack("102", videoSource);

        webSocketChannel = new WebSocketChannel();
        webSocketChannel.setWebSocketCallback(webSocketCallback);
        webSocketChannel.connect(WEBSOCKET_URL);

        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "需要相机和录音权限",
                    100, perms);
        }

        adapter = new VideoItemAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoCapturer.dispose();
        surfaceTextureHelper.dispose();
    }

    class VideoItemAdapter extends RecyclerView.Adapter<VideoItemHolder> {

        @NonNull
        @Override
        public VideoItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.videoroom_item, parent, false);
            return new VideoItemHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull VideoItemHolder holder, int position) {
            VideoItem videoItem = videoItemList.get(position);
            if (videoItem.videoTrack != null) {
                videoItem.videoTrack.addSink(holder.surfaceViewRenderer);
            }
            videoItem.surfaceViewRenderer = holder.surfaceViewRenderer;
            if (LOCAL_USER_ID.equals(videoItem.userId)) {
                holder.tvUserId.setText("我");
            } else {
                holder.tvUserId.setText(videoItem.userId);
            }
        }

        @Override
        public int getItemCount() {
            return videoItemList.size();
        }
    }

    class VideoItemHolder extends RecyclerView.ViewHolder {
        SurfaceViewRenderer surfaceViewRenderer;
        TextView tvUserId;

        VideoItemHolder(@NonNull View itemView) {
            super(itemView);
            surfaceViewRenderer = itemView.findViewById(R.id.surfaceviewrender);
            surfaceViewRenderer.init(eglBaseContext, null);
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            surfaceViewRenderer.setKeepScreenOn(true);
            tvUserId = itemView.findViewById(R.id.tv_userid);
        }
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

    private PeerConnection createPeerConnection(VideoItem videoItem) {
        List<PeerConnection.IceServer> iceServerList = new ArrayList<>();
        // 使用 google 的 stun 服务器做NAT内网穿透
        iceServerList.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(iceServerList, new PeerConnection.Observer() {
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
                    Log.d(TAG, "onIceGatheringChange trickle complete");
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
                    msgObj.put("receiver", videoItem.userId); // 接收者
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
//                peerConnection.removeIceCandidates(candidates);
            }

            @Override
            public void onAddStream(MediaStream stream) {
                Log.d(TAG, "onAddStream");
                // 接收远程远程视频流
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (stream.videoTracks.size() > 0) {
                            videoItem.videoTrack = stream.videoTracks.get(0);
                            adapter.notifyDataSetChanged();
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
        });
        peerConnection.addTrack(audioTrack);
        peerConnection.addTrack(videoTrack);
        return peerConnection;
    }

    private void createOffer(PeerConnection peerConnection, String receiverUserId) {
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
                            msgObj.put("receiver", receiverUserId); // 接收者
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

    private void createAnswer(PeerConnection peerConnection, String receiverUserId) {
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
                            msgObj.put("receiver", receiverUserId); // 接收者
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleMessage(text);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        @Override
        public void onClosed() {

        }
    };

    private void handleMessage(String text) throws JSONException {
        JSONObject msgObj = new JSONObject(text);
        String event = msgObj.getString("event");
        if ("sdp".equals(event)) {
            String sdpType = msgObj.getString("type");
            String sdpDescription = msgObj.getString("description");
            String sender = msgObj.getString("sender"); // 发送者
            if (sdpType.toLowerCase().equals("offer")) {
                // 我已经在房间内，收到新加入者给我发的 offer
                VideoItem videoItem = addNewVideoItem(sender);
                // 创建 PeerConnection ,准备回复 answer
                PeerConnection peerConnection = createPeerConnection(videoItem);
                videoItem.peerConnection = peerConnection;

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
                        createAnswer(peerConnection, sender);
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
                // 我收到 answer
                PeerConnection peerConnection = getPeerConnectionByUserId(sender);
                if (peerConnection == null) {
                    Log.e(TAG, "fatal error 未找到 answer 用户对应的 PeerConnection");
                    return;
                }
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
            String sender = msgObj.getString("sender");
            PeerConnection peerConnection = getPeerConnectionByUserId(sender);
            if (peerConnection == null) {
                Log.e(TAG, "fatal error 未找到 sender 用户对应的 PeerConnection");
                return;
            }
            JSONObject candidateObj = msgObj.getJSONObject("candidate");
            String sdpMid = candidateObj.getString("sdpMid");
            int sdpMLineIndex = candidateObj.getInt("sdpMLineIndex");
            String sdp = candidateObj.getString("sdp");
            IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
            peerConnection.addIceCandidate(candidate);
        } else if ("joinRoom".equals(event)) {
            // 新用户加入事件
            String remoteUserId = msgObj.getString("userId");
            int pos = -1;
            for (int i = 0; i < videoItemList.size(); i++) {
                if (remoteUserId.equals(videoItemList.get(i).userId)) {
                    pos = i;
                }
            }
            if (pos == -1) {
                VideoItem videoItem = addNewVideoItem(remoteUserId);
                // 主动给新加入的用户发送 offer
                PeerConnection peerConnection = createPeerConnection(videoItem);
                videoItem.peerConnection = peerConnection;
                createOffer(peerConnection, videoItem.userId);
            }
        } else if ("leaveRoom".equals(event)) {
            // 离开房间事件
            String leaveUserId = msgObj.getString("userId");
            VideoItem videoItem = getVideoItemByUserId(leaveUserId);
            if (videoItem == null) {
                Log.e(TAG, "fatal error 未找到对应的离开用户");
                return;
            }
            // 关闭连接
            if (videoItem.surfaceViewRenderer != null) {
                videoItem.surfaceViewRenderer.release();
            }
            if (videoItem.videoTrack != null) {
                videoItem.videoTrack.dispose();
            }
            if (videoItem.peerConnection != null) {
                videoItem.peerConnection.close();
            }
            videoItemList.remove(videoItem);
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 根据远端用户userId 取对应的 PeerConnection
     *
     * @param userId
     */
    private PeerConnection getPeerConnectionByUserId(String userId) {
        if (userId == null) {
            return null;
        }
        for (VideoItem videoItem : videoItemList) {
            if (userId.equals(videoItem.userId)) {
                return videoItem.peerConnection;
            }
        }
        return null;
    }

    private VideoItem getVideoItemByUserId(String userId) {
        for (VideoItem videoItem : videoItemList) {
            if (userId.equals(videoItem.userId)) {
                return videoItem;
            }
        }
        return null;
    }

    class VideoItem {
        PeerConnection peerConnection;
        String userId;
        VideoTrack videoTrack;
        SurfaceViewRenderer surfaceViewRenderer;
    }

    VideoItem addNewVideoItem(String userId) {
        VideoItem videoItem = new VideoItem();
        videoItem.userId = userId;
        videoItemList.add(videoItem);
        adapter.notifyItemInserted(videoItemList.size() - 1);
        return videoItem;
    }
}