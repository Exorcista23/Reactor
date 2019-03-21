/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable.ConditionalSubscriber;
import reactor.core.publisher.FluxUsingWhen.UsingWhenSubscriber;
import reactor.core.publisher.Operators.DeferredSubscription;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * Uses a resource, generated by a {@link Publisher} for each individual {@link Subscriber},
 * while streaming the values from a {@link Publisher} derived from the same resource.
 * Whenever the resulting sequence terminates, the relevant {@link Function} generates
 * a "cleanup" {@link Publisher} that is invoked but doesn't change the content of the
 * main sequence. Instead it just defers the termination (unless it errors, in which case
 * the error suppresses the original termination signal).
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 */
final class MonoUsingWhen<T, S> extends Mono<T> implements SourceProducer<T> {

	final Publisher<S>                                     resourceSupplier;
	final Function<? super S, ? extends Mono<? extends T>> resourceClosure;
	final Function<? super S, ? extends Publisher<?>>      asyncComplete;
	final Function<? super S, ? extends Publisher<?>>      asyncError;
	@Nullable
	final Function<? super S, ? extends Publisher<?>>      asyncCancel;

	MonoUsingWhen(Publisher<S> resourceSupplier,
			Function<? super S, ? extends Mono<? extends T>> resourceClosure,
			Function<? super S, ? extends Publisher<?>> asyncComplete,
			Function<? super S, ? extends Publisher<?>> asyncError,
			@Nullable Function<? super S, ? extends Publisher<?>> asyncCancel) {
		this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
		this.resourceClosure = Objects.requireNonNull(resourceClosure, "resourceClosure");
		this.asyncComplete = Objects.requireNonNull(asyncComplete, "asyncComplete");
		this.asyncError = Objects.requireNonNull(asyncError, "asyncError");
		this.asyncCancel = asyncCancel;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(CoreSubscriber<? super T> actual) {
		if (resourceSupplier instanceof Callable) {
			try {
				Callable<S> resourceCallable = (Callable<S>) resourceSupplier;
				S resource = resourceCallable.call();

				if (resource == null) {
					Operators.complete(actual);
				}
				else {
					final Mono<? extends T> p = deriveMonoFromResource(resource, resourceClosure);
					final UsingWhenSubscriber<? super T, S> subscriber = prepareSubscriberForResource(resource,
							actual,
							asyncComplete,
							asyncError,
							asyncCancel,
							null);

					p.subscribe(subscriber);
				}
			}
			catch (Throwable e) {
				Operators.error(actual, e);
			}
			return;
		}

		resourceSupplier.subscribe(new ResourceSubscriber(actual, resourceClosure,
				asyncComplete, asyncError, asyncCancel,
				resourceSupplier instanceof Mono));
	}

	@Override
	public Object scanUnsafe(Attr key) {
		return null; //no particular key to be represented, still useful in hooks
	}

	private static <RESOURCE, T> Mono<? extends T> deriveMonoFromResource(
			RESOURCE resource,
			Function<? super RESOURCE, ? extends Mono<? extends T>> resourceClosure) {

		Mono<? extends T> p;

		try {
			p = Objects.requireNonNull(resourceClosure.apply(resource),
					"The resourceClosure function returned a null value");
		}
		catch (Throwable e) {
			p = Mono.error(e);
		}

		return p;
	}

	private static <RESOURCE, T> UsingWhenSubscriber<? super T, RESOURCE> prepareSubscriberForResource(
			RESOURCE resource,
			CoreSubscriber<? super T> actual,
			Function<? super RESOURCE, ? extends Publisher<?>> asyncComplete,
			Function<? super RESOURCE, ? extends Publisher<?>> asyncError,
			@Nullable Function<? super RESOURCE, ? extends Publisher<?>> asyncCancel,
			@Nullable DeferredSubscription arbiter) {
		if (actual instanceof ConditionalSubscriber) {
			@SuppressWarnings("unchecked") ConditionalSubscriber<? super T>
					conditionalActual =  (ConditionalSubscriber<? super T>) actual;
			return new FluxUsingWhen.UsingWhenConditionalSubscriber<>(conditionalActual,
					resource,
					asyncComplete,
					asyncError,
					asyncCancel,
					arbiter);
		}
		else {
			return new UsingWhenSubscriber<>(actual,
					resource,
					asyncComplete,
					asyncError,
					asyncCancel,
					arbiter);
		}
	}

	//needed to correctly call prepareSubscriberForResource with Mono.from conversions
	static class ResourceSubscriber<S, T> extends DeferredSubscription implements InnerConsumer<S> {

		final CoreSubscriber<? super T> actual;

		final Function<? super S, ? extends Mono<? extends T>>      resourceClosure;
		final Function<? super S, ? extends Publisher<?>>           asyncComplete;
		final Function<? super S, ? extends Publisher<?>>           asyncError;
		@Nullable
		final Function<? super S, ? extends Publisher<?>>           asyncCancel;
		final boolean                                               isMonoSource;

		Subscription        resourceSubscription;
		boolean             resourceProvided;

		UsingWhenSubscriber<? super T, S> closureSubscriber;

		ResourceSubscriber(CoreSubscriber<? super T> actual,
				Function<? super S, ? extends Mono<? extends T>> resourceClosure,
				Function<? super S, ? extends Publisher<?>> asyncComplete,
				Function<? super S, ? extends Publisher<?>> asyncError,
				@Nullable Function<? super S, ? extends Publisher<?>> asyncCancel,
				boolean isMonoSource) {
			this.actual = Objects.requireNonNull(actual, "actual");
			this.resourceClosure = Objects.requireNonNull(resourceClosure, "resourceClosure");
			this.asyncComplete = Objects.requireNonNull(asyncComplete, "asyncComplete");
			this.asyncError = Objects.requireNonNull(asyncError, "asyncError");
			this.asyncCancel = asyncCancel;
			this.isMonoSource = isMonoSource;
		}

		@Override
		public Context currentContext() {
			return actual.currentContext();
		}

		@Override
		public void onNext(S resource) {
			if (resourceProvided) {
				Operators.onNextDropped(resource, actual.currentContext());
				return;
			}
			resourceProvided = true;

			final Mono<? extends T> p = deriveMonoFromResource(resource, resourceClosure);

			this.closureSubscriber =
					prepareSubscriberForResource(resource,
							this.actual,
							this.asyncComplete,
							this.asyncError,
							this.asyncCancel,
							this);

			p.subscribe(closureSubscriber);

			if (!isMonoSource) {
				resourceSubscription.cancel();
			}
		}

		@Override
		public void onError(Throwable throwable) {
			if (resourceProvided) {
				Operators.onErrorDropped(throwable, actual.currentContext());
				return;
			}
			//even if no resource provided, actual.onSubscribe has been called
			//let's immediately fail actual
			actual.onError(throwable);
		}

		@Override
		public void onComplete() {
			if (resourceProvided) {
				return;
			}
			//even if no resource provided, actual.onSubscribe has been called
			//let's immediately complete actual
			actual.onComplete();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.resourceSubscription, s)) {
				this.resourceSubscription = s;
				actual.onSubscribe(this);
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void cancel() {
			if (!resourceProvided) {
				resourceSubscription.cancel();
				super.cancel();
			}
			else {
				Operators.terminate(S, this);
				if (closureSubscriber != null) {
					closureSubscriber.cancel();
				}
			}
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return resourceSubscription;
			if (key == Attr.ACTUAL) return actual;
			if (key == Attr.PREFETCH) return Integer.MAX_VALUE;
			if (key == Attr.TERMINATED) return resourceProvided;

			return null;
		}
	}

}
