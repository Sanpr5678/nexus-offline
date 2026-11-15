package com.nexus.offline

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * Application-level mesh on top of Nearby Connections (P2P_CLUSTER).
 * Nearby chooses BLE / Bluetooth Classic / Wi-Fi (Direct, hotspot, LAN) per link; this class adds
 * node identity, pairing approval, topology gossip, next-hop routing, TTL, de-duplication,
 * retry queue, acks and end-to-end encryption.
 *
 * Threading: every method is expected to run on the main thread (Nearby callbacks and the bridge
 * both post there).
 */
class MeshEngine(context: Context) {

    companion object {
        const val SERVICE_ID = "com.nexus.offline.mesh"
        const val MAX_FILE = 1_048_576
        const val CHUNK = 8 * 1024
        const val TTL = 8
        const val MAX_ATTEMPTS = 40
        const val TICK_MS = 15_000L
        const val NODE_EXPIRY_MS = 100_000L
    }

    private class Node(val id: String) {
        var name: String = id
        var pub: String? = null
        var relay: Boolean = true
        var nbrs: Set<String> = emptySet()
        var seenAt: Long = 0
    }

    private class Pending(val nodeId: String, val name: String, val digits: String)
    private class FileRx(val n: Int, val name: String, val mime: String) {
        val parts = arrayOfNulls<ByteArray>(n)
        var got = 0
    }

    private class Out(var pkts: List<Packet>, val dst: String) {
        var attempts = 0
    }

    private class Lru(private val max: Int) {
        private val m = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > max
        }

        @Synchronized
        fun add(k: String): Boolean = m.put(k, true) == null

