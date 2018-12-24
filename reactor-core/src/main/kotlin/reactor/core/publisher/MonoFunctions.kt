/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher

import org.reactivestreams.Publisher


/**
 * Aggregates the given void [Publisher]s into a new void [Mono].
 * An alias for a corresponding [Mono.when] to avoid use of `when`, which is a keyword in Kotlin.
 *
 * @author DoHyung Kim
 * @author Sebastien Deleuze
 * @since 3.1
 */
fun whenComplete(vararg sources: Publisher<*>): Mono<Void> = MonoBridges.`when`(sources)

/**
 * Aggregates the given [Mono]s into a new [Mono].
 *
 * @author DoHyung Kim
 * @since 3.1
 */
@Suppress("UNCHECKED_CAST")
fun <R> zip(vararg monos: Mono<*>, combinator: (Array<*>) -> R): Mono<R> =
        MonoBridges.zip(combinator, monos)