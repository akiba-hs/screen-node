package space.akiba.screen_node

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.LowLatencyVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Приёмная сторона WebRTC: один RTCPeerConnection на сессию сигналинга,
 * только приём (recvonly), без STUN/TURN — в LAN хватает host/prflx-кандидатов.
 * Все публичные методы и колбэки Listener — в главном потоке.
 */
class WebRtcReceiver(
    context: Context,
    eglBase: EglBase,
    private val sink: VideoSink,
    private val listener: Listener,
    lowLatency: Boolean,
    lowLatencyDecoder: Boolean,
) {
    interface Listener {
        fun onAnswer(session: Long, sdp: String)
        fun onLocalCandidate(session: Long, candidate: JSONObject)
        fun onConnected(session: Long)
        /** Первый кадр сессии отрисован — можно убирать заставку. */
        fun onFirstFrame(session: Long)
        /** Краткая сводка статистики приёма (раз в STATS_INTERVAL_MS). */
        fun onStats(summary: String)
        /** Соединение упало (FAILED или затяжной DISCONNECTED) — нужны новые переговоры. */
        fun onConnectionLost(session: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    // Последовательный фоновый поток WebRTC: создание фабрики, освобождение соединений и фабрики.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "webrtc-worker") }
    // Загрузка нативной библиотеки и создание фабрики занимают сотни миллисекунд на слабых
    // приставках, поэтому выполняются в фоне и не задерживают первый кадр заставки. Фабрика
    // нужна только с первым offer; если он придёт раньше готовности, главный поток дождётся её.
    private val factoryTask: Future<PeerConnectionFactory> =
        worker.submit(Callable { createFactory(context.applicationContext, eglBase, lowLatency, lowLatencyDecoder) })

    /** Фабрика соединений; null — инициализация WebRTC не удалась (ошибка уже в логе). */
    private val factory: PeerConnectionFactory?
        get() = try {
            factoryTask.get()
        } catch (e: ExecutionException) {
            Log.e(TAG, "инициализация WebRTC не удалась", e.cause)
            null
        }

    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var videoSink: SessionSink? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var connected = false
    private var stats = StatsState()

    /** Номер текущей сессии сигналинга (0 — соединения нет). */
    var session = 0L
        private set

    private val disconnectTimeout = Runnable { lost("DISCONNECTED дольше $DISCONNECT_GRACE_MS мс") }
    private val statsTick = object : Runnable {
        override fun run() {
            pc?.getStats { report -> main.post { logStats(report) } }
            main.postDelayed(this, STATS_INTERVAL_MS)
        }
    }


    private fun createFactory(context: Context, eglBase: EglBase, lowLatency: Boolean, lowLatencyDecoder: Boolean): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(if (lowLatency) LOW_LATENCY_FIELD_TRIALS else "")
                .createInitializationOptions()
        )
        Log.i(TAG, "низкая задержка: воспроизведение $lowLatency, декодер $lowLatencyDecoder")
        // Звук трансляции — как медиа (громкость «Музыка/видео»), без эхоподавления:
        // микрофон проектору не нужен.
        val adm = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder()
            // Как DefaultVideoDecoderFactory (сначала аппаратные MediaCodec, программные — запасные),
            // но аппаратный декодер в режиме низкой задержки (см. LowLatencyVideoDecoderFactory).
            .setVideoDecoderFactory(LowLatencyVideoDecoderFactory(eglBase.eglBaseContext, lowLatencyDecoder))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        adm.release() // фабрика держит свою ссылку
        return factory
    }

    /** Новый offer от отправителя: старое соединение закрывается, создаётся новое. */
    fun handleOffer(session: Long, sdp: String) {
        close()
        // Без WebRTC принять трансляцию нельзя; проектор остаётся на заставке.
        val factory = factory ?: return
        this.session = session
        stats = StatsState()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // В LAN достаточно UDP; TCP-кандидаты только удлиняют проверку связности.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        val peer = factory.createPeerConnection(config, Observer(session))
        if (peer == null) {
            Log.e(TAG, "не удалось создать PeerConnection")
            lost("createPeerConnection вернул null")
            return
        }
        pc = peer
        Log.i(TAG, "сессия $session: получен offer (${sdp.length} байт)")

        peer.setRemoteDescription(object : SdpAdapter("setRemoteDescription", session) {
            override fun onSetSuccess() = onMain(session) { onRemoteOfferApplied(peer, session) }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun onRemoteOfferApplied(peer: PeerConnection, session: Long) {
        remoteDescriptionSet = true
        for (t in peer.transceivers) {
            // Проектор только принимает: ничего не отправляем.
            t.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) preferH264(t)
        }
        pendingCandidates.forEach { peer.addIceCandidate(it) }
        pendingCandidates.clear()

        peer.createAnswer(object : SdpAdapter("createAnswer", session) {
            override fun onCreateSuccess(desc: SessionDescription) = onMain(session) {
                peer.setLocalDescription(object : SdpAdapter("setLocalDescription", session) {
                    override fun onSetSuccess() = onMain(session) {
                        Log.i(TAG, "сессия $session: answer готов")
                        listener.onAnswer(session, desc.description)
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    /** Если отправитель не упорядочил кодеки (например, Firefox), H.264 выбираем сами. */
    private fun preferH264(t: RtpTransceiver) {
        try {
            val codecs = factory?.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
            val sorted = codecs.sortedBy { if (it.name.equals("H264", ignoreCase = true)) 0 else 1 }
            t.setCodecPreferences(sorted)
        } catch (e: Throwable) {
            Log.w(TAG, "setCodecPreferences не поддержан: ${e.message}")
        }
    }

    fun addRemoteCandidate(session: Long, json: JSONObject) {
        if (session != this.session) return
        val candidate = IceCandidate(
            json.optString("sdpMid", ""),
            json.optInt("sdpMLineIndex", 0),
            json.optString("candidate", ""),
        )
        if (candidate.sdp.isEmpty()) return // end-of-candidates
        val peer = pc ?: return
        if (remoteDescriptionSet) peer.addIceCandidate(candidate) else pendingCandidates += candidate
    }

    /** Закрыть текущее соединение (трансляция остановлена или начинается новая сессия). */
    fun close() {
        main.removeCallbacks(disconnectTimeout)
        main.removeCallbacks(statsTick)
        videoSink?.let { videoTrack?.removeSink(it) }
        videoTrack = null
        videoSink = null
        pc?.let {
            Log.i(TAG, "сессия $session: соединение закрыто")
            // dispose() синхронно ждёт потоки WebRTC — на слабой приставке это десятки и
            // сотни миллисекунд, поэтому не в главном потоке (заставка не дёргается).
            worker.execute { it.dispose() }
        }
        pc = null
        session = 0
        remoteDescriptionSet = false
        connected = false
        pendingCandidates.clear()
    }

    fun dispose() {
        close()
        // Фабрику — после всех соединений (тот же последовательный поток), и дождаться:
        // сразу за этим освобождается EGL-контекст, который использует декодер.
        worker.submit { runCatching { factoryTask.get() }.getOrNull()?.dispose() }.get()
        worker.shutdown()
    }

    private fun lost(reason: String) {
        val s = session
        Log.w(TAG, "сессия $s: соединение потеряно — $reason")
        listener.onConnectionLost(s)
    }

    /** Колбэки WebRTC приходят в его signaling-потоке — переносим в главный и отбрасываем устаревшие. */
    private fun onMain(session: Long, block: () -> Unit) {
        main.post { if (session == this.session && pc != null) block() }
    }

    private inner class Observer(private val session: Long) : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = onMain(session) {
            val json = JSONObject()
                .put("candidate", c.sdp)
                .put("sdpMid", c.sdpMid)
                .put("sdpMLineIndex", c.sdpMLineIndex)
            listener.onLocalCandidate(session, json)
        }

        override fun onTrack(transceiver: RtpTransceiver) = onMain(session) {
            val track = transceiver.receiver.track()
            Log.i(TAG, "сессия $session: трек ${track?.kind()}")
            if (track is VideoTrack) {
                videoSink?.let { videoTrack?.removeSink(it) }
                val proxy = SessionSink(session)
                videoTrack = track
                videoSink = proxy
                track.addSink(proxy)
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onMain(session) {
            Log.i(TAG, "сессия $session: состояние $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    main.removeCallbacks(disconnectTimeout)
                    if (!connected) {
                        connected = true
                        listener.onConnected(session)
                        main.removeCallbacks(statsTick)
                        main.postDelayed(statsTick, 2000)
                    }
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    main.removeCallbacks(disconnectTimeout)
                    main.postDelayed(disconnectTimeout, DISCONNECT_GRACE_MS)
                }
                PeerConnection.PeerConnectionState.FAILED -> lost("FAILED")
                else -> Unit
            }
        }

        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {
            Log.i(TAG, "сессия $session: выбрана пара ${event.local.sdp} ⇄ ${event.remote.sdp} (${event.reason})")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "сессия $session: ICE $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    /** Прокси к рендереру, сообщающий о первом кадре сессии (вызывается в потоке декодера). */
    private inner class SessionSink(private val session: Long) : VideoSink {
        @Volatile private var first = true

        override fun onFrame(frame: VideoFrame) {
            sink.onFrame(frame)
            if (first) {
                first = false
                onMain(session) { listener.onFirstFrame(session) }
            }
        }
    }

    private open inner class SdpAdapter(private val op: String, private val session: Long) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = fail(error)
        override fun onSetFailure(error: String?) = fail(error)
        private fun fail(error: String?) = onMain(session) {
            Log.e(TAG, "сессия $session: $op: $error")
            lost("$op: $error")
        }
    }

    // ---------- Статистика и оценка задержки ----------

    private data class StatsState(
        var jbDelay: Double = 0.0,
        var jbCount: Double = 0.0,
        var decodeTime: Double = 0.0,
        var framesDecoded: Double = 0.0,
        var bytes: Double = 0.0,
        var timestampUs: Double = 0.0,
    )

    private fun logStats(report: RTCStatsReport) {
        if (pc == null) return
        val all = report.statsMap.values
        val inbound = all.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" } ?: return
        val m = inbound.members
        fun num(map: Map<String, Any>, key: String) = (map[key] as? Number)?.toDouble() ?: 0.0

        val jbDelay = num(m, "jitterBufferDelay")
        val jbCount = num(m, "jitterBufferEmittedCount")
        val decodeTime = num(m, "totalDecodeTime")
        val framesDecoded = num(m, "framesDecoded")
        val bytes = num(m, "bytesReceived")
        val ts = inbound.timestampUs

        val prev = stats
        val jbMs = delta(jbDelay - prev.jbDelay, jbCount - prev.jbCount) * 1000
        val decodeMs = delta(decodeTime - prev.decodeTime, framesDecoded - prev.framesDecoded) * 1000
        val mbps = if (prev.timestampUs > 0) (bytes - prev.bytes) * 8 / (ts - prev.timestampUs) else 0.0
        stats = StatsState(jbDelay, jbCount, decodeTime, framesDecoded, bytes, ts)

        val codec = (m["codecId"] as? String)?.let { report.statsMap[it] }?.members?.get("mimeType") ?: "?"
        val transport = all.firstOrNull { it.type == "transport" }
        val pair = (transport?.members?.get("selectedCandidatePairId") as? String)?.let { report.statsMap[it] }
        val rttMs = pair?.let { num(it.members, "currentRoundTripTime") * 1000 } ?: 0.0
        val path = pair?.let {
            fun cand(id: Any?) = (id as? String)?.let { report.statsMap[it] }?.members
            val l = cand(it.members["localCandidateId"])
            val r = cand(it.members["remoteCandidateId"])
            "${l?.get("candidateType")} ${l?.get("address")} ⇄ ${r?.get("candidateType")} ${r?.get("address")}"
        } ?: "?"

        listener.onStats(
            String.format(
                Locale.ROOT, "%s %.0fx%.0f %.0f fps %.1f Мбит/с\njb %.0f мс, декод %.1f мс, RTT %.0f мс",
                m["decoderImplementation"], num(m, "frameWidth"), num(m, "frameHeight"), num(m, "framesPerSecond"),
                mbps, jbMs, decodeMs, rttMs,
            )
        )
        Log.i(
            TAG, String.format(
                Locale.ROOT,
                "stats: %s decoder=%s %.0fx%.0f %.0f fps %.2f Мбит/с | jitterBuffer=%.0f мс decode=%.1f мс RTT=%.0f мс " +
                    "⇒ задержка приёма ≈ %.0f мс (+ захват и кодирование у отправителя) | lost=%.0f dropped=%.0f | %s",
                codec, m["decoderImplementation"], num(m, "frameWidth"), num(m, "frameHeight"),
                num(m, "framesPerSecond"), mbps, jbMs, decodeMs, rttMs, jbMs + decodeMs + rttMs / 2,
                num(m, "packetsLost"), num(m, "framesDropped"), path,
            )
        )
    }

    private fun delta(value: Double, count: Double) = if (count > 0) value / count else 0.0

    companion object {
        private const val TAG = "ProjectorRtc"
        private const val DISCONNECT_GRACE_MS = 4000L
        private const val STATS_INTERVAL_MS = 5000L

        /**
         * Нулевая задержка воспроизведения: кадр рендерится сразу после декодирования,
         * без сглаживающей буферизации (обычно ~40–50 мс по Wi-Fi). Для слайдов и демо
         * экрана задержка важнее идеальной плавности.
         */
        private const val LOW_LATENCY_FIELD_TRIALS =
            "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/" +
                "WebRTC-ZeroPlayoutDelay/min_pacing:0ms,max_decode_queue_size:8/"
    }
}