        @Synchronized
        fun has(k: String): Boolean = m.containsKey(k)
    }

    private val ctx = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(ctx)
    private val prefs = ctx.getSharedPreferences("nexus", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // ---- identity & settings ----
    val nodeId: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().replace("-", "").take(10).also { prefs.edit().putString("id", it).apply() }
    var name: String = prefs.getString("name", null) ?: ("Nexus-" + nodeId.take(4).uppercase())
        private set
    var relayEnabled: Boolean = prefs.getBoolean("relay", true)
        private set
    var autoTrust: Boolean = prefs.getBoolean("autotrust", true)
        private set
    private var pass: String = prefs.getString("pass", "nexus-open") ?: "nexus-open"
    private var groupKey: SecretKeySpec = Crypto.groupKey(pass)

    private val keyPair: KeyPair = run {
        val pub = prefs.getString("pub", null)
        val priv = prefs.getString("priv", null)
        var kp: KeyPair? = null
        if (pub != null && priv != null) {
            kp = try { KeyPair(Crypto.decodePub(pub), Crypto.decodePriv(priv)) } catch (e: Exception) { null }
        }
        kp ?: Crypto.newKeyPair().also {
            prefs.edit().putString("pub", Crypto.encodePub(it.public))
                .putString("priv", Crypto.encodePriv(it.private)).apply()
        }
    }
    private val myPub = Crypto.encodePub(keyPair.public)

    // ---- runtime state ----
    var running = false
        private set
    private val nodes = ConcurrentHashMap<String, Node>()
    private val links = ConcurrentHashMap<String, String>()          // endpointId -> nodeId
    private val pendingConn = ConcurrentHashMap<String, Pending>()   // endpointId -> awaiting approval
    private val discovered = ConcurrentHashMap<String, String>()     // endpointId -> nodeId
    private val requested = ConcurrentHashMap<String, Long>()        // endpointId -> last request time
    private val seen = Lru(3000)
    private val delivered = Lru(1000)
    private val doneFiles = Lru(200)
    private val rxFiles = HashMap<String, FileRx>()
    private val outbox = ConcurrentHashMap<String, Out>()
    private var tickCount = 0

    // ---- events to UI ----
    private var listener: ((JSONObject) -> Unit)? = null
    private val buffer = ArrayList<JSONObject>()

    fun attach(l: (JSONObject) -> Unit) {
        listener = l
        val copy = ArrayList(buffer)
        buffer.clear()
        copy.forEach(l)
    }

    fun detach() {
        listener = null
    }

    private fun emit(e: JSONObject) {
        val l = listener
        if (l != null) l(e) else {
            buffer.add(e)
            if (buffer.size > 800) buffer.removeAt(0)
        }
    }

    private fun emitStatus(id: String, st: String) = emit(JSONObject().put("t", "status").put("id", id).put("st", st))
    private fun emitError(m: String) = emit(JSONObject().put("t", "error").put("m", m))

    fun emitState() {
        if (listener == null) return
        emit(JSONObject().put("t", "state").put("s", stateJson()))
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    private fun now() = System.currentTimeMillis()
    private fun nameOf(id: String) = nodes[id]?.name ?: id

    // ---- prefs sets ----
    private fun getSet(k: String): MutableSet<String> = HashSet(prefs.getStringSet(k, emptySet()) ?: emptySet())
    private fun putSet(k: String, s: Set<String>) = prefs.edit().putStringSet(k, HashSet(s)).apply()
    private fun isBlocked(id: String) = getSet("blocked").contains(id)
    private fun isTrusted(id: String) = getSet("trusted").contains(id)
    private fun isVerified(id: String) = getSet("verified").contains(id)

    // ============ lifecycle ============

    fun start() {
        if (running) return
        running = true
        advertise()
        discover()
        handler.postDelayed(tick, TICK_MS)
        emitState()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        links.clear(); pendingConn.clear(); discovered.clear(); requested.clear(); nodes.clear()
        emitState()
    }

    fun rescan() {
        if (!running) return
        client.stopDiscovery()
        discovered.clear(); requested.clear()
        discover()
        emitState()
    }

    private fun advertise() {
        val o = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising("$nodeId|$name", SERVICE_ID, connCb, o).addOnFailureListener {
            if (!(it is ApiException && it.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING))
                emitError("Advertising failed: ${it.message}")
        }
    }

    private fun discover() {
        val o = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, discCb, o).addOnFailureListener {
            if (!(it is ApiException && it.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING))
                emitError("Discovery failed: ${it.message}")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                housekeeping()
            } catch (e: Exception) {
                emitError("Maintenance error: ${e.message}")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun housekeeping() {
        tickCount++
        for ((ep, id) in discovered) tryConnect(ep, id)
        val limit = now() - NODE_EXPIRY_MS
        for (n in nodes.values.toList()) if (n.seenAt in 1 until limit && !links.containsValue(n.id)) nodes.remove(n.id)
        if (tickCount % 2 == 0) broadcastHello()
        retryOutbox()
        emitState()
    }

    // ============ discovery & pairing ============

    private fun parseName(s: String): Pair<String, String>? {
        val i = s.indexOf('|')
        if (i <= 0) return null
        return s.substring(0, i) to s.substring(i + 1)
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(ep: String, info: DiscoveredEndpointInfo) {
            val (id, _) = parseName(info.endpointName) ?: return
            if (id == nodeId || isBlocked(id)) return
            discovered[ep] = id
            tryConnect(ep, id)
            emitState()
        }

        override fun onEndpointLost(ep: String) {
            discovered.remove(ep)
            requested.remove(ep)
        }
    }

    /** Only the node with the lower id initiates, to avoid simultaneous cross-requests. */
    private fun tryConnect(ep: String, id: String) {
        if (nodeId >= id) return
        if (isBlocked(id) || links.containsValue(id) || pendingConn.containsKey(ep)) return
        val last = requested[ep]
        if (last != null && now() - last < 40_000) return
        requested[ep] = now()
        client.requestConnection("$nodeId|$name", ep, connCb).addOnFailureListener { requested.remove(ep) }
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(ep: String, info: ConnectionInfo) {
            val p = parseName(info.endpointName)
            if (p == null || isBlocked(p.first)) {
                client.rejectConnection(ep)
                return
            }
            pendingConn[ep] = Pending(p.first, p.second, info.authenticationDigits)
            if (autoTrust && isTrusted(p.first)) {
                client.acceptConnection(ep, payCb)
            }
            emitState()
        }

        override fun onConnectionResult(ep: String, r: ConnectionResolution) {
            val pend = pendingConn.remove(ep)
            requested.remove(ep)
            if (r.status.isSuccess && pend != null) {
                links[ep] = pend.nodeId
                val n = nodes.getOrPut(pend.nodeId) { Node(pend.nodeId) }
                n.name = pend.name
                n.seenAt = now()
                send(ep, helloPacket())
                broadcastHello()
                retryOutbox()
            }
            emitState()
        }

        override fun onDisconnected(ep: String) {
            val id = links.remove(ep)
            pendingConn.remove(ep)
            if (id != null) emit(JSONObject().put("t", "lost").put("id", id))
            broadcastHello()
            emitState()
        }
    }

    fun approve(ep: String, ok: Boolean) {
        val p = pendingConn[ep] ?: return
        if (ok) {
            putSet("trusted", getSet("trusted").apply { add(p.nodeId) })
            client.acceptConnection(ep, payCb)
        } else {
            pendingConn.remove(ep)
            client.rejectConnection(ep)
        }
        emitState()
    }

    fun block(id: String) {
        putSet("blocked", getSet("blocked").apply { add(id) })
        putSet("trusted", getSet("trusted").apply { remove(id) })
        links.entries.filter { it.value == id }.forEach { client.disconnectFromEndpoint(it.key) }
        pendingConn.entries.filter { it.value.nodeId == id }.forEach { client.rejectConnection(it.key); pendingConn.remove(it.key) }
        nodes.remove(id)
        emitState()
    }

    fun unblock(id: String) {
        putSet("blocked", getSet("blocked").apply { remove(id) })
        emitState()
    }

    fun setVerified(id: String, ok: Boolean) {
        putSet("verified", getSet("verified").apply { if (ok) add(id) else remove(id) })
        emitState()
    }

    fun clearTrusted() {
        putSet("trusted", emptySet())
        emitState()
    }

    // ============ settings ============

    fun setName(n: String) {
        val clean = n.trim().replace("|", "/").take(24)
        if (clean.isEmpty()) return
        name = clean
        prefs.edit().putString("name", clean).apply()
        if (running) {
            client.stopAdvertising()
            advertise()
            broadcastHello()
        }
        emitState()
    }

    fun setRelay(on: Boolean) {
        relayEnabled = on
        prefs.edit().putBoolean("relay", on).apply()
        broadcastHello()
        emitState()
    }

    fun setAutoTrust(on: Boolean) {
        autoTrust = on
        prefs.edit().putBoolean("autotrust", on).apply()
        emitState()
    }

    fun setPass(p: String) {
        if (p.length < 4) {
            emitError("Passphrase must be at least 4 characters.")
            return
        }
        pass = p
        prefs.edit().putString("pass", p).apply()
        groupKey = Crypto.groupKey(p)
        emitState()
    }

    // ============ routing ============

    private fun helloPacket(): Packet {
        val b = JSONObject().put("name", name).put("pub", myPub).put("relay", relayEnabled)
            .put("nbrs", JSONArray(links.values.toList()))
        return Packet(newId(), nodeId, BROADCAST, "hello", 6, now(), b.toString())
    }

    private fun broadcastHello() {
        if (links.isEmpty()) return
        transmit(listOf(helloPacket()), BROADCAST)
    }

    private fun adjacency(): Map<String, MutableSet<String>> {
        val g = HashMap<String, MutableSet<String>>()
        fun edge(a: String, b: String) {
            g.getOrPut(a) { HashSet() }.add(b)
            g.getOrPut(b) { HashSet() }.add(a)
        }
        for (id in links.values) edge(nodeId, id)            // my own edges come only from live links
        for (n in nodes.values) for (m in n.nbrs) if (m != nodeId && n.id != nodeId) edge(n.id, m)
        return g
    }

    /** BFS from this node. Non-relay nodes can be reached but not traversed. */
    private fun bfs(): Pair<Map<String, Int>, Map<String, String>> {
        val g = adjacency()
        val dist = HashMap<String, Int>()
        val parent = HashMap<String, String>()
        val q = ArrayDeque<String>()
        dist[nodeId] = 0
        q.add(nodeId)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u != nodeId && nodes[u]?.relay == false) continue
            for (v in g[u] ?: emptySet<String>()) {
                if (!dist.containsKey(v)) {
                    dist[v] = dist[u]!! + 1
                    parent[v] = u
                    q.add(v)
                }
            }
        }
        return dist to parent
    }

    private fun nextHop(dst: String): String? {
        val (dist, parent) = bfs()
        if (!dist.containsKey(dst)) return null
        var cur = dst
        while (parent[cur] != nodeId) cur = parent[cur] ?: return null
        return cur
    }

    private fun epOf(id: String): String? = links.entries.firstOrNull { it.value == id }?.key

    private fun send(ep: String, p: Packet) {
        client.sendPayload(ep, Payload.fromBytes(p.toBytes()))
            .addOnFailureListener { emitError("Send failed: ${it.message}") }
    }

    /** Sends own packets: next hop if a route is known, otherwise flood to all neighbours. */
    private fun transmit(pkts: List<Packet>, dst: String): Boolean {
        if (links.isEmpty()) return false
        val hopEp = if (dst == BROADCAST) null else nextHop(dst)?.let { epOf(it) }
        for (p in pkts) {
            seen.add("${p.id}#${p.a}")
            if (hopEp != null) send(hopEp, p) else links.keys.forEach { send(it, p) }
        }
        return true
    }

    private fun forward(p: Packet, exclude: String) {
        if (p.dst == BROADCAST || p.type == "hello") {
            links.keys.filter { it != exclude }.forEach { send(it, p) }
            return
        }
        val hop = nextHop(p.dst)?.let { epOf(it) }
        if (hop != null && hop != exclude) send(hop, p)
        else links.keys.filter { it != exclude }.forEach { send(it, p) }
    }

    // ============ receive ============

    private val payCb = object : PayloadCallback() {
        override fun onPayloadReceived(ep: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val p = Packet.parse(bytes) ?: return
            try {
                onPacket(ep, p)
            } catch (e: Exception) {
                emitError("Bad packet: ${e.message}")
            }
        }

        override fun onPayloadTransferUpdate(ep: String, u: PayloadTransferUpdate) {}
    }

    private fun onPacket(ep: String, p: Packet) {
        if (!links.containsKey(ep)) return
        if (isBlocked(p.src) || p.src == nodeId) return
        if (!seen.add("${p.id}#${p.a}")) return

        val forMe = p.dst == nodeId || p.dst == BROADCAST
        if (forMe) handleLocal(p)
        if (p.dst != nodeId && p.ttl > 1 && relayEnabled) forward(p.copy(ttl = p.ttl - 1), ep)
    }

    private fun keyFor(p: Packet): SecretKeySpec? {
        if (p.dst == BROADCAST) return groupKey
        val pub = nodes[p.src]?.pub ?: return null
        return Crypto.pairKey(keyPair.private, pub)
    }

    private fun handleLocal(p: Packet) {
        when (p.type) {
            "hello" -> handleHello(p)
            "ack" -> {
                if (outbox.remove(p.body) != null) emitStatus(p.body, "delivered")
            }
            "chat" -> {
                if (p.dst == nodeId && delivered.has(p.id)) { sendAck(p.src, p.id); return }
                val key = keyFor(p) ?: return
                val plain = Crypto.decrypt(key, p.body)
                if (plain == null) {
                    if (p.dst == nodeId) emitError("Could not decrypt a message from ${nameOf(p.src)}.")
                    return
                }
                val text = JSONObject(String(plain, Charsets.UTF_8)).getString("text")
                if (p.dst == nodeId) { delivered.add(p.id); sendAck(p.src, p.id) }
                emit(JSONObject().put("t", "msg").put("id", p.id).put("from", p.src).put("fromName", nameOf(p.src))
                    .put("dst", if (p.dst == BROADCAST) "*" else "me").put("text", text).put("ts", p.ts))
            }
            "file" -> handleFile(p)
        }
    }

    private fun handleHello(p: Packet) {
        val j = JSONObject(p.body)
        val pub = j.getString("pub")
        val pinned = prefs.getString("pin_${p.src}", null)
        if (pinned != null && pinned != pub) {
            emitError("Identity key of ${j.optString("name", p.src)} changed. Packet ignored.")
            return
        }
        if (pinned == null) prefs.edit().putString("pin_${p.src}", pub).apply()
        val n = nodes.getOrPut(p.src) { Node(p.src) }
        n.name = j.optString("name", p.src)
        n.pub = pub
        n.relay = j.optBoolean("relay", true)
        val arr = j.optJSONArray("nbrs")
        n.nbrs = if (arr == null) emptySet() else (0 until arr.length()).map { arr.getString(it) }.toSet()
        n.seenAt = now()
        emitState()
    }

    private fun handleFile(p: Packet) {
        val key = keyFor(p) ?: return
        val plain = Crypto.decrypt(key, p.body) ?: return
        val j = JSONObject(String(plain, Charsets.UTF_8))
        val fid = j.getString("fid")
        val n = j.getInt("n")
        val i = j.getInt("i")
        if (doneFiles.has(fid)) { if (p.dst == nodeId) sendAck(p.src, fid); return }
        if (n < 1 || n > MAX_FILE / CHUNK + 2 || i < 0 || i >= n) return
        val rx = rxFiles.getOrPut(fid) { FileRx(n, j.getString("name"), j.getString("mime")) }
        if (rx.n != n) return
        if (rx.parts[i] == null) {
            rx.parts[i] = Base64.decode(j.getString("d"), Base64.NO_WRAP)
            rx.got++
        }
        if (rx.got == rx.n) {
            rxFiles.remove(fid)
            doneFiles.add(fid)
            val out = ByteArrayOutputStream()
            rx.parts.forEach { out.write(it!!) }
            if (p.dst == nodeId) sendAck(p.src, fid)
            emit(JSONObject().put("t", "file").put("id", fid).put("from", p.src).put("fromName", nameOf(p.src))
                .put("dst", if (p.dst == BROADCAST) "*" else "me").put("name", rx.name).put("mime", rx.mime)
                .put("size", out.size()).put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                .put("ts", p.ts))
        }
    }

    private fun sendAck(dst: String, key: String) {
        transmit(listOf(Packet(newId(), nodeId, dst, "ack", TTL, now(), key)), dst)
    }

    // ============ send ============

    private fun keyForDst(dst: String): SecretKeySpec? {
        if (dst == BROADCAST) return groupKey
        val pub = nodes[dst]?.pub ?: return null
        return Crypto.pairKey(keyPair.private, pub)
    }

    private fun dispatchOwn(key: String, pkts: List<Packet>, dst: String) {
        val ok = transmit(pkts, dst)
        if (!(ok && dst == BROADCAST)) outbox[key] = Out(pkts, dst)
        emitStatus(key, if (ok) "sent" else "queued")
    }

    fun sendText(dst: String, text: String, id: String) {
        val key = keyForDst(dst)
        if (key == null) { emitError("No key for that device yet. Wait until it is reachable."); emitStatus(id, "failed"); return }
        val body = Crypto.encrypt(key, JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8))
        dispatchOwn(id, listOf(Packet(id, nodeId, dst, "chat", TTL, now(), body)), dst)
    }

    fun sendFile(dst: String, name: String, mime: String, data: ByteArray, fid: String) {
        if (data.size > MAX_FILE) { emitError("File larger than 1 MB."); emitStatus(fid, "failed"); return }
        val key = keyForDst(dst)
        if (key == null) { emitError("No key for that device yet."); emitStatus(fid, "failed"); return }
        val n = maxOf(1, (data.size + CHUNK - 1) / CHUNK)
        val ts = now()
        val pkts = (0 until n).map { i ->
            val slice = data.copyOfRange(i * CHUNK, minOf(data.size, (i + 1) * CHUNK))
            val j = JSONObject().put("fid", fid).put("name", name).put("mime", mime)
                .put("i", i).put("n", n).put("d", Base64.encodeToString(slice, Base64.NO_WRAP))
            Packet("$fid-$i", nodeId, dst, "file", TTL, ts, Crypto.encrypt(key, j.toString().toByteArray(Charsets.UTF_8)))
        }
        dispatchOwn(fid, pkts, dst)
    }

    /** Offline queue: messages without a route/ack are retried on each tick and whenever a link comes up. */
    private fun retryOutbox() {
        if (links.isEmpty()) return
        for ((key, o) in outbox.entries.toList()) {
            if (o.attempts >= MAX_ATTEMPTS) { outbox.remove(key); emitStatus(key, "failed"); continue }
            o.attempts++
            o.pkts = o.pkts.map { it.copy(a = o.attempts) }
            if (transmit(o.pkts, o.dst)) {
                emitStatus(key, "sent")
                if (o.dst == BROADCAST) outbox.remove(key)
            }
        }
    }

    // ============ state for UI ============

    private fun radioBt(): Boolean = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    } catch (e: SecurityException) { false }

    private fun radioWifi(): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { false }

    private fun stateJson(): JSONObject {
        val (dist, _) = bfs()
        val s = JSONObject()
        s.put("me", JSONObject().put("id", nodeId).put("name", name).put("fp", Crypto.fingerprint(myPub)))
        s.put("relay", relayEnabled).put("autoTrust", autoTrust).put("active", running)
        s.put("bt", radioBt()).put("wifi", radioWifi()).put("scanning", running)
        val la = JSONArray()
        for ((ep, id) in links) {
            val n = nodes[id]
            la.put(JSONObject().put("id", id).put("ep", ep).put("name", n?.name ?: id)
                .put("fp", n?.pub?.let { Crypto.fingerprint(it) } ?: "").put("verified", isVerified(id)))
        }
        s.put("links", la)
        val na = JSONArray()
        for (n in nodes.values) {
            na.put(JSONObject().put("id", n.id).put("name", n.name).put("hops", dist[n.id] ?: -1)
                .put("relay", n.relay).put("fp", n.pub?.let { Crypto.fingerprint(it) } ?: "")
                .put("verified", isVerified(n.id)))
        }
        s.put("nodes", na)
        val ea = JSONArray()
        val g = adjacency()
        for ((a, set) in g) for (b in set) if (a < b) ea.put(JSONArray().put(a).put(b))
        s.put("edges", ea)
        val pa = JSONArray()
        for ((ep, p) in pendingConn) pa.put(JSONObject().put("ep", ep).put("id", p.nodeId).put("name", p.name).put("digits", p.digits))
        s.put("pending", pa)
        s.put("blocked", JSONArray(getSet("blocked").toList()))
        s.put("queued", outbox.size)
        return s
    }
}
