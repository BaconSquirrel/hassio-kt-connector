package de.baconsquirrel.hassiokt.connector.websocket

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.json
import de.baconsquirrel.hassiokt.connector.HassioConnector
import de.baconsquirrel.hassiokt.connector.general.EntityEvent
import de.baconsquirrel.hassiokt.connector.general.EntityState
import de.baconsquirrel.hassiokt.connector.general.ServiceMessage
import de.baconsquirrel.hassiokt.connector.internal.completeOnFirstOccurrenceMatching
import de.baconsquirrel.hassiokt.connector.internal.firstOccurrenceMatching
import de.baconsquirrel.hassiokt.connector.internal.mapNotNull
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import mu.KotlinLogging
import okhttp3.*
import okio.ByteString
import java.io.StringReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReconnectingReactiveWebSocketWrapper(
    private val hassioIpAddress: String,
    private val hassioPort: Int,
    private val accessToken: String
) : HassioConnector {

    companion object {
        private const val REOPEN_AFTER_CLOSED_COOLDOWN_SECONDS = 2
        private const val WEBSOCKET_CLOSE_STATUS_NORMAL = 1000
    }

    private enum class ConnectionState {
        OPENING, OPEN, AUTHENTICATED, CLOSED
    }

    private data class WebSocketData(
        val state: ConnectionState,
        val webSocket: WebSocket,
        val serverJsonMessagesObs: Observable<JsonObject>,
        val nextMessageId: AtomicLong
    )

    //
    //

    private val log = KotlinLogging.logger("connector.ReconnectingWebSocket")
    private val connectorScheduler = Schedulers.from(Executors.newSingleThreadExecutor { Thread(it, "ReconnectingWebSocketConnectorThread") })

    private val jsonParser = Parser.default()
    private val webSocketSubj: BehaviorSubject<WebSocketData> = BehaviorSubject.create()
    private val disposables = CompositeDisposable()

    init {
        this.log.info { "init" }
        this.disposables += this.initAuthenticateOnOpenState()
        this.disposables += this.initReopenOnClosedState()
    }

    private fun initAuthenticateOnOpenState() = this.webSocketSubj
        .filter { data -> data.state == ConnectionState.OPEN }
        .doOnNext { this.log.info { "authenticateOnOpenState$ Current web socket is open, trying to authenticate..." } }
        .switchMapSingle { (_, webSocket, jsonMessageObs, nextMsgId) ->
            Completable.concatArray(
                Completable.fromRunnable { this.log.info { "authenticateOnOpenState: Waiting for 'aut_required' message..." } },
                jsonMessageObs.completeOnFirstOccurrenceMatching { it.string("type") == "auth_required" },
                Completable.fromRunnable {
                    this.log.info { "authenticateOnOpenState: Sending 'auth' message with access token, then wait for 'auth_ok'..." }
                    webSocket.send(json { obj("type" to "auth", "access_token" to accessToken) }.toJsonString())
                },
                jsonMessageObs.completeOnFirstOccurrenceMatching { it.string("type") == "auth_ok" },
                Completable.fromRunnable { this.log.info { "authenticateOnOpenState: Got 'auth_ok', now the fun starts =)" } }
            ).andThen(Single.just(WebSocketData(ConnectionState.AUTHENTICATED, webSocket, jsonMessageObs, nextMsgId)))
        }
        .subscribe { authWebSocketData -> this.webSocketSubj.onNext(authWebSocketData) }


    private fun initReopenOnClosedState() = this.webSocketSubj
        .filter { data -> data.state == ConnectionState.CLOSED }
        .doOnNext { this.log.info { "reopenOnClosedState$ Reached state CLOSED, reopening in a few seconds..." } }
        .delay(REOPEN_AFTER_CLOSED_COOLDOWN_SECONDS.toLong(), TimeUnit.SECONDS, this.connectorScheduler)
        .subscribe {
            this.log.info { "reopenOnClosedState$ Reopening..." }
            this.reopenWebSocketConnection()
        }

    private fun reopenWebSocketConnection() {
        this.log.info { "reopenWebSocketConnection" }
        val serverJsonMessagesSub: BehaviorSubject<JsonObject> = BehaviorSubject.create()
        val serverJsonMessageObs = serverJsonMessagesSub.hide()
        val nextMsgId = AtomicLong(1)

        this.webSocketSubj.onNext(WebSocketData(
            state = ConnectionState.OPENING,
            webSocket = OkHttpClient().newWebSocket(
                request = Request.Builder().url("ws://$hassioIpAddress:$hassioPort/api/websocket").build(),
                listener = object : WebSocketListener() {
                    private val listenerLog = KotlinLogging.logger("connector.ReconnectingWebSocket.Listener")

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        this.listenerLog.info { "onOpen# response=$response" }
                        connectorScheduler.scheduleDirect {
                            webSocketSubj.onNext(WebSocketData(ConnectionState.OPEN, webSocket, serverJsonMessageObs, nextMsgId))
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        this.listenerLog.debug { "onMessage# text=$text" }
                        kotlin.runCatching { jsonParser.parse(StringReader(text)) as JsonObject }
                            .onFailure { err -> listenerLog.warn { "onMessage# Not mappable '$text', error=$err" } }
                            .onSuccess { connectorScheduler.scheduleDirect { serverJsonMessagesSub.onNext(it) } }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        this.listenerLog.debug { "onMessage# bytes=$bytes" }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        this.listenerLog.info { "onClosing# code=$code, reason=$reason" }
                        connectorScheduler.scheduleDirect {
                            webSocketSubj.onNext(WebSocketData(ConnectionState.CLOSED, webSocket, serverJsonMessageObs, nextMsgId))
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        this.listenerLog.info { "onClosed# code=$code, reason=$reason" }
                        connectorScheduler.scheduleDirect {
                            webSocketSubj.onNext(WebSocketData(ConnectionState.CLOSED, webSocket, serverJsonMessageObs, nextMsgId))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        this.listenerLog.info { "onFailure# t=$t, response=$response" }
                        connectorScheduler.scheduleDirect {
                            webSocketSubj.onNext(WebSocketData(ConnectionState.CLOSED, webSocket, serverJsonMessageObs, nextMsgId))
                        }
                    }
                }),
            serverJsonMessagesObs = serverJsonMessageObs,
            nextMessageId = nextMsgId
        ))
    }

    //
    //

    override fun start() {
        this.log.info { "start#" }
        this.disposables += this.connectorScheduler.scheduleDirect {
            this.reopenWebSocketConnection()
        }
    }

    override fun stop(): Completable {
        this.log.info { "stop#" }
        return Completable
            .fromRunnable {
                this.log.info { "stop$ Disposing internal state subscriptions and asking web socket to be closed..." }
                this.disposables.dispose()
                this.webSocketSubj.value?.webSocket?.close(WEBSOCKET_CLOSE_STATUS_NORMAL, null)
            }
            .andThen(this.webSocketSubj)
            .filter { data -> data.state == ConnectionState.CLOSED }
            .firstOrError()
            .ignoreElement()
            .doOnComplete { this.log.info { "stop$ Shutdown completed successfully =)" } }
            .doOnError { excpt -> this.log.error(excpt) { "stop$ Error on shutdown =(" } }
            .onErrorComplete()
            .subscribeOn(this.connectorScheduler)
    }

    override val entityStateChanges: Observable<EntityState> = this.webSocketSubj
        .filter { data -> data.state == ConnectionState.AUTHENTICATED }
        .doOnNext { this.log.info { "entityStateChanges$ Got authenticated web socket, building up state transfer chain... " } }
        .switchMap { Observable.concatArray(it.connectInitialStateFetch(), it.connectStateChangeEventSubscription()) }
        .doOnError { this.log.error(it) { "entityStateChanges$: $it" } }
        .subscribeOn(this.connectorScheduler)
        .replay()
        .refCount()

    override val entityEvents: Observable<EntityEvent> = this.webSocketSubj
        .filter { data -> data.state == ConnectionState.AUTHENTICATED }
        .doOnNext { this.log.info { "entityEvents$ Got authenticated web socket, building up event transfer chain... " } }
        .switchMap { it.connectStateEventSubscription() }
        .subscribeOn(this.connectorScheduler)
        .share()

    override fun callServiceWith(message: ServiceMessage): Completable = this.webSocketSubj
        .filter { data -> data.state == ConnectionState.AUTHENTICATED }
        .firstOrError()
        .flatMapCompletable { it.callServiceWith(message) }
        .doOnError { this.log.error(it) { "callServiceWith$: $it" } }
        .subscribeOn(this.connectorScheduler)


    //
    //

    private fun WebSocketData.connectInitialStateFetch(): Observable<EntityState> {
        log.info { "connectInitialStateTransfer#" }

        return Single
            .fromCallable {
                val id = this.nextMessageId.getAndIncrement()
                log.info { "connectInitialStateTransfer$ Sending 'get_states' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "get_states") }.toJsonString())
                id
            }
            .flatMap { id -> this.serverJsonMessagesObs.firstOccurrenceMatching { it.long("id") == id } }
            .map { msg -> msg.array<JsonObject>("result")?.toEntityStateObjects() ?: listOf() }
            .doOnSuccess { log.info { "connectInitialStateTransfer$ Result contains ${it.size} entity objects" } }
            .flatMapObservable { entityObjects -> Observable.fromIterable(entityObjects) }
    }

    private fun WebSocketData.connectStateChangeEventSubscription(): Observable<EntityState> {
        log.info { "connectStateChangeEventSubscription#" }

        return Single
            .fromCallable {
                val id = this.nextMessageId.getAndIncrement()
                log.info { "connectStateChangeEventSubscription$ Sending 'subscribe_events' for 'state_changed' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "subscribe_events", "event_type" to "state_changed") }.toJsonString())
                id
            }
            .flatMapObservable { id -> this.serverJsonMessagesObs.filter { it.long("id") == id } }
            .mapNotNull { msg -> msg.obj("event")?.obj("data")?.toEntityStateObject() }
    }

    private fun WebSocketData.connectStateEventSubscription(): Observable<EntityEvent> {
        log.info { "connectStateEventSubscription#" }

        return Single
            .fromCallable {
                val id = this.nextMessageId.getAndIncrement()
                log.info { "connectStateEventSubscription: Sending 'subscribe_events' for 'deconz_event' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "subscribe_events", "event_type" to "deconz_event") }.toJsonString())
                id
            }
            .flatMapObservable { id -> this.serverJsonMessagesObs.filter { it.long("id") == id } }
            .mapNotNull { msg -> msg.obj("event")?.obj("data")?.toEntityEventObject() }
            .doOnError { log.error(it) { "connectStateEventSubscription: $it" } }
    }

    private fun WebSocketData.callServiceWith(message: ServiceMessage): Completable {
        log.info { "callServiceWith# message=$message" }

        // The message gets converted into one or more json command objects. For example while turning off a light only requires one single
        // 'turn_off' message, turning it on and bring it to another brightness level or color needs one single 'turn_on' message for each
        // single step.
        //
        // The generated command objects are treated one after the other in the following way:
        // 1. Equip the command object with a unique identifier
        // 2. Send the enhanced command object over to home assistant
        // 3. Wait for a result object from home assistant carrying the same unique identifier
        // 4. If the result object states success of the command, continue with the next command object in the same way. If the result
        //    object states an error for executing the command abort sending the other command objects and report the error back to the
        //    caller.
        // 5. If all command objects executions were reported successfully report that back to the caller.

        val callLog = KotlinLogging.logger("connector.ReconnectingWebSocket.callServiceWith[$message]")
        val commandObjects = message.toJsonCommandMessages().toList()
        val commandCount = commandObjects.size

        callLog.info { "Processing $commandCount commands..." }
        return commandObjects
            .withIndex()
            .map { (idx, jsonValue) ->
                Single.fromCallable { this.nextMessageId.getAndIncrement() }
                    .doOnSuccess { commandId ->
                        val commandJsonString = JsonObject(jsonValue).also { it["id"] = commandId }.toJsonString()

                        callLog.debug { "${idx + 1} / $commandCount: Sending $commandJsonString" }
                        this.webSocket.send(commandJsonString)
                        callLog.debug { "${idx + 1} / $commandCount: Waiting for result object..." }
                    }
                    .flatMap { commandId ->
                        this.serverJsonMessagesObs
                            .doOnNext { callLog.debug { "${idx + 1} / $commandCount: Possible result object: ${it.toJsonString()}" } }
                            .firstOccurrenceMatching { it.long("id") == commandId }
                    }
                    .flatMapCompletable { resultObj ->
                        callLog.debug { "${idx + 1} / $commandCount: resultObj=${resultObj.toJsonString()}" }
                        resultObj
                            .takeIf { it.boolean("success") == false }
                            ?.let { Completable.error { HassioConnector.CallServiceFailedException(it.obj("error")?.toJsonString()) } }
                            ?: Completable.complete()
                    }
            }
            .let { commandSendAndWaitCompletables -> Completable.concat(commandSendAndWaitCompletables) }
            .doOnError { error -> callLog.error { "$error" } }
            .doOnComplete { callLog.info { "Successfully processed all $commandCount commands =)" } }
    }
}