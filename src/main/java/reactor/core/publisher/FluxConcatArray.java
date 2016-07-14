/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
package reactor.core.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.flow.MultiReceiver;
import reactor.core.subscriber.MultiSubscriptionSubscriber;
import reactor.core.subscriber.SubscriptionHelper;
import reactor.core.util.Exceptions;

/**
 * Concatenates a fixed array of Publishers' values.
 *
 * @param <T> the value type
 */

/**
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxConcatArray<T> 
extends Flux<T>
		implements MultiReceiver {

	final Publisher<? extends T>[] array;
	
	final boolean delayError;

	@SafeVarargs
	public FluxConcatArray(boolean delayError, Publisher<? extends T>... array) {
		this.array = Objects.requireNonNull(array, "array");
		this.delayError = delayError;
	}

	@Override
	public Iterator<?> upstreams() {
		return Arrays.asList(array).iterator();
	}

	@Override
	public long upstreamCount() {
		return array.length;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		Publisher<? extends T>[] a = array;

		if (a.length == 0) {
			SubscriptionHelper.complete(s);
			return;
		}
		if (a.length == 1) {
			Publisher<? extends T> p = a[0];

			if (p == null) {
				SubscriptionHelper.error(s, new NullPointerException("The single source Publisher is null"));
			} else {
				p.subscribe(s);
			}
			return;
		}

		if (delayError) {
			ConcatArrayDelayErrorSubscriber<T> parent = new ConcatArrayDelayErrorSubscriber<>(s, a);

			s.onSubscribe(parent);

			if (!parent.isCancelled()) {
				parent.onComplete();
			}
			return;
		}
		ConcatArraySubscriber<T> parent = new ConcatArraySubscriber<>(s, a);

		s.onSubscribe(parent);

		if (!parent.isCancelled()) {
			parent.onComplete();
		}
	}

	/**
	 * Returns a new instance which has the additional source to be merged together with
	 * the current array of sources.
	 * <p>
	 * This operation doesn't change the current FluxMerge instance.
	 * 
	 * @param source the new source to merge with the others
	 * @return the new FluxConcatArray instance
	 */
	public FluxConcatArray<T> concatAdditionalSourceLast(Publisher<? extends T> source) {
		int n = array.length;
		@SuppressWarnings("unchecked")
		Publisher<? extends T>[] newArray = new Publisher[n + 1];
		System.arraycopy(array, 0, newArray, 0, n);
		newArray[n] = source;
		
		return new FluxConcatArray<>(delayError, newArray);
	}

	/**
	 * Returns a new instance which has the additional first source to be concatenated together with
	 * the current array of sources.
	 * <p>
	 * This operation doesn't change the current FluxConcatArray instance.
	 * 
	 * @param source the new source to merge with the others
	 * @return the new FluxConcatArray instance
	 */
	public FluxConcatArray<T> concatAdditionalSourceFirst(Publisher<? extends T> source) {
		int n = array.length;
		@SuppressWarnings("unchecked")
		Publisher<? extends T>[] newArray = new Publisher[n + 1];
		System.arraycopy(array, 0, newArray, 1, n);
		newArray[0] = source;
		
		return new FluxConcatArray<>(delayError, newArray);
	}

	
	static final class ConcatArraySubscriber<T>
			extends MultiSubscriptionSubscriber<T, T> {

		final Publisher<? extends T>[] sources;

		int index;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatArraySubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(ConcatArraySubscriber.class, "wip");

		long produced;

		public ConcatArraySubscriber(Subscriber<? super T> actual, Publisher<? extends T>[] sources) {
			super(actual);
			this.sources = sources;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}

		@Override
		public void onComplete() {
			if (WIP.getAndIncrement(this) == 0) {
				Publisher<? extends T>[] a = sources;
				do {

					if (isCancelled()) {
						return;
					}

					int i = index;
					if (i == a.length) {
						subscriber.onComplete();
						return;
					}

					Publisher<? extends T> p = a[i];

					if (p == null) {
						subscriber.onError(new NullPointerException("The " + i + "th source Publisher is null"));
						return;
					}

					long c = produced;
					if (c != 0L) {
						produced = 0L;
						produced(c);
					}
					p.subscribe(this);

					if (isCancelled()) {
						return;
					}

					index = ++i;
				} while (WIP.decrementAndGet(this) != 0);
			}

		}
	}

	static final class ConcatArrayDelayErrorSubscriber<T>
	extends MultiSubscriptionSubscriber<T, T> {

		final Publisher<? extends T>[] sources;

		int index;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatArrayDelayErrorSubscriber> WIP =
		AtomicIntegerFieldUpdater.newUpdater(ConcatArrayDelayErrorSubscriber.class, "wip");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ConcatArrayDelayErrorSubscriber, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ConcatArrayDelayErrorSubscriber.class, Throwable.class, "error");
		
		long produced;

		public ConcatArrayDelayErrorSubscriber(Subscriber<? super T> actual, Publisher<? extends T>[] sources) {
			super(actual);
			this.sources = sources;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}
		
		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				onComplete();
			} else {
				Exceptions.onErrorDropped(t);
			}
		}

		@Override
		public void onComplete() {
			if (WIP.getAndIncrement(this) == 0) {
				Publisher<? extends T>[] a = sources;
				do {

					if (isCancelled()) {
						return;
					}

					int i = index;
					if (i == a.length) {
						Throwable e = Exceptions.terminate(ERROR, this);
						if (e != null) {
							subscriber.onError(e);
						} else {
							subscriber.onComplete();
						}
						return;
					}

					Publisher<? extends T> p = a[i];

					if (p == null) {
						subscriber.onError(new NullPointerException("The " + i + "th source Publisher is null"));
						return;
					}

					long c = produced;
					if (c != 0L) {
						produced = 0L;
						produced(c);
					}
					p.subscribe(this);

					if (isCancelled()) {
						return;
					}

					index = ++i;
				} while (WIP.decrementAndGet(this) != 0);
			}

		}
	}

}
