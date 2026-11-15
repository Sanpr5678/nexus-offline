# NEXUS Offline

Native Android (Kotlin) app with your HTML UI in a WebView, wired to a real mesh engine.

## Open and run
1. Android Studio > Open > select this `NexusOffline` folder. Let Gradle sync (needs internet once).
2. Install on two or more real phones (Android 8.0+). Emulators do not support Bluetooth/Wi-Fi discovery.
3. Open the app on each phone, grant the permissions, keep Bluetooth, Wi-Fi and Location (Android 11 and below) on.
4. A connection request with a 4-digit code appears on both phones. Compare the codes, then Accept.

## What is real
- Discovery and links: Google Nearby Connections (P2P_CLUSTER). It picks BLE, Bluetooth Classic or Wi-Fi per link. Needs Google Play services on the phone.
- Mesh layer (`MeshEngine.kt`): topology gossip, BFS next-hop routing, TTL (8), duplicate suppression, reroute on disconnect, offline retry queue, delivery acks, relay on/off.
- Security: ECDH P-256 identity per device, AES-GCM end-to-end for private chats, group passphrase key for "Everyone", pairing approval, block list, key pinning (changed key is rejected), fingerprints you can mark as verified.
- Chat: private, group, text, timestamps, status (sending/queued/sent/delivered), files and images up to 1 MB.

## Not done yet
- Voice calling (Phase 5). The Calls tab says so; there are no fake controls.
- Direct control of Wi-Fi Direct / Wi-Fi Aware and raw BLE/Classic sockets. Nearby handles those. A custom transport would be a later phase.
- Group chat is a single broadcast channel (Everyone), not named groups.
- Untested on hardware. Test each feature on at least 2 phones, and multi-hop on 3 (A and C out of direct range, B between).

## Known limits
- Android 8.0 - 11 needs Location permission for scanning; 12+ uses Nearby devices permission.
- The WebView is not hardened beyond local assets; no network access is used.
