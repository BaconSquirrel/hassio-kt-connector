package de.baconsquirrel.hassiokt.connector.internal

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single

internal fun <T : Any> Observable<T>.completeOnFirstOccurrenceOf(completionValue: T): Completable =
    this.completeOnFirstOccurrenceMatching(completionValue::equals)

internal fun <T : Any> Observable<T>.completeOnFirstOccurrenceMatching(predicateBlock: (T) -> Boolean): Completable =
    this.firstOccurrenceMatching(predicateBlock::invoke).ignoreElement()

internal fun <T : Any> Observable<T>.firstOccurrenceMatching(predicateBlock: (T) -> Boolean): Single<T> =
    this.filter(predicateBlock::invoke).firstOrError()

//
//

internal fun <I : Any, O : Any> Observable<I>.mapNotNull(block: (I) -> O?): Observable<O> =
    this.flatMap { input -> block(input)?.let { mapped -> Observable.just(mapped) } ?: Observable.empty() }
