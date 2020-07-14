package de.baconsquirrel.hassiokt.connector.websocket

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.json
import de.baconsquirrel.hassiokt.connector.HassioConnector
import de.baconsquirrel.hassiokt.connector.general.EntityEvent
import de.baconsquirrel.hassiokt.connector.general.EntityState
import de.baconsquirrel.hassiokt.connector.general.ServiceMessage
import de.baconsquirrel.hassiokt.connector.internal.completeOnFirstOccurrenceMatching
import de.baconsquirrel.hassiokt.connector.internal.completeOnFirstOccurrenceOf
import de.baconsquirrel.hassiokt.connector.internal.firstOccurrenceMatching
import de.baconsquirrel.hassiokt.connector.internal.mapNotNull
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.StringReader
import java.util.concurrent.Executors

private const val STATUS_NORMAL = 1000

/**
 * @see [https://developers.home-assistant.io/docs/external_api_websocket]
 */
class HassioConnectorWebSocket(hassioIpAddress: String, hassioPort: Int, private val accessToken: String) : HassioConnector {

    private val log = KotlinLogging.logger("connector.WebSocket")
    private val connectorScheduler = Schedulers.from(Executors.newSingleThreadExecutor { Thread(it, "WebSocketConnectorThread") })

    init {
        this.log.info { "init" }
    }

    //
    //

    private enum class WebSocketState { OPEN, CLOSING, CLOSED }

    private var webSocketMessageId = 1L
    private val webSocketStateSub = BehaviorSubject.createDefault(WebSocketState.CLOSED)
    private val webSocketTextMessageSub = BehaviorSubject.create<String>()

