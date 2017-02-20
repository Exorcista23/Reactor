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

package reactor.core.publisher;

import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.Exceptions;

/**
 * Splits the source sequence into potentially overlapping windowEnds controlled by items
 * of a start Publisher and end Publishers derived from the start values.
 *
 * @param <T> the source value type
 * @param <U> the window starter value type
 * @param <V> the window end value type (irrelevant)
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxWindowStartEnd<T, U, V> extends FluxSource<T, Flux<T>> {

	final Publisher<U> start;

	final Function<? super U, ? extends Publisher<V>> end;

	final Supplier<? extends Queue<Object>> drainQueueSupplier;

	final Supplier<? extends Queue<T>> processorQueueSupplier;

	FluxWindowStartEnd(Flux<? extends T> source,
			Publisher<U> start,
			Function<? super U, ? extends Publisher<V>> end,
			Supplier<? extends Queue<Object>> drainQueueSupplier,
			Supplier<? extends Queue<T>> processorQueueSupplier) {
		super(source);
		this.start = Objects.requireNonNull(start, "start");
		this.end = Objects.requireNonNull(end, "end");
		this.drainQueueSupplier =
				Objects.requireNonNull(drainQueueSupplier, "drainQueueSupplier");
		this.processorQueueSupplier =
				Objects.requireNonNull(processorQueueSupplier, "processorQueueSupplier");
	}

	@Override
	public long getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super Flux<T>> s) {

		Queue<Object> q = drainQueueSupplier.get();

		WindowStartEndMainSubscriber<T, U, V> main =
				new WindowStartEndMainSubscriber<>(s, q, end, processorQueueSupplier);

		s.onSubscribe(main);

		start.subscribe(main.starter);

		source.subscribe(main);
	}

	static final class WindowStartEndMainSubscriber<T, U, V>
			implements Subscriber<T>, InnerOperator<T, Flux<T>>, Disposable {

		final Subscriber<? super Flux<T>> actual;

		final Queue<Object> queue;

		final WindowStartEndStarter<T, U, V> starter;

		final Function<? super U, ? extends Publisher<V>> end;

		final Supplier<? extends Queue<T>> processorQueueSupplier;

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<WindowStartEndMainSubscriber> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(WindowStartEndMainSubscriber.class,
						"requested");

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowStartEndMainSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(WindowStartEndMainSubscriber.class,
						"wip");

		volatile boolean cancelled;

		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<WindowStartEndMainSubscriber, Subscription>
				S =
				AtomicReferenceFieldUpdater.newUpdater(WindowStartEndMainSubscriber.class,
						Subscription.class,
						"s");

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowStartEndMainSubscriber> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(WindowStartEndMainSubscriber.class,
						"once");

		volatile int open;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<WindowStartEndMainSubscriber> OPEN =
				AtomicIntegerFieldUpdater.newUpdater(WindowStartEndMainSubscriber.class,
						"open");

		Set<WindowStartEndEnder<T, V>> windowEnds;

		Set<UnicastProcessor<T>> windows;

		volatile boolean mainDone;

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<WindowStartEndMainSubscriber, Throwable>
				ERROR = AtomicReferenceFieldUpdater.newUpdater(
				WindowStartEndMainSubscriber.class,
				Throwable.class,
				"error");

		WindowStartEndMainSubscriber(Subscriber<? super Flux<T>> actual,
				Queue<Object> queue,
				Function<? super U, ? extends Publisher<V>> end,
				Supplier<? extends Queue<T>> processorQueueSupplier) {
			this.actual = actual;
			this.queue = queue;
			this.starter = new WindowStartEndStarter<>(this);
			this.end = end;
			this.windowEnds = new HashSet<>();
			this.windows = new HashSet<>();
			this.processorQueueSupplier = processorQueueSupplier;
			this.open = 1;
		}

		@Override
		public Object scan(Attr key) {
			switch(key){
				case TERMINATED:
					return mainDone;
			}
			return InnerOperator.super.scan(key);
		}

		@Override
		public Subscriber<? super Flux<T>> actual() {
			return actual;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			synchronized (this) {
				queue.offer(t);
			}
			drain();
		}

		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				drain();
			}
			else {
				Operators.onErrorDropped(t);
			}
		}

		@Override
		public void onComplete() {
			closeMain();
			starter.cancel();
			mainDone = true;

			drain();
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.getAndAddCap(REQUESTED, this, n);
			}
		}

		@Override
		public void cancel() {
			cancelled = true;

			starter.cancel();
			closeMain();
		}

		void starterNext(U u) {
			NewWindow<U> nw = new NewWindow<>(u);
			synchronized (this) {
				queue.offer(nw);
			}
			drain();
		}

		void starterError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				drain();
			}
			else {
				Operators.onErrorDropped(e);
			}
		}

		void starterComplete() {
			closeMain();
			drain();
		}

		void endSignal(WindowStartEndEnder<T, V> end) {
			remove(end);
			synchronized (this) {
				queue.offer(end);
			}
			drain();
		}

		void endError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				drain();
			}
			else {
				Operators.onErrorDropped(e);
			}
		}

		void closeMain() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				dispose();
			}
		}

		@Override
		public void dispose() {
			if (OPEN.decrementAndGet(this) == 0) {
				Operators.terminate(S, this);
			}
		}

		@Override
		public boolean isDisposed() {
			return s == Operators.cancelledSubscription() || mainDone;
		}

		boolean add(WindowStartEndEnder<T, V> ender) {
			synchronized (starter) {
				Set<WindowStartEndEnder<T, V>> set = windowEnds;
				if (set != null) {
					set.add(ender);
					return true;
				}
			}
			ender.cancel();
			return false;
		}

		void remove(WindowStartEndEnder<T, V> ender) {
			synchronized (starter) {
				Set<WindowStartEndEnder<T, V>> set = windowEnds;
				if (set != null) {
					set.remove(ender);
				}
			}
		}

		void removeAll() {
			Set<WindowStartEndEnder<T, V>> set;
			synchronized (starter) {
				set = windowEnds;
				if (set == null) {
					return;
				}
				windowEnds = null;
			}

			for (Subscription s : set) {
				s.cancel();
			}
		}

		void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			final Subscriber<? super UnicastProcessor<T>> a = actual;
			final Queue<Object> q = queue;

			int missed = 1;

			for (; ; ) {

				for (; ; ) {
					Throwable e = error;
					if (e != null) {
						e = Exceptions.terminate(ERROR, this);
						if (e != Exceptions.TERMINATED) {
							Operators.terminate(S, this);
							starter.cancel();
							removeAll();

							for (UnicastProcessor<T> w : windows) {
								w.onError(e);
							}
							windows = null;

							q.clear();

							a.onError(e);
						}

						return;
					}

					if (mainDone || open == 0) {
						removeAll();

						for (UnicastProcessor<T> w : windows) {
							w.onComplete();
						}
						windows = null;

						a.onComplete();
						return;
					}

					Object o = q.poll();

					if (o == null) {
						break;
					}

					if (o instanceof NewWindow) {
						if (!cancelled && open != 0 && !mainDone) {
							@SuppressWarnings("unchecked") NewWindow<U> newWindow =
									(NewWindow<U>) o;

							Queue<T> pq = processorQueueSupplier.get();

							Publisher<V> p;

							try {
								p = Objects.requireNonNull(end.apply(newWindow.value),
										"The end returned a null publisher");
							}
							catch (Throwable ex) {
								Exceptions.addThrowable(ERROR,
										this,
										Operators.onOperatorError(s,
												ex,
												newWindow.value));
								continue;
							}

							OPEN.getAndIncrement(this);

							UnicastProcessor<T> w = new UnicastProcessor<>(pq, this);

							WindowStartEndEnder<T, V> end =
									new WindowStartEndEnder<>(this, w);

							windows.add(w);

							if (add(end)) {

								long r = requested;
								if (r != 0L) {
									a.onNext(w);
									if (r != Long.MAX_VALUE) {
										REQUESTED.decrementAndGet(this);
									}
								}
								else {
									Exceptions.addThrowable(ERROR,
											this,
											Exceptions.failWithOverflow(
													"Could not emit window due to lack of requests"));
									continue;
								}

								p.subscribe(end);
							}
						}
					}
					else if (o instanceof WindowStartEndEnder) {
						@SuppressWarnings("unchecked") WindowStartEndEnder<T, V> end =
								(WindowStartEndEnder<T, V>) o;

						end.window.onComplete();
					}
					else {
						@SuppressWarnings("unchecked") T v = (T) o;

						for (UnicastProcessor<T> w : windows) {
							w.onNext(v);
						}
					}
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
	}

	static final class WindowStartEndStarter<T, U, V>
			extends Operators.DeferredSubscription implements Subscriber<U> {

		final WindowStartEndMainSubscriber<T, U, V> main;

		public WindowStartEndStarter(WindowStartEndMainSubscriber<T, U, V> main) {
			this.main = main;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(U t) {
			main.starterNext(t);
		}

		@Override
		public void onError(Throwable t) {
			main.starterError(t);
		}

		@Override
		public void onComplete() {
			main.starterComplete();
		}

	}

	static final class WindowStartEndEnder<T, V> extends Operators.DeferredSubscription
			implements Subscriber<V> {

		final WindowStartEndMainSubscriber<T, ?, V> main;

		final UnicastProcessor<T> window;

		public WindowStartEndEnder(WindowStartEndMainSubscriber<T, ?, V> main,
				UnicastProcessor<T> window) {
			this.main = main;
			this.window = window;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(V t) {
			cancel();

			main.endSignal(this);
		}

		@Override
		public void onError(Throwable t) {
			main.endError(t);
		}

		@Override
		public void onComplete() {
			main.endSignal(this);
		}

	}

	static final class NewWindow<U> {

		final U value;

		public NewWindow(U value) {
			this.value = value;
		}
	}
}
