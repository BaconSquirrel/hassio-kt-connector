package de.baconsquirrel.hassiokt.connector

import de.baconsquirrel.hassiokt.connector.general.ServiceMessage
import de.baconsquirrel.hassiokt.connector.general.EntityEvent
import de.baconsquirrel.hassiokt.connector.general.EntityState
import io.reactivex.Completable
import io.reactivex.Observable
import java.lang.RuntimeException

interface HassioConnector {
    class CallServiceFailedException(message: String?) : RuntimeException(message)

    val entityStateChanges: Observable<EntityState>
    val entityEvents: Observable<EntityEvent>

    fun start()
    fun stop() : Completable

    /**
     * Issues the necessary calls to the home assistant service that are needed to fulfill the messages intentions.
     *
     * @return A [Completable] that completes as soon as all necessary calls to home assistant were executed and reported successfully.
     *     Note that the [Completable] will raise an error of type [CallServiceFailedException] if one of the calls to home assistant were
     *     reported not successfully.
     */
    fun callServiceWith(message: ServiceMessage): Completable
}