    private val webSocket = OkHttpClient().newWebSocket(
        request = Request.Builder().url("ws://$hassioIpAddress:$hassioPort/api/websocket").build(),
        listener = object : WebSocketListener() {
            private val log = KotlinLogging.logger("connector.WebSocket.Listener")

            override fun onOpen(webSocket: WebSocket, response: Response) {
                this.log.info { "onOpen: response=$response" }
                with(this@HassioConnectorWebSocket) {
                    this.connectorScheduler.scheduleDirect { this.webSocketStateSub.onNext(WebSocketState.OPEN) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                this.log.debug { "onMessage: text=$text" }
                with(this@HassioConnectorWebSocket) {
                    this.connectorScheduler.scheduleDirect { this.webSocketTextMessageSub.onNext(text) }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                this.log.debug { "onMessage: bytes=$bytes" }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                this.log.info { "onClosing: code=$code, reason=$reason" }
                with(this@HassioConnectorWebSocket) {
                    this.connectorScheduler.scheduleDirect { this.webSocketStateSub.onNext(WebSocketState.CLOSING) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this.log.info { "onClosed: code=$code, reason=$reason" }
                with(this@HassioConnectorWebSocket) {
                    this.connectorScheduler.scheduleDirect { this.webSocketStateSub.onNext(WebSocketState.CLOSED) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this.log.info { "onFailure: t=$t, response=$response" }
                t.printStackTrace()
            }
        })

    //
    //

    private val jsonParser = Parser.default()
    private val webSocketJsonMessageObs = this.webSocketTextMessageSub
        .flatMap { rawTextMessage ->
            kotlin.runCatching { this.jsonParser.parse(StringReader(rawTextMessage)) as JsonObject }
                .onFailure { err -> this.log.warn { "webSocketJsonMessageObs: Not mappable '$rawTextMessage', error=$err" } }
                .map { jsonMessage -> Observable.just(jsonMessage) }
                .getOrDefault(Observable.empty())
        }
        .share()

    //
    //

    private val hassioAuthenticationFinishedCompl = Completable
        .concatArray(
            Completable.fromRunnable { this.log.info { "auth: Waiting for 'aut_required' message..." } },
            this.webSocketJsonMessageObs.completeOnFirstOccurrenceMatching { it.string("type") == "auth_required" },
            Completable.fromRunnable {
                this.log.info { "auth: Sending 'auth' message with access token, then wait for 'auth_ok'..." }
                this.webSocket.send(json { obj("type" to "auth", "access_token" to accessToken) }.toJsonString())
            },
            this.webSocketJsonMessageObs.completeOnFirstOccurrenceMatching { it.string("type") == "auth_ok" },
            Completable.fromRunnable { this.log.info { "auth: Got 'auth_ok', now the fun starts =)" } }
        )
        .subscribeOn(this.connectorScheduler)
        .cache()

    override fun connect(): Completable = this.hassioAuthenticationFinishedCompl

    //
    //

    override fun observeEntityStateChanges(): Observable<EntityState> {
        this.log.info { "observeEntityStateChanges" }

        return this.hassioAuthenticationFinishedCompl
            .observeOn(this.connectorScheduler)
            .doOnSubscribe { this.log.info { "observeEntityStateChanges: Building up state transfer chain... " } }
            .andThen(Observable.concatArray(this.connectInitialStateFetch(), this.connectStateEventSubscription()))
            .doOnError { this.log.error { "observeEntityStateChanges: $it" } }
            .share()
    }

    private fun connectInitialStateFetch(): Observable<EntityState> {
        this.log.info { "connectInitialStateTransfer" }

        return Single
            .fromCallable {
                val id = this.webSocketMessageId++
                this.log.info { "connectInitialStateTransfer: Sending 'get_states' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "get_states") }.toJsonString())
                id
            }
            .subscribeOn(this.connectorScheduler)
            .flatMap { id -> this.webSocketJsonMessageObs.firstOccurrenceMatching { it.long("id") == id } }
            .map { msg -> msg.array<JsonObject>("result")?.toEntityStateObjects() ?: listOf() }
            .doOnSuccess { this.log.info { "connectInitialStateTransfer: Result contains ${it.size} entity objects" } }
            .flatMapObservable { entityObjects -> Observable.fromIterable(entityObjects) }
    }

    private fun connectStateEventSubscription(): Observable<EntityState> {
        this.log.info { "connectStateEventSubscription" }

        return Single
            .fromCallable {
                val id = this.webSocketMessageId++
                this.log.info { "connectStateEventSubscription: Sending 'subscribe_events' for 'state_changed' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "subscribe_events", "event_type" to "state_changed") }.toJsonString())
                id
            }
            .subscribeOn(this.connectorScheduler)
            .flatMapObservable { id -> this.webSocketJsonMessageObs.filter { it.long("id") == id } }
            .mapNotNull { msg -> msg.obj("event")?.obj("data")?.toEntityStateObject() }
    }

    //
    //

    override fun observeEntityEvents(): Observable<EntityEvent> {
        this.log.info { "observeEntityEvents" }

        return this.hassioAuthenticationFinishedCompl
            .observeOn(this.connectorScheduler)
            .doOnSubscribe { this.log.info { "observeEntityEvents: Building up event transfer chain... " } }
            .andThen(Single.fromCallable {
                val id = this.webSocketMessageId++
                this.log.info { "observeEntityEvents: Sending 'subscribe_events' for 'deconz_event' with id=$id" }
                this.webSocket.send(json { obj("id" to id, "type" to "subscribe_events", "event_type" to "deconz_event") }.toJsonString())
                id
            })
            .flatMapObservable { id -> this.webSocketJsonMessageObs.filter { it.long("id") == id } }
            .mapNotNull { msg -> msg.obj("event")?.obj("data")?.toEntityEventObject() }
            .doOnError { this.log.error { "observeEntityEvents: $it" } }
            .share()
    }

    //
    //

    private var serviceCallCounter = 1L

    override fun callServiceWith(message: ServiceMessage): Completable {
        this.log.info { "callServiceWith: message=$message" }

        return Completable
            .concatArray(
                this.hassioAuthenticationFinishedCompl,
                Completable.defer { this.doCallServiceWith(this.serviceCallCounter++, message) }
            )
            .subscribeOn(this.connectorScheduler)
    }

    private fun doCallServiceWith(callId: Long, message: ServiceMessage): Completable {

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

        val callIdLog = KotlinLogging.logger("connector.WebSocket.doCallServiceWith[$callId]")
        val commandObjects = message.toJsonCommandMessages().toList()
        val commandCount = commandObjects.size

        callIdLog.info { "Processing $commandCount commands for message=$message" }
        return commandObjects
            .withIndex()
            .map { cmdTuple ->
                Single.fromCallable { this.webSocketMessageId++ }
                    .doOnSuccess { commandId ->
                        val commandJsonString = JsonObject(cmdTuple.value).also { it["id"] = commandId }.toJsonString()

                        callIdLog.debug { "${cmdTuple.index + 1} / $commandCount: Sending $commandJsonString" }
                        this.webSocket.send(commandJsonString)
                        callIdLog.debug { "${cmdTuple.index + 1} / $commandCount: Waiting for result object..." }
                    }
                    .flatMap { commandId ->
                        this.webSocketJsonMessageObs
                            .doOnNext { callIdLog.debug { "${cmdTuple.index + 1} / $commandCount: Possible result object: ${it.toJsonString()}" } }
                            .firstOccurrenceMatching { it.long("id") == commandId }
                    }
                    .flatMapCompletable { resultObj ->
                        callIdLog.debug { "${cmdTuple.index + 1} / $commandCount: resultObj=${resultObj.toJsonString()}" }
                        resultObj
                            .takeIf { it.boolean("success") == false }
                            ?.let { Completable.error { HassioConnector.CallServiceFailedException(it.obj("error")?.toJsonString()) } }
                            ?: Completable.complete()
                    }
            }
            .let { commandSendAndWaitCompletables -> Completable.concat(commandSendAndWaitCompletables) }
            .doOnError { error -> callIdLog.error { "$error" } }
            .doOnComplete { callIdLog.info { "Successfully processed all $commandCount commands =)" } }
            .subscribeOn(this.connectorScheduler)
    }

    //
    //

    override fun shutdown(): Completable {
        this.log.info { "shutdown" }

        return Completable
            .concatArray(
                Completable.fromRunnable { this.webSocket.close(STATUS_NORMAL, "shutdown") },
                this.webSocketStateSub.completeOnFirstOccurrenceOf(WebSocketState.CLOSED)
            )
            .onErrorComplete()
            .doOnComplete { this.connectorScheduler.shutdown() }
            .subscribeOn(this.connectorScheduler)
    }
}
