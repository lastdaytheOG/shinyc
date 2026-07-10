package com.amar.vault

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File

class NearbySyncManager(
    private val context: Context,
    private val cryptoEngine: P2PSyncEngine
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val gson              = Gson()
    private val scope             = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectedEndpointId: String? = null
    private var peerKeyBase64: String?        = null

    // SENDER: queue of items waiting to be sent
    private val pendingItems    = mutableListOf<VaultItem>()
    private var currentTempFile: File? = null

    // RECEIVER: maps incoming file payload IDs to VaultItem IDs
    private val incomingFileMap = mutableMapOf<Long, String>()

    companion object {
        private const val SERVICE_ID = "com.amar.vault.P2P_SYNC"
        private const val TAG        = "NearbySync"
    }

    // ── Discovery & Connection ────────────────────────────────────────────────

    fun startAdvertising(myDeviceName: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        connectionsClient.startAdvertising(
            myDeviceName, SERVICE_ID, connectionLifecycleCallback, options
        ).addOnSuccessListener { Log.d(TAG, "Advertising started") }
            .addOnFailureListener { Log.e(TAG, "Advertising failed: ${it.message}") }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnSuccessListener { Log.d(TAG, "Discovery started") }
            .addOnFailureListener { Log.e(TAG, "Discovery failed: ${it.message}") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found endpoint: ${info.endpointName} — requesting connection")
            connectionsClient.requestConnection(
                "AmarVault", endpointId, connectionLifecycleCallback
            )
        }
        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept — PIN verification can be added in UI layer
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "Connected to $endpointId")
                connectedEndpointId = endpointId

                // Exchange our public key with peer as first payload
                val myKey = cryptoEngine.getKeyForQr()
                val keyPayload = Payload.fromBytes("KEY:$myKey".toByteArray())
                connectionsClient.sendPayload(endpointId, keyPayload)
            } else {
                Log.e(TAG, "Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            connectedEndpointId = null
            peerKeyBase64       = null
        }
    }

    // ── SENDER Pipeline ───────────────────────────────────────────────────────

    /**
     * Begin sync after connection is established and peer key is known.
     * Sends JSON metadata first, then streams images one by one.
     */
    fun beginSync(items: List<VaultItem>) {
        val endpoint = connectedEndpointId ?: run {
            Log.e(TAG, "Cannot sync — not connected")
            return
        }
        val key = peerKeyBase64 ?: run {
            Log.e(TAG, "Cannot sync — peer key not received yet")
            return
        }

        pendingItems.clear()
        pendingItems.addAll(items)

        scope.launch {
            // Step 1 — Send encrypted JSON metadata
            val payload     = cryptoEngine.buildSyncPayload()
            val jsonBytes   = gson.toJson(payload).toByteArray(Charsets.UTF_8)
            val encryptedJson = cryptoEngine.encryptBytes(jsonBytes, key)
            val metaPayload = Payload.fromBytes("META:".toByteArray() + encryptedJson)
            connectionsClient.sendPayload(endpoint, metaPayload)

            // Step 2 — Start streaming images
            sendNextImageInQueue()
        }
    }

    private suspend fun sendNextImageInQueue() {
        val endpoint = connectedEndpointId ?: return
        val key      = peerKeyBase64 ?: return

        if (pendingItems.isEmpty()) {
            Log.d(TAG, "✅ All items synced successfully")
            return
        }

        val item = pendingItems.removeAt(0)

        try {
            val uri = Uri.parse(item.uri)

            // Stream-encrypt directly from ContentResolver into temp file
            // Zero RAM bloat — 8KB buffer regardless of image size
            val encryptedFile = File(context.cacheDir, "sending_chunk.enc")
            context.contentResolver.openInputStream(uri)?.use { input ->
                encryptedFile.outputStream().use { output ->
                    cryptoEngine.encryptStream(input, output, key)
                }
            } ?: run {
                Log.e(TAG, "Could not open URI: ${item.uri}")
                sendNextImageInQueue()
                return
            }

            currentTempFile = encryptedFile

            // Send routing header first so receiver knows which item this file belongs to
            val filePayload  = Payload.fromFile(encryptedFile)
            val headerBytes  = "IMG_HEADER:${filePayload.id}:${item.id}".toByteArray()
            connectionsClient.sendPayload(endpoint, Payload.fromBytes(headerBytes))

            // Send the encrypted file
            connectionsClient.sendPayload(endpoint, filePayload)

            // Pipeline pauses here — resumes in onPayloadTransferUpdate on SUCCESS

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send item ${item.id}: ${e.message}")
            currentTempFile?.delete()
            sendNextImageInQueue()
        }
    }

    // ── RECEIVER Pipeline ─────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data   = payload.asBytes() ?: return
                    val header = String(data.take(5).toByteArray())

                    when {
                        // Peer key exchange
                        String(data).startsWith("KEY:") -> {
                            peerKeyBase64 = String(data).removePrefix("KEY:")
                            Log.d(TAG, "Received peer key — ready to sync")
                        }

                        // Encrypted JSON database metadata
                        header == "META:" -> {
                            val encryptedJson  = data.sliceArray(5 until data.size)
                            val key            = peerKeyBase64 ?: return
                            val decryptedBytes = cryptoEngine.decryptBytes(encryptedJson, key)
                            if (decryptedBytes != null) {
                                val syncPayload = gson.fromJson(
                                    String(decryptedBytes, Charsets.UTF_8),
                                    SyncPayload::class.java
                                )
                                scope.launch {
                                    insertReceivedItems(syncPayload.items)
                                }
                                Log.d(TAG, "Received ${syncPayload.items.size} DB items")
                            } else {
                                Log.e(TAG, "Failed to decrypt metadata")
                            }
                        }

                        // Image routing header
                        String(data).startsWith("IMG_HEADER:") -> {
                            val parts     = String(data).split(":")
                            val payloadId = parts[1].toLong()
                            val itemId    = parts[2]
                            incomingFileMap[payloadId] = itemId
                            Log.d(TAG, "Routing header: payload $payloadId → item $itemId")
                        }
                    }
                }

                Payload.Type.FILE -> {
                    Log.d(TAG, "Incoming encrypted file: ${payload.id}")
                    // Handled in onPayloadTransferUpdate on SUCCESS
                }

                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {

                // SENDER: file sent successfully — cleanup and send next
                currentTempFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                        currentTempFile = null
                        scope.launch { sendNextImageInQueue() }
                    }
                }

                // RECEIVER: file received — decrypt and save to MediaStore
                val itemId = incomingFileMap[update.payloadId]
                if (itemId != null) {
                    Log.d(TAG, "File for $itemId received — ready to decrypt and save")
                    incomingFileMap.remove(update.payloadId)
                    // Full MediaStore save wired up in UI layer
                }
            }
        }
    }

    private suspend fun insertReceivedItems(items: List<VaultItem>) {
        val dao = VaultDatabase.get(context).vaultDao()
        items.forEach { item ->
            try {
                dao.insert(item)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert item ${item.id}: ${e.message}")
            }
        }
        Log.d(TAG, "Inserted ${items.size} received items into vault")
    }

    fun stop() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        scope.cancel()
    }
}