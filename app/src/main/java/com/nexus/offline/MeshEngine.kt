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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * Application-level mesh on top of Nearby Connections (P2P_CLUSTER).
 * Nearby chooses BLE / Bluetooth Classic / Wi-Fi per link; this class adds node identity (8 character
 * ID code), pairing approval, topology gossip, next-hop routing, TTL, de-duplication, retry queue,
 * acks, end-to-end encryption, groups (public / private) and voice calls.
 *
 * Threading: every method runs on the main thread.
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
        const val ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        val AUDIO_MAGIC: Byte = 0xAD.toByte()
    }

    private class Node(val id: String) {
        var name: String = id
        var pub: String? = null
        var relay: Boolean = true
        var nbrs: Set<String> = emptySet()
        var pubGroups: List<Pair<String, String>> = emptyList()
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

    private class Group(val gid: String, var name: String, val type: String, val owner: String, val key: ByteArray)
    private class Invite(
        val gid: String, val name: String, val type: String, val owner: String,
        val from: String, val key: ByteArray
    )

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
    val nodeId: String = prefs.getString("id", null)?.takeIf { it.length == 8 }
        ?: genId().also { prefs.edit().putString("id", it).apply() }
    var name: String = prefs.getString("name", null) ?: ("Nexus-" + nodeId.take(4).uppercase())
        private set
    var relayEnabled: Boolean = prefs.getBoolean("relay", true)
        private set
    var autoTrust: Boolean = prefs.getBoolean("autotrust", true)
        private set
    private var pass: String = prefs.getString("pass", "nexus-open") ?: "nexus-open"
    private var netKey: SecretKeySpec = Crypto.groupKey(pass)

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
    private val deferred = HashMap<String, MutableList<() -> Unit>>() // sends waiting for a device's key
    private val groups = LinkedHashMap<String, Group>()
    private val invites = LinkedHashMap<String, Invite>()
    private var tickCount = 0

    // ---- call state ----
    private var callState = "idle"            // idle | out | in | active
    private var callPeer = ""
    private var callId = ""
    private var callKey: SecretKeySpec? = null
    private var callRoute: String? = null
    private var callMuted = false
    private var callSpeakerOn = false
    private var callStartedAt = 0L
    private var voice: VoiceCall? = null
    private var txSeq = 0
    private var rxLastSeq = -1
    private var rxCount = 0
    private var rxExpected = 0
    private var lastRxAt = 0L
    var callHook: ((Boolean) -> Unit)? = null
    var ringHook: ((String?) -> Unit)? = null

    // ---- events to UI ----
    private var listener: ((JSONObject) -> Unit)? = null
    private val buffer = ArrayList<JSONObject>()

    init {
        loadGroups()
    }

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

    private fun genId(): String {
        val r = SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(ID_ALPHABET[r.nextInt(ID_ALPHABET.length)])
        return sb.toString()
    }

    fun newGid(): String = genId()
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    private fun now() = System.currentTimeMillis()
    private fun nameOf(id: String) = nodes[id]?.name ?: id
    private fun isFlood(dst: String) = dst == BROADCAST || dst.startsWith("g:")
    private fun normalizeId(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun isValidId(s: String) = s.length == 8 && s.all { ID_ALPHABET.indexOf(it) >= 0 }

    // ---- prefs sets ----
    private fun getSet(k: String): MutableSet<String> = HashSet(prefs.getStringSet(k, emptySet()) ?: emptySet())
    private fun putSet(k: String, s: Set<String>) = prefs.edit().putStringSet(k, HashSet(s)).apply()
    private fun isBlocked(id: String) = getSet("blocked").contains(id)
    private fun isTrusted(id: String) = getSet("trusted").contains(id)
    private fun isVerified(id: String) = getSet("verified").contains(id)

    // ---- keys ----
    private fun pubOf(id: String): String? = nodes[id]?.pub ?: prefs.getString("pin_$id", null)

    private fun pairKeyWith(id: String): SecretKeySpec? {
        val pub = pubOf(id) ?: return null
        return Crypto.pairKey(keyPair.private, pub)
    }

    private fun keyForDst(dst: String): SecretKeySpec? {
        if (dst == BROADCAST) return netKey
        if (dst.startsWith("g:")) return groups[dst.substring(2)]?.let { Crypto.aes(it.key) }
        return pairKeyWith(dst)
    }

    private fun keyForPacket(p: Packet): SecretKeySpec? {
        if (p.dst == BROADCAST || p.dst.startsWith("g:")) return keyForDst(p.dst)
        return pairKeyWith(p.src)
    }

    // ============ groups storage ============

    private fun loadGroups() {
        try {
            val a = JSONArray(prefs.getString("groups", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                groups[o.getString("g")] = Group(
                    o.getString("g"), o.getString("n"), o.getString("t"), o.getString("o"),
                    Base64.decode(o.getString("k"), Base64.NO_WRAP)
                )
            }
            val b = JSONArray(prefs.getString("invites", "[]"))
            for (i in 0 until b.length()) {
                val o = b.getJSONObject(i)
                invites[o.getString("g")] = Invite(
                    o.getString("g"), o.getString("n"), o.getString("t"), o.getString("o"),
                    o.getString("f"), Base64.decode(o.getString("k"), Base64.NO_WRAP)
                )
            }
        } catch (e: Exception) {
            // corrupted storage: start with no groups
        }
    }

    private fun saveGroups() {
        val a = JSONArray()
        for (g in groups.values) {
            a.put(JSONObject().put("g", g.gid).put("n", g.name).put("t", g.type).put("o", g.owner)
                .put("k", Base64.encodeToString(g.key, Base64.NO_WRAP)))
        }
        val b = JSONArray()
        for (v in invites.values) {
            b.put(JSONObject().put("g", v.gid).put("n", v.name).put("t", v.type).put("o", v.owner)
                .put("f", v.from).put("k", Base64.encodeToString(v.key, Base64.NO_WRAP)))
        }
        prefs.edit().putString("groups", a.toString()).putString("invites", b.toString()).apply()
    }

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
        if (callState != "idle") endLocal("ended")
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
        broadcastHello()
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
        netKey = Crypto.groupKey(p)
        emitState()
    }

    // ============ routing ============

    private fun helloPacket(): Packet {
        val ga = JSONArray()
        for (g in groups.values) if (g.type == "public") ga.put(JSONObject().put("g", g.gid).put("n", g.name))
        val b = JSONObject().put("name", name).put("pub", myPub).put("relay", relayEnabled)
            .put("nbrs", JSONArray(links.values.toList())).put("groups", ga)
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

    /** Returns (next hop node id, hop count) or (null, -1) when unreachable. */
    private fun routeInfo(dst: String): Pair<String?, Int> {
        val (dist, parent) = bfs()
        val d = dist[dst] ?: return Pair(null, -1)
        var cur = dst
        while (parent[cur] != nodeId) cur = parent[cur] ?: return Pair(null, -1)
        return Pair(cur, d)
    }

    private fun nextHop(dst: String): String? = routeInfo(dst).first

    private fun epOf(id: String): String? = links.entries.firstOrNull { it.value == id }?.key

    private fun send(ep: String, p: Packet) {
        client.sendPayload(ep, Payload.fromBytes(p.toBytes()))
            .addOnFailureListener { emitError("Send failed: ${it.message}") }
    }

    /** Sends own packets: next hop if a route is known, otherwise flood to all neighbours. */
    private fun transmit(pkts: List<Packet>, dst: String): Boolean {
        if (links.isEmpty()) return false
        val hopEp = if (isFlood(dst)) null else nextHop(dst)?.let { epOf(it) }
        for (p in pkts) {
            seen.add("${p.id}#${p.a}")
            if (hopEp != null) send(hopEp, p) else links.keys.forEach { send(it, p) }
        }
        return true
    }

    private fun forward(p: Packet, exclude: String) {
        if (isFlood(p.dst) || p.type == "hello") {
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
            if (bytes.isNotEmpty() && bytes[0] == AUDIO_MAGIC) {
                try {
                    handleAudio(ep, bytes)
                } catch (e: Exception) {
                    // drop bad audio frame
                }
                return
            }
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

        val inGroup = p.dst.startsWith("g:") && groups.containsKey(p.dst.substring(2))
        val forMe = p.dst == nodeId || p.dst == BROADCAST || inGroup
        if (forMe) handleLocal(p)
        if (p.dst != nodeId && p.ttl > 1 && relayEnabled) forward(p.copy(ttl = p.ttl - 1), ep)
    }

    private fun evDst(p: Packet): String = when {
        p.dst == nodeId -> "me"
        p.dst == BROADCAST -> "*"
        else -> p.dst
    }

    private fun handleLocal(p: Packet) {
        when (p.type) {
            "hello" -> handleHello(p)
            "ack" -> {
                if (outbox.remove(p.body) != null) emitStatus(p.body, "delivered")
            }
            "chat" -> {
                val direct = p.dst == nodeId
                if (direct && delivered.has(p.id)) { sendAck(p.src, p.id); return }
                val key = keyForPacket(p) ?: return
                val plain = Crypto.decrypt(key, p.body)
                if (plain == null) {
                    if (direct) emitError("Could not decrypt a message from ${nameOf(p.src)}.")
                    return
                }
                val text = JSONObject(String(plain, Charsets.UTF_8)).getString("text")
                if (direct) { delivered.add(p.id); sendAck(p.src, p.id) }
                emit(JSONObject().put("t", "msg").put("id", p.id).put("from", p.src).put("fromName", nameOf(p.src))
                    .put("dst", evDst(p)).put("text", text).put("ts", p.ts))
            }
            "file" -> handleFile(p)
            "ginv" -> handleInvite(p)
            "call" -> handleCallPacket(p)
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
        val ga = j.optJSONArray("groups")
        n.pubGroups = if (ga == null) emptyList() else (0 until ga.length()).map {
            val o = ga.getJSONObject(it)
            Pair(o.getString("g"), o.getString("n"))
        }
        n.seenAt = now()
        val waiting = deferred.remove(p.src)
        waiting?.forEach { it() }
        emitState()
    }

    private fun handleFile(p: Packet) {
        val direct = p.dst == nodeId
        val key = keyForPacket(p) ?: return
        val plain = Crypto.decrypt(key, p.body) ?: return
        val j = JSONObject(String(plain, Charsets.UTF_8))
        val fid = j.getString("fid")
        val n = j.getInt("n")
        val i = j.getInt("i")
        if (doneFiles.has(fid)) { if (direct) sendAck(p.src, fid); return }
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
            if (direct) sendAck(p.src, fid)
            emit(JSONObject().put("t", "file").put("id", fid).put("from", p.src).put("fromName", nameOf(p.src))
                .put("dst", evDst(p)).put("name", rx.name).put("mime", rx.mime)
                .put("size", out.size()).put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                .put("ts", p.ts))
        }
    }

    private fun sendAck(dst: String, key: String) {
        transmit(listOf(Packet(newId(), nodeId, dst, "ack", TTL, now(), key)), dst)
    }

    // ============ sending ============

    private fun dispatchOwn(key: String, pkts: List<Packet>, dst: String) {
        val ok = transmit(pkts, dst)
        if (!(ok && isFlood(dst))) outbox[key] = Out(pkts, dst)
        emitStatus(key, if (ok) "sent" else "queued")
    }

    /** Runs [action] now if the device's public key is known, otherwise as soon as it is seen. */
    private fun whenKeyKnown(dst: String, id: String, action: () -> Unit) {
        if (pubOf(dst) != null) { action(); return }
        val l = deferred.getOrPut(dst) { ArrayList() }
        if (l.size >= 100) {
            emitStatus(id, "failed")
            emitError("Too many messages waiting for that device.")
            return
        }
        l.add(action)
        emitStatus(id, "queued")
    }

    private fun target(dst: String): String? {
        if (dst == BROADCAST) return dst
        if (dst.startsWith("g:")) {
            return if (groups.containsKey(dst.substring(2))) dst else null
        }
        val id = normalizeId(dst)
        return if (isValidId(id) && id != nodeId) id else null
    }

    fun sendText(dst: String, text: String, id: String) {
        val d = target(dst)
        if (d == null) { emitError("Invalid receiver or you are not in that group."); emitStatus(id, "failed"); return }
        if (isFlood(d)) doSendText(d, text, id) else whenKeyKnown(d, id) { doSendText(d, text, id) }
    }

    private fun doSendText(d: String, text: String, id: String) {
        val key = keyForDst(d)
        if (key == null) { emitStatus(id, "failed"); return }
        val body = Crypto.encrypt(key, JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8))
        dispatchOwn(id, listOf(Packet(id, nodeId, d, "chat", TTL, now(), body)), d)
    }

    fun sendFile(dst: String, name: String, mime: String, data: ByteArray, fid: String) {
        if (data.size > MAX_FILE) { emitError("File larger than 1 MB."); emitStatus(fid, "failed"); return }
        val d = target(dst)
        if (d == null) { emitError("Invalid receiver or you are not in that group."); emitStatus(fid, "failed"); return }
        if (isFlood(d)) doSendFile(d, name, mime, data, fid)
        else whenKeyKnown(d, fid) { doSendFile(d, name, mime, data, fid) }
    }

    private fun doSendFile(d: String, name: String, mime: String, data: ByteArray, fid: String) {
        val key = keyForDst(d)
        if (key == null) { emitStatus(fid, "failed"); return }
        val n = maxOf(1, (data.size + CHUNK - 1) / CHUNK)
        val ts = now()
        val pkts = (0 until n).map { i ->
            val slice = data.copyOfRange(i * CHUNK, minOf(data.size, (i + 1) * CHUNK))
            val j = JSONObject().put("fid", fid).put("name", name).put("mime", mime)
                .put("i", i).put("n", n).put("d", Base64.encodeToString(slice, Base64.NO_WRAP))
            Packet("$fid-$i", nodeId, d, "file", TTL, ts, Crypto.encrypt(key, j.toString().toByteArray(Charsets.UTF_8)))
        }
        dispatchOwn(fid, pkts, d)
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
                if (isFlood(o.dst)) outbox.remove(key)
            }
        }
    }

    // ============ groups ============

    fun createGroup(gid: String, name: String, type: String) {
        if (!isValidId(gid) || groups.containsKey(gid)) return
        val t = if (type == "private") "private" else "public"
        val n = name.trim().take(30).ifEmpty { "Group " + gid.uppercase() }
        val key = if (t == "public") Crypto.publicGroupKeyBytes(gid) else Crypto.randomBytes(32)
        groups[gid] = Group(gid, n, t, nodeId, key)
        saveGroups()
        broadcastHello()
        emitState()
    }

    private fun discGroupName(gid: String): String? {
        for (n in nodes.values) for ((g, gn) in n.pubGroups) if (g == gid) return gn
        return null
    }

    /** Public groups can be joined only while a member advertising them is reachable. */
    fun joinGroup(code: String) {
        val g = normalizeId(code)
        if (!isValidId(g)) { emitError("Invalid group code."); return }
        if (groups.containsKey(g)) { emitError("You are already in this group."); return }
        val nm = discGroupName(g)
        if (nm == null) {
            emitError("No public group with this code is reachable right now. Private groups need an invitation.")
            return
        }
        groups[g] = Group(g, nm, "public", "", Crypto.publicGroupKeyBytes(g))
        saveGroups()
        broadcastHello()
        emitState()
    }

    fun leaveGroup(gid: String) {
        if (groups.remove(gid) != null) {
            saveGroups()
            broadcastHello()
            emitState()
        }
    }

    fun invite(gid: String, to: String) {
        val g = groups[gid] ?: return
        if (g.type != "private" || g.owner != nodeId) {
            emitError("Only the owner can invite people to a private group.")
            return
        }
        val id = normalizeId(to)
        if (!isValidId(id) || id == nodeId) { emitError("Invalid ID code."); return }
        val pid = newId()
        whenKeyKnown(id, pid) {
            val key = pairKeyWith(id)
            if (key != null) {
                val body = Crypto.encrypt(
                    key,
                    JSONObject().put("gid", g.gid).put("name", g.name).put("type", g.type).put("owner", g.owner)
                        .put("key", Base64.encodeToString(g.key, Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
                )
                dispatchOwn(pid, listOf(Packet(pid, nodeId, id, "ginv", TTL, now(), body)), id)
            }
        }
    }

    private fun handleInvite(p: Packet) {
        if (p.dst != nodeId) return
        if (delivered.has(p.id)) { sendAck(p.src, p.id); return }
        val key = pairKeyWith(p.src) ?: return
        val plain = Crypto.decrypt(key, p.body) ?: return
        val j = JSONObject(String(plain, Charsets.UTF_8))
        delivered.add(p.id)
        sendAck(p.src, p.id)
        val gid = j.getString("gid")
        if (groups.containsKey(gid) || invites.containsKey(gid)) return
        invites[gid] = Invite(
            gid, j.getString("name"), j.getString("type"), j.getString("owner"), p.src,
            Base64.decode(j.getString("key"), Base64.NO_WRAP)
        )
        saveGroups()
        emit(JSONObject().put("t", "invite").put("gid", gid).put("name", j.getString("name")).put("fromName", nameOf(p.src)))
        emitState()
    }

    fun acceptInvite(gid: String) {
        val v = invites.remove(gid) ?: return
        groups[gid] = Group(v.gid, v.name, v.type, v.owner, v.key)
        saveGroups()
        broadcastHello()
        emitState()
    }

    fun declineInvite(gid: String) {
        if (invites.remove(gid) != null) {
            saveGroups()
            emitState()
        }
    }

    // ============ voice calls ============

    private val ringTimeout = Runnable {
        val st = callState
        if (st == "out") {
            sendCall("end", callPeer, true)
            endLocal("noanswer")
        } else if (st == "in") {
            endLocal("missed")
        }
    }

    private val callTick = object : Runnable {
        override fun run() {
            if (callState != "active") return
            val (hop, hops) = routeInfo(callPeer)
            callRoute = hop?.let { epOf(it) }
            val loss = if (rxExpected > 0) (100 - rxCount * 100 / rxExpected).coerceIn(0, 100) else 100
            rxCount = 0
            rxExpected = 0
            emit(JSONObject().put("t", "callq").put("loss", loss).put("hops", hops))
            if (now() - lastRxAt > 8000) {
                sendCall("end", callPeer, true)
                endLocal("lost")
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun emitCall(st: String, reason: String = "") {
        emit(
            JSONObject().put("t", "call").put("st", st).put("peer", callPeer).put("name", nameOf(callPeer))
                .put("hops", routeInfo(callPeer).second).put("reason", reason)
                .put("dur", if (callStartedAt > 0) (now() - callStartedAt) / 1000 else 0)
        )
    }

    private fun sendCall(op: String, dst: String, twice: Boolean = false) {
        val key = pairKeyWith(dst) ?: return
        val body = Crypto.encrypt(key, JSONObject().put("op", op).put("cid", callId).toString().toByteArray(Charsets.UTF_8))
        transmit(listOf(Packet(newId(), nodeId, dst, "call", TTL, now(), body)), dst)
        if (twice) {
            handler.postDelayed({
                if (running) transmit(listOf(Packet(newId(), nodeId, dst, "call", TTL, now(), body)), dst)
            }, 400)
        }
    }

    fun callStart(dstRaw: String) {
        val dst = normalizeId(dstRaw)
        if (!running) { emitError("Turn the mesh node on first."); return }
        if (callState != "idle") { emitError("You are already in a call."); return }
        if (!isValidId(dst) || dst == nodeId) { emitError("Invalid ID code."); return }
        if (pubOf(dst) == null || nextHop(dst) == null) {
            emitError("That device is not reachable right now. Voice calls need a live route.")
            return
        }
        callPeer = dst
        callId = newId()
        callState = "out"
        callStartedAt = 0
        sendCall("invite", dst)
        emitCall("out")
        handler.postDelayed(ringTimeout, 35_000)
    }

    fun callAccept() {
        if (callState != "in") return
        handler.removeCallbacks(ringTimeout)
        ringHook?.invoke(null)
        sendCall("accept", callPeer, true)
        startAudio()
    }

    fun callReject() {
        if (callState != "in") return
        sendCall("reject", callPeer, true)
        endLocal("declined")
    }

    fun callEnd() {
        if (callState == "idle") return
        sendCall("end", callPeer, true)
        endLocal("ended")
    }

    fun callMute(on: Boolean) {
        callMuted = on
        voice?.muted = on
    }

    fun callSpeaker(on: Boolean) {
        callSpeakerOn = on
        voice?.setSpeaker(on)
    }

    private fun handleCallPacket(p: Packet) {
        if (p.dst != nodeId) return
        val key = pairKeyWith(p.src) ?: return
        val plain = Crypto.decrypt(key, p.body) ?: return
        val j = JSONObject(String(plain, Charsets.UTF_8))
        val op = j.getString("op")
        val cid = j.getString("cid")
        when (op) {
            "invite" -> {
                if (callState == "in" && cid == callId) return
                if (callState != "idle") {
                    val keep = callId
                    callId = cid
                    sendCall("busy", p.src)
                    callId = keep
                    return
                }
                callPeer = p.src
                callId = cid
                callState = "in"
                callStartedAt = 0
                emitCall("in")
                ringHook?.invoke(nameOf(p.src))
                handler.postDelayed(ringTimeout, 35_000)
            }
            "accept" -> {
                if (callState == "out" && cid == callId && p.src == callPeer) {
                    handler.removeCallbacks(ringTimeout)
                    startAudio()
                }
            }
            "reject", "busy", "end" -> {
                if (callState != "idle" && cid == callId && p.src == callPeer) {
                    endLocal(if (op == "reject") "declined" else if (op == "busy") "busy" else "ended")
                }
            }
        }
    }

    private fun startAudio() {
        if (callState == "active") return
        callState = "active"
        callStartedAt = now()
        txSeq = 0
        rxLastSeq = -1
        rxCount = 0
        rxExpected = 0
        lastRxAt = now()
        callKey = pairKeyWith(callPeer)
        val (hop, _) = routeInfo(callPeer)
        callRoute = hop?.let { epOf(it) }
        val v = VoiceCall(ctx) { frame -> handler.post { sendAudio(frame) } }
        voice = v
        if (!v.start()) {
            voice = null
            emitError("Could not start the microphone or speaker.")
            sendCall("end", callPeer, true)
            endLocal("error")
            return
        }
        v.muted = callMuted
        v.setSpeaker(callSpeakerOn)
        callHook?.invoke(true)
        emitCall("active")
        handler.postDelayed(callTick, 1000)
    }

    private fun idBytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII).copyOf(8)

    private fun sendAudio(frame: ByteArray) {
        if (callState != "active") return
        val ep = callRoute ?: return
        val key = callKey ?: return
        val enc = Crypto.encryptRaw(key, frame)
        val buf = ByteArray(20 + enc.size)
        buf[0] = AUDIO_MAGIC
        buf[1] = TTL.toByte()
        System.arraycopy(idBytes(nodeId), 0, buf, 2, 8)
        System.arraycopy(idBytes(callPeer), 0, buf, 10, 8)
        buf[18] = ((txSeq shr 8) and 0xFF).toByte()
        buf[19] = (txSeq and 0xFF).toByte()
        System.arraycopy(enc, 0, buf, 20, enc.size)
        txSeq = (txSeq + 1) and 0xFFFF
        client.sendPayload(ep, Payload.fromBytes(buf))
    }

    private fun handleAudio(ep: String, b: ByteArray) {
        if (b.size < 21 || !links.containsKey(ep)) return
        val src = String(b, 2, 8, Charsets.US_ASCII).trimEnd('\u0000')
        val dst = String(b, 10, 8, Charsets.US_ASCII).trimEnd('\u0000')
        val ttl = b[1].toInt()
        if (isBlocked(src)) return
        if (dst == nodeId) {
            if (callState != "active" || src != callPeer) return
            val key = callKey ?: return
            val seq = ((b[18].toInt() and 0xFF) shl 8) or (b[19].toInt() and 0xFF)
            val plain = Crypto.decryptRaw(key, b, 20) ?: return
            lastRxAt = now()
            if (rxLastSeq >= 0) {
                val gap = (seq - rxLastSeq) and 0xFFFF
                if (gap == 0 || gap > 32768) return      // duplicate or very old frame
                rxExpected += gap
            } else {
                rxExpected += 1
            }
            rxCount++
            rxLastSeq = seq
            voice?.play(plain)
        } else if (ttl > 1 && relayEnabled) {
            val hop = nextHop(dst)?.let { epOf(it) } ?: return
            if (hop == ep) return
            val copy = b.copyOf()
            copy[1] = (ttl - 1).toByte()
            client.sendPayload(hop, Payload.fromBytes(copy))
        }
    }

    private fun endLocal(reason: String) {
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(callTick)
        val wasActive = callState == "active"
        emitCall("ended", reason)
        voice?.stop()
        voice = null
        callState = "idle"
        callPeer = ""
        callId = ""
        callKey = null
        callRoute = null
        callStartedAt = 0
        ringHook?.invoke(null)
        if (wasActive) callHook?.invoke(false)
        emitState()
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

        val ga = JSONArray()
        for (gr in groups.values) {
            ga.put(JSONObject().put("gid", gr.gid).put("name", gr.name).put("type", gr.type)
                .put("owner", gr.owner).put("mine", gr.owner == nodeId))
        }
        s.put("groups", ga)
        val ia = JSONArray()
        for (v in invites.values) {
            ia.put(JSONObject().put("gid", v.gid).put("name", v.name).put("type", v.type)
                .put("from", v.from).put("fromName", nameOf(v.from)))
        }
        s.put("invites", ia)
        val da = JSONArray()
        val dseen = HashSet<String>()
        for (n in nodes.values) for ((gid, gn) in n.pubGroups) {
            if (!groups.containsKey(gid) && dseen.add(gid)) {
                da.put(JSONObject().put("gid", gid).put("name", gn).put("via", n.name))
            }
        }
        s.put("discGroups", da)
        s.put("call", callState).put("callPeer", callPeer)
        return s
    }
}
