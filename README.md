# RTCMultiConnection Android Demo

This demo is built on top of Android Studio project for AppRTCDemo of WebRTC project. The original AppRTCDemo revision number is 15663.
https://chromium.googlesource.com/external/webrtc/+/b4ad603b47f269626585cca717cc53c7e944a8c4

Instead of WebSocket Java library, this demo uses socket.io Java library to implement AppRTCClient. The URL and ICE servers are hardcoded and it currently only supports one-to-one-demo.

