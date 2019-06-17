package org.appspot.apprtc;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SocketIORTCClient implements AppRTCClient {
    private static final String TAG = "SocketIORTCClient";
    private static final String ALPHA_NUMERIC_STRING = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static int USER_ID_LENGTH = 11;
    private static LinkedList<PeerConnection.IceServer> ICE_SERVERS; // Hardcoded ICE servers, needs to get from signaling server
    private static final String MESSAGE_EVENT = "one-to-one-demo"; // Hardcoded channel type
    private static final String SOCKETIO_SERVER_URL = "https://wip.remocare.net";
    private Socket mSocket;
    private final Handler handler;
    private final String mRoomId;
    private boolean isInitiator = false;
    private SignalingEvents events;
    private String mUserId;
    private String mOtherUserId; // Assume the room only allows two participants
    private SessionDescription mSdp;
    private LinkedList<IceCandidate> mIceCandidates = new LinkedList<IceCandidate>();
    public static String randomAlphaNumeric(int count) {
        StringBuilder builder = new StringBuilder();
        while (count-- != 0) {
            int character = (int)(Math.random()*ALPHA_NUMERIC_STRING.length());
            builder.append(ALPHA_NUMERIC_STRING.charAt(character));
        }
        return builder.toString();
    }
    public SocketIORTCClient(String roomId, SignalingEvents events) {
        final HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mRoomId = roomId;
        mUserId = randomAlphaNumeric(USER_ID_LENGTH);
        //String url = SOCKETIO_SERVER_URL + "/?msgEvent=" + MESSAGE_EVENT + "&userid=" + mUserId;
        String url = "https://wip.remocare.net/?msgEvent=" + MESSAGE_EVENT + "&userid=" + mUserId;
        this.events = events;
        if (ICE_SERVERS == null) {
            ICE_SERVERS = new LinkedList<>();
            ICE_SERVERS.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302", "", ""));
            ICE_SERVERS.add(new PeerConnection.IceServer("stun:stun1.l.google.com:19302", "", ""));
            ICE_SERVERS.add(new PeerConnection.IceServer("stun:stun2.l.google.com:19302", "", ""));
            ICE_SERVERS.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302?transport=udp", "", ""));
        }
        handler = new Handler(handlerThread.getLooper());
        {
            try {
                mSocket = IO.socket(url);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
    }
    private void checkPresence() {
        mSocket.emit("check-presence", mRoomId, new Ack() {
            public void call(final Object... args) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (args instanceof Object[] && args.length > 0) {
                            boolean presence = (boolean)args[0];
                            JSONObject data = (JSONObject)args[2];
                            isInitiator = !presence;
                            if (presence) {
                                joinRoom();
                            } else {
                                openRoom();
                            }
                            Log.d(TAG, presence + " " + (String)args[1] + data.toString());
                        }
                    }
                });
            }
        });
    }
    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.d(TAG, "onConnect" + args.length);
            checkPresence();
        }
    };
    private Emitter.Listener onExtraDataUpdated = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            String argsStr = "onExtraDataUpdated ";
            for (int i=0; i<args.length; i++) {
                argsStr += args[i] + " ";
                Log.d(TAG, "args.length = " + args.length + "args[0] = " + args[0]);
            }
            Log.d(TAG, argsStr);
        }
    };
    private Emitter.Listener onUserConnected = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (args.length > 0) {
                mOtherUserId = (String)args[0];
                Log.d(TAG, "args.length = " + args.length + "args[0] = " + args[0]);
            }
        }
    };
    private Emitter.Listener onSocketMessageEvent = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "args.length = " + args.length + " args[0] = " + args[0]);
                    JSONObject msg = (JSONObject) args[0];
                    try {
                        Log.d(TAG, "mUserId = " + mUserId);
                        // ignore messages sent by myself
                        if (!msg.getString("remoteUserId").equals((mUserId))) return;

                        JSONObject message = (JSONObject) msg.get("message");
                        if (message.optBoolean("enableMedia", false)) {
                            // need to send "readyForOffer"
                            sendReadyForOffer(message.getJSONObject("userPreferences"));
                            return;
                        }
                        if ("offer".equals(message.optString("type"))) {
                            String sdp = message.getString("sdp");
                            notifyConnectedToRoom(false, sdp);
                            return;
                        }
                        if ("answer".equals(message.optString("type"))) {
                            String sdp = message.getString("sdp");
                            events.onRemoteDescription(new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                            return;
                        }
                        String candidate = message.optString("candidate");
                        if (candidate.length() > 0) {
                            String sdpMid = message.getString("sdpMid");
                            int sdpMLineIndex = message.getInt("sdpMLineIndex");
                            notifyRemoteCandidate(sdpMid, sdpMLineIndex, candidate);
                            return;
                        }
                        if (message.optBoolean("newParticipationRequest")) {
                            mOtherUserId = msg.optString("sender");
                            sendEnableMedia(message);
                            return;
                        }
                        if (message.optBoolean("readyForOffer")) {
                            // Assume that we have sdp and candidates from PeerConnectionClient already
                            sendOffer();
                        }
                    } catch (JSONException jse) {
                        jse.printStackTrace();
                    }
                }
            });
        }
    };

    // utility functions
    private void sendEventMessage(JSONObject data) {
        sendEventMessage(data, mOtherUserId);
    }
    private void sendEventMessage(JSONObject data, String remoteUserId) {
        try {
            JSONObject msgToSend = new JSONObject();
            msgToSend.put("remoteUserId", remoteUserId);
            msgToSend.put("sender", mUserId);
            msgToSend.put("message", data);
            Log.d(TAG, "sendEventMessage " + data.toString());
            mSocket.emit(MESSAGE_EVENT, msgToSend);
        } catch (JSONException jse) {
            Log.d(TAG, jse.toString());
        }
    }

    private void sendOffer() {
        Log.d(TAG, "sendOfferSdp " + mSdp.type);
        try {
            JSONObject msg = new JSONObject();
            msg.put("sdp", mSdp.description);
            msg.put("type", "offer");
            sendEventMessage(msg);
            for (int i=0; i< mIceCandidates.size(); i++) {
                IceCandidate candidate = mIceCandidates.get(i);
                JSONObject iceMsg = new JSONObject();
                iceMsg.put("candidate", candidate.sdp);
                iceMsg.put("sdpMid", candidate.sdpMid);
                iceMsg.put("sdpMLineIndex", candidate.sdpMLineIndex);
                sendEventMessage(iceMsg);
            }
        } catch (JSONException jse) {
            jse.printStackTrace();
        }
    }

    private void sendReadyForOffer(JSONObject msgToAttach) {
        try {
            msgToAttach.put("streamsToShare", new JSONObject());
            JSONObject readyForOffer = new JSONObject();
            readyForOffer.put("readyForOffer", true);
            readyForOffer.put("userPreferences", msgToAttach);
            sendEventMessage(readyForOffer);
        } catch (JSONException jse) {
            Log.d(TAG, jse.toString());
        }
    }

    private void sendEnableMedia(JSONObject msgToAttach) {
        try {
            JSONObject msg = new JSONObject();
            //msg.put("message", msgToAttach);
            msg.put("enableMedia", true);
            JSONObject userPref = new JSONObject();
            userPref.put("localPeerSdpConstraints", new JSONObject("{\"OfferToReceiveAudio\":true,\"OfferToReceiveVideo\":true}"));
            userPref.put("remotePeerSdpConstraints", new JSONObject("{\"OfferToReceiveAudio\":true,\"OfferToReceiveVideo\":true}"));
            userPref.put("isOneWay", false);
            userPref.put("isDataOnly", false);
            userPref.put("dontGetRemoteStream", false);
            userPref.put("dontAttachLocalStream", false);
            JSONObject connDesc = new JSONObject();
            connDesc.put("remoteUserId", mUserId);
            connDesc.put("message", msgToAttach);
            connDesc.put("sender", mOtherUserId);
            userPref.put("connectionDescription", connDesc);
            msg.put("userPreferences", userPref);
            sendEventMessage(msg);
        } catch (JSONException jse) {
            Log.d(TAG, jse.toString());
        }

    }

    private void joinRoom() {
        JSONObject data = createOpenOrJoinJson();
        mSocket.emit("join-room", data, new Ack() {
            public void call(final Object... args) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (args instanceof Object[] && args.length > 0) {
                            boolean roomJoined = (boolean)args[0];
                            //String roomId = (String)args[1];
                            if (roomJoined) {
                                Log.d(TAG, Boolean.toString(roomJoined));
                                //notifyConnectedToRoom(false);
                                sendNewParticipationRequest();
                            }
                        }
                    }
                });
            }
        });
    }

    private void sendNewParticipationRequest() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("newParticipationRequest", true);
            msg.put("isOneWay", false);
            msg.put("isDataOnly", false);
            msg.put("localPeerSdpConstraints", new JSONObject("{\"OfferToReceiveAudio\":true,\"OfferToReceiveVideo\":true}"));
            msg.put("remotePeerSdpConstraints", new JSONObject("{\"OfferToReceiveAudio\":true,\"OfferToReceiveVideo\":true}"));
            sendEventMessage(msg, mRoomId);
        } catch (JSONException jse) {
            Log.d(TAG, jse.toString());
        }
    }
    private JSONObject createOpenOrJoinJson() {
        try {
            JSONObject data = new JSONObject();
            data.put("sessionid", mRoomId);
            JSONObject session = new JSONObject();
            session.put("audio", true);
            session.put("video", true);
            data.put("session", session);
            JSONObject mediaConstraints = new JSONObject();
            JSONObject audio = new JSONObject();
            JSONObject audioMandatory = new JSONObject();
            audio.put("mandatory", audioMandatory);
            audio.put("optional", new JSONArray());
            mediaConstraints.put("audio", audio);
            JSONObject video = new JSONObject();
            JSONObject videoMandatory = new JSONObject();
            JSONArray videoMandatoryOptional = new JSONArray();
            videoMandatoryOptional.put(new JSONObject("{\"facingMode\": \"user\"}"));
            videoMandatory.put("mandatory", new JSONObject());
            videoMandatory.put("optional", videoMandatoryOptional);
            mediaConstraints.put("video", videoMandatory);
            data.put("mediaConstraints", mediaConstraints);
            JSONObject sdpConstraints = new JSONObject("{\"mandatory\":{\"OfferToReceiveAudio\":true,\"OfferToReceiveVideo\":true},\"optional\":[{\"VoiceActivityDetection\":false}]}");
            data.put("sdpConstraints", sdpConstraints);
            data.put("streams", new JSONArray());
            data.put("extra", new JSONObject());
            data.put("identifier", new JSONObject());
            //data.put("password", new JSONObject());
            return data;
        } catch (JSONException jse) {
            Log.d(TAG, jse.toString());
            return null;
        }
    }
    private void openRoom() {
        JSONObject data = createOpenOrJoinJson();
        mSocket.emit("open-room", data, new Ack() {
            public void call(final Object... args) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (args instanceof Object[] && args.length > 0) {
                            boolean roomOpened = (boolean)args[0];
                            //String roomId = (String)args[1];
                            if (roomOpened) {
                                Log.d(TAG, Boolean.toString(roomOpened));
                                // TODO: implement this
                                notifyConnectedToRoom(true, null);
                            }
                        }
                    }
                });
            }
        });

    }
    private void notifyRemoteCandidate(String sdpMid, int sdpMLineIndex, String sdp) {
        IceCandidate can = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
        events.onRemoteIceCandidate(can);
    }

    private void notifyConnectedToRoom(boolean initiator, String sdp) {
        String clientId = "0";
        String wssUrl = "https://wip.remocare.net";
        String wssPostUrl = "";
        SessionDescription offerSdp = initiator ? null : new SessionDescription(SessionDescription.Type.OFFER, sdp);
        List<IceCandidate> iceCandidates = null;
        SignalingParameters params = new SignalingParameters(
                ICE_SERVERS, initiator, clientId, wssUrl, wssPostUrl, offerSdp, iceCandidates);
        events.onConnectedToRoom(params);
    }

    private void disconnectFromRoomInternal() {
        Log.d(TAG, "Leaving room.");
        mSocket.disconnect();
    }
    /**
     * Asynchronously connect to an AppRTC room URL using supplied connection
     * parameters. Once connection is established onConnectedToRoom()
     * callback with room parameters is invoked.
     */
    public void connectToRoom(RoomConnectionParameters connectionParameters) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!mSocket.connected()) {
                    mSocket.on("connect", onConnect);
                    mSocket.on(MESSAGE_EVENT, onSocketMessageEvent);
                    mSocket.on("extra-data-updated", onExtraDataUpdated);
                    mSocket.on("user-connected", onUserConnected);
                    mSocket.connect();
                } else {
                    checkPresence();
                }
            }
        });
    }

    /**
     * Send offer SDP to the other participant.
     */
    public void sendOfferSdp(final SessionDescription sdp) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                // Save sdp for notification later
                mSdp = sdp;
            }
        });
    }

    /**
     * Send answer SDP to the other participant.
     */
    public void sendAnswerSdp(final SessionDescription sdp) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "sendAnswerSdp " + sdp.type);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("sdp", sdp.description);
                    msg.put("type", "answer");
                    sendEventMessage(msg);
                } catch (JSONException jse) {
                    Log.d(TAG, jse.toString());
                }
            }
        });
    }

    /**
     * Send Ice candidate to the other participant.
     */
    public void sendLocalIceCandidate(final IceCandidate candidate) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "sendLocalIceCandidate");
                if (isInitiator) {
                    mIceCandidates.add(candidate);
                    return;
                }
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("candidate", candidate.sdp);
                    msg.put("sdpMid", candidate.sdpMid);
                    msg.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    sendEventMessage(msg);
                } catch (JSONException jse) {
                    jse.printStackTrace();
                }
            }
        });
    }

    /**
     * Send removed ICE candidates to the other participant.
     */
    public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {

    }

    /**
     * Disconnect from room.
     */
    public void disconnectFromRoom() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                disconnectFromRoomInternal();
                handler.getLooper().quit();
            }
        });
    }
}
