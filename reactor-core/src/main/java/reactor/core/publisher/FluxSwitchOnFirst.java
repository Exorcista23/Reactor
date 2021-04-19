/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.util.annotation.Nullable;

/**
 * @param <T>
 * @param <R>
 * @author Oleh Dokuka
 */
final class FluxSwitchOnFirst<T, R> extends InternalFluxOperator<T, R> {

	final BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer;
	final boolean cancelSourceOnComplete;

	FluxSwitchOnFirst(Flux<? extends T> source,
			BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer,
			boolean cancelSourceOnComplete) {
		super(source);
		this.transformer = Objects.requireNonNull(transformer, "transformer");
		this.cancelSourceOnComplete = cancelSourceOnComplete;
	}

	@Override
	public int getPrefetch() {
		return 1;
	}

	@Override
	@SuppressWarnings("unchecked")
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super R> actual) {
		if (actual instanceof Fuseable.ConditionalSubscriber) {
			return new SwitchOnFirstConditionalMain<>((Fuseable.ConditionalSubscriber<? super R>) actual,
					transformer,
					cancelSourceOnComplete);
		}
		return new SwitchOnFirstMain<>(actual, transformer, cancelSourceOnComplete);
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) {
			return Attr.RunStyle.SYNC;
		}
		return super.scanUnsafe(key);
	}

	static final int HAS_FIRST_VALUE_RECEIVED_FLAG    =
			0b0000_0000_0000_0000_0000_0000_0000_0001;
	static final int HAS_INBOUND_SUBSCRIBED_ONCE_FLAG =
			0b0000_0000_0000_0000_0000_0000_0000_0010;
	static final int HAS_INBOUND_SUBSCRIBER_SET_FLAG =
			0b0000_0000_0000_0000_0000_0000_0000_0100;
	static final int HAS_INBOUND_REQUESTED_ONCE_FLAG  =
			0b0000_0000_0000_0000_0000_0000_0000_1000;
	static final int HAS_FIRST_VALUE_SENT_FLAG        =
			0b0000_0000_0000_0000_0000_0000_0001_0000;
	static final int HAS_INBOUND_CANCELLED_FLAG       =
			0b0000_0000_0000_0000_0000_0000_0010_0000;
	static final int HAS_INBOUND_TERMINATED_FLAG      =
			0b0000_0000_0000_0000_0000_0000_0100_0000;

	static final int HAS_OUTBOUND_SUBSCRIBED_FLAG =
			0b0000_0000_0000_0000_0000_0000_1000_0000;
	static final int HAS_OUTBOUND_CANCELLED_FLAG  =
			0b0000_0000_0000_0000_0000_0001_0000_0000;
	static final int HAS_OUTBOUND_TERMINATED_FLAG =
			0b0000_0000_0000_0000_0000_0010_0000_0000;

	/**
	 * Adds a flag which indicate that the first inbound onNext signal has already been
	 * received. Fails if inbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markFirstValueReceived(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_FIRST_VALUE_RECEIVED_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the inbound has already been subscribed once.
	 * Fails if inbound has already been subscribed once.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundSubscribedOnce(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundSubscribedOnce(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_SUBSCRIBED_ONCE_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the inbound subscriber has already been set.
	 * Fails if inbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundSubscriberSet(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_SUBSCRIBER_SET_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the inbound has already been requested once.
	 * Fails if inbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundRequestedOnce(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_REQUESTED_ONCE_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the first onNext value has been successfully
	 * delivered. Fails if inbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markFirstValueSent(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_FIRST_VALUE_SENT_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the inbound has already been terminated with
	 * onComplete or onError. Fails if inbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundTerminated(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_TERMINATED_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the inbound has already been cancelled. Fails if
	 * inbound is cancelled or terminated.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundCancelled(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundTerminated(state) || hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_CANCELLED_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds flags which indicate that the inbound has cancelled upstream and errored
	 * downstream. Fails if either inbound is cancelled or terminated.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markInboundCancelledAndErrored(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasInboundTerminated(state) || hasInboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_INBOUND_CANCELLED_FLAG | HAS_INBOUND_TERMINATED_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the outbound has received subscription. Fails if
	 * outbound is cancelled.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markOutboundSubscribed(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasOutboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_OUTBOUND_SUBSCRIBED_FLAG)) {
				return state;
			}
		}
	}


	/**
	 * Adds a flag which indicate that the outbound has already been
	 * terminated with onComplete or onError. Fails if outbound is cancelled or terminated.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markOutboundTerminated(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasOutboundCancelled(state) || hasOutboundTerminated(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_OUTBOUND_TERMINATED_FLAG)) {
				return state;
			}
		}
	}

	/**
	 * Adds a flag which indicate that the outbound has already been cancelled. Fails
	 * if outbound is cancelled or terminated.
	 *
	 * @return previous observed state
	 */
	static <T, R> long markOutboundCancelled(AbstractSwitchOnFirstMain<T, R> instance) {
		for (;;) {
			final int state = instance.state;

			if (hasOutboundTerminated(state) || hasOutboundCancelled(state)) {
				return state;
			}

			if (AbstractSwitchOnFirstMain.STATE.compareAndSet(instance, state, state | HAS_OUTBOUND_CANCELLED_FLAG)) {
				return state;
			}
		}
	}

	static boolean hasInboundCancelled(long state) {
		return (state & HAS_INBOUND_CANCELLED_FLAG) == HAS_INBOUND_CANCELLED_FLAG;
	}

	static boolean hasInboundTerminated(long state) {
		return (state & HAS_INBOUND_TERMINATED_FLAG) == HAS_INBOUND_TERMINATED_FLAG;
	}

	static boolean hasFirstValueReceived(long state) {
		return (state & HAS_FIRST_VALUE_RECEIVED_FLAG) == HAS_FIRST_VALUE_RECEIVED_FLAG;
	}

	static boolean hasFirstValueSent(long state) {
		return (state & HAS_FIRST_VALUE_SENT_FLAG) == HAS_FIRST_VALUE_SENT_FLAG;
	}

	static boolean hasInboundSubscribedOnce(long state) {
		return (state & HAS_INBOUND_SUBSCRIBED_ONCE_FLAG) == HAS_INBOUND_SUBSCRIBED_ONCE_FLAG;
	}

	static boolean hasInboundSubscriberSet(long state) {
		return (state & HAS_INBOUND_SUBSCRIBER_SET_FLAG) == HAS_INBOUND_SUBSCRIBER_SET_FLAG;
	}

	static boolean hasInboundRequestedOnce(long state) {
		return (state & HAS_INBOUND_REQUESTED_ONCE_FLAG) == HAS_INBOUND_REQUESTED_ONCE_FLAG;
	}

	static boolean hasOutboundSubscribed(long state) {
		return (state & HAS_OUTBOUND_SUBSCRIBED_FLAG) == HAS_OUTBOUND_SUBSCRIBED_FLAG;
	}

	static boolean hasOutboundCancelled(long state) {
		return (state & HAS_OUTBOUND_CANCELLED_FLAG) == HAS_OUTBOUND_CANCELLED_FLAG;
	}

	static boolean hasOutboundTerminated(long state) {
		return (state & HAS_OUTBOUND_TERMINATED_FLAG) == HAS_OUTBOUND_TERMINATED_FLAG;
	}

	static abstract class AbstractSwitchOnFirstMain<T, R> extends Flux<T>
			implements InnerOperator<T, R> {

		final ControlSubscriber<? super R>                                     outboundSubscriber;
		final BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer;

		Subscription s;

		boolean isInboundRequestedOnce;
		boolean isFirstOnNextValueReceivedOnce;
		T       firstValue;

		Throwable throwable;
		boolean   done;

		CoreSubscriber<? super T> inboundSubscriber;

		volatile int state;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<AbstractSwitchOnFirstMain> STATE =
				AtomicIntegerFieldUpdater.newUpdater(AbstractSwitchOnFirstMain.class, "state");

		@SuppressWarnings("unchecked")
		AbstractSwitchOnFirstMain(CoreSubscriber<? super R> outboundSubscriber,
				BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer,
				boolean cancelSourceOnComplete) {
			this.outboundSubscriber = outboundSubscriber instanceof Fuseable.ConditionalSubscriber ?
					new SwitchOnFirstConditionalControlSubscriber<>(this,
							(Fuseable.ConditionalSubscriber<R>) outboundSubscriber,
							cancelSourceOnComplete) :
					new SwitchOnFirstControlSubscriber<>(this, outboundSubscriber,
							cancelSourceOnComplete);
			this.transformer = transformer;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			final boolean isCancelled = this.inboundSubscriber == Operators.EMPTY_SUBSCRIBER;

			if (key == Attr.CANCELLED) {
				return isCancelled && !this.done;
			}
			if (key == Attr.TERMINATED) {
				return this.done || isCancelled;
			}
			if (key == Attr.RUN_STYLE) {
				return Attr.RunStyle.SYNC;
			}

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public final CoreSubscriber<? super R> actual() {
			return this.outboundSubscriber;
		}

		@Override
		public final void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				this.outboundSubscriber.sendSubscription();
				if (!hasInboundCancelled(this.state)) {
					s.request(1);
				}
			}
		}

		@Override
		public final void onNext(T t) {
			if (this.done) {
				Operators.onNextDropped(t, currentContext());
				return;
			}

			final T f = this.firstValue;
			if (f == null) {
				this.firstValue = t;

				long previousState = markFirstValueReceived(this);
				if (hasInboundCancelled(previousState)) {
					Operators.onDiscard(t, this.outboundSubscriber.currentContext());
					return;
				}

				final Publisher<? extends R> outboundPublisher;
				final CoreSubscriber<? super R> o = this.outboundSubscriber;

				try {
					final Signal<T> signal = Signal.next(t, o.currentContext());
					outboundPublisher = Objects.requireNonNull(this.transformer.apply(signal, this), "The transformer returned a null value");
				}
				catch (Throwable e) {
					this.done = true;

					previousState = markInboundTerminated(this);
					if (hasInboundCancelled(previousState) || hasInboundTerminated(previousState)) {
						Operators.onErrorDropped(e, this.outboundSubscriber.currentContext());
						return;
					}

					o.onError(Operators.onOperatorError(this.s, e, t, o.currentContext()));
					return;
				}

				outboundPublisher.subscribe(o);
				return;
			}

			synchronized (this) {
				this.inboundSubscriber.onNext(t);
			}
		}

		@Override
		public final void onError(Throwable t) {
			if (this.done) {
				Operators.onErrorDropped(t, this.outboundSubscriber.currentContext());
				return;
			}

			this.done = true;
			this.throwable = t;

			final long previousState = markInboundTerminated(this);
			if (hasInboundCancelled(previousState) || hasInboundTerminated(previousState)) {
				Operators.onErrorDropped(t, this.outboundSubscriber.currentContext());
				return;
			}

			if (hasFirstValueSent(previousState)) {
				synchronized (this) {
					this.inboundSubscriber.onError(t);
				}
				return;
			}

			if (!hasFirstValueReceived(previousState)) {
				final Publisher<? extends R> result;
				final CoreSubscriber<? super R> o = this.outboundSubscriber;
				try {
					final Signal<T> signal = Signal.error(t, o.currentContext());
					result = Objects.requireNonNull(this.transformer.apply(signal, this), "The transformer returned a null value");
				}
				catch (Throwable e) {
					o.onError(Exceptions.addSuppressed(t, e));
					return;
				}

				result.subscribe(o);
			}
		}

		@Override
		public final void onComplete() {
			if (this.done) {
				return;
			}

			this.done = true;

			final long previousState = markInboundTerminated(this);
			if (hasInboundCancelled(previousState) || hasInboundTerminated(previousState)) {
				return;
			}

			if (hasFirstValueSent(previousState)) {
				synchronized (this) {
					this.inboundSubscriber.onComplete();
				}
				return;
			}

			if (!hasFirstValueReceived(previousState)) {
				final Publisher<? extends R> result;
				final CoreSubscriber<? super R> o = this.outboundSubscriber;

				try {
					final Signal<T> signal = Signal.complete(o.currentContext());
					result = Objects.requireNonNull(this.transformer.apply(signal, this), "The transformer returned a null value");
				}
				catch (Throwable e) {
					o.onError(e);
					return;
				}

				result.subscribe(o);
			}
		}

		@Override
		public final void cancel() {
			long previousState = markInboundCancelled(this);
			if (hasInboundCancelled(previousState) || hasInboundTerminated(previousState)) {
				return;
			}

			this.s.cancel();

			if (hasFirstValueReceived(previousState) && !hasInboundRequestedOnce(previousState)) {
				final T f = this.firstValue;
				Operators.onDiscard(f, currentContext());
			}
		}

		final void cancelAndError() {
			long previousState = markInboundCancelledAndErrored(this);
			if (hasInboundCancelled(previousState) || hasInboundTerminated(previousState)) {
				return;
			}

			this.s.cancel();

			if (hasFirstValueReceived(previousState) && !hasFirstValueSent(previousState)) {
				if (!hasInboundRequestedOnce(previousState)) {
					final T f = this.firstValue;
					Operators.onDiscard(f, currentContext());

					if (hasInboundSubscriberSet(previousState)) {
						this.inboundSubscriber.onError(new CancellationException(
								"FluxSwitchOnFirst has already been cancelled"));
					}
				}
				return;
			}

			if (hasInboundSubscriberSet(previousState)) {
				synchronized (this) {
					this.inboundSubscriber.onError(new CancellationException("FluxSwitchOnFirst has already been cancelled"));
				}
			}
		}

		@Override
		public final void request(long n) {
			if (Operators.validate(n)) {
				// This is a sanity check to avoid extra volatile read in the request
				// context
				if (!this.isInboundRequestedOnce) {
					this.isInboundRequestedOnce = true;

					final T first = this.firstValue;
					if (first != null) {
						final long previousState = markInboundRequestedOnce(this);
						if (hasInboundCancelled(previousState)) {
							return;
						}

						final boolean wasDelivered = sendFirst(first);
						if (wasDelivered) {
							if (n != Long.MAX_VALUE) {
								if (--n > 0) {
									this.s.request(n);
								}
								return;
							}
						}
					}
				}

				this.s.request(n);
			}
		}

		@Override
		public final void subscribe(CoreSubscriber<? super T> inboundSubscriber) {
			long previousState = markInboundSubscribedOnce(this);
			if (hasInboundSubscribedOnce(previousState)) {
				Operators.error(inboundSubscriber, new IllegalStateException("FluxSwitchOnFirst allows only one Subscriber"));
				return;
			}

			if (hasInboundCancelled(previousState)) {
				Operators.error(inboundSubscriber, new CancellationException("FluxSwitchOnFirst has already been cancelled"));
				return;
			}

			if (!hasFirstValueReceived(previousState)) {
				final Throwable t = this.throwable;
				if (t != null) {
					Operators.error(inboundSubscriber, t);
				}
				else {
					Operators.complete(inboundSubscriber);
				}
				return;
			}

			this.inboundSubscriber = convert(inboundSubscriber);

			inboundSubscriber.onSubscribe(this);

			previousState = markInboundSubscriberSet(this);
			if (hasInboundCancelled(previousState) && hasInboundTerminated(previousState)) {
				inboundSubscriber.onError(new CancellationException("FluxSwitchOnFirst has already been cancelled"));
			}
		}

		abstract CoreSubscriber<? super T> convert(CoreSubscriber<? super T> inboundSubscriber);


		boolean sendFirst(T firstValue) {
			final CoreSubscriber<? super T> a = this.inboundSubscriber;

			final boolean sent = tryDirectSend(a, firstValue);

			final long previousState = markFirstValueSent(this);
			if (hasInboundCancelled(previousState)) {
				if (hasInboundTerminated(previousState)) {
					a.onError(new CancellationException("FluxSwitchOnFirst has already been cancelled"));
				}
				return sent;
			}

			if (hasInboundTerminated(previousState)) {
				Throwable t = this.throwable;
				if (t != null) {
					a.onError(t);
				}
				else {
					a.onComplete();
				}
			}

			return sent;
		}

		abstract boolean tryDirectSend(CoreSubscriber<? super T> actual, T value);

	}

	static final class SwitchOnFirstMain<T, R> extends AbstractSwitchOnFirstMain<T, R> {

		SwitchOnFirstMain(CoreSubscriber<? super R> outer,
				BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer,
				boolean cancelSourceOnComplete) {
			super(outer, transformer, cancelSourceOnComplete);
		}

		@Override
		CoreSubscriber<? super T> convert(CoreSubscriber<? super T> inboundSubscriber) {
			return inboundSubscriber;
		}

		@Override
		boolean tryDirectSend(CoreSubscriber<? super T> actual, T t) {
			actual.onNext(t);
			return true;
		}
	}

	static final class SwitchOnFirstConditionalMain<T, R>
			extends AbstractSwitchOnFirstMain<T, R>
			implements Fuseable.ConditionalSubscriber<T> {

		SwitchOnFirstConditionalMain(Fuseable.ConditionalSubscriber<? super R> outer,
				BiFunction<Signal<? extends T>, Flux<T>, Publisher<? extends R>> transformer,
				boolean cancelSourceOnComplete) {
			super(outer, transformer, cancelSourceOnComplete);
		}

		@Override
		CoreSubscriber<? super T> convert(CoreSubscriber<? super T> inboundSubscriber) {
			return Operators.toConditionalSubscriber(inboundSubscriber);
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean tryOnNext(T t) {
			if (this.done) {
				Operators.onNextDropped(t, currentContext());
				return false;
			}

			final T f = this.firstValue;
			if (f == null) {
				this.firstValue = t;

				final long state = markFirstValueReceived(this);
				if (hasInboundCancelled(state)) {
					Operators.onDiscard(t, this.outboundSubscriber.currentContext());
					return true;
				}

				final Publisher<? extends R> result;
				final CoreSubscriber<? super R> o = this.outboundSubscriber;

				try {
					final Signal<T> signal = Signal.next(t, o.currentContext());
					result = Objects.requireNonNull(this.transformer.apply(signal, this),
							"The transformer returned a null value");
				}
				catch (Throwable e) {
					this.done = true;
					o.onError(Operators.onOperatorError(this.s, e, t, o.currentContext()));
					return false;
				}

				result.subscribe(o);
				return true;
			}

			synchronized (this) {
				return ((Fuseable.ConditionalSubscriber<? super T>) this.inboundSubscriber).tryOnNext(t);
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		boolean tryDirectSend(CoreSubscriber<? super T> actual, T t) {
			return ((Fuseable.ConditionalSubscriber<? super T>) actual).tryOnNext(t);
		}
	}

	static final class SwitchOnFirstControlSubscriber<T>
			extends Operators.DeferredSubscription
			implements InnerOperator<T, T>, ControlSubscriber<T> {

		final AbstractSwitchOnFirstMain<?, T> parent;
		final CoreSubscriber<? super T>       delegate;
		final boolean                         cancelSourceOnComplete;

		boolean done;

		SwitchOnFirstControlSubscriber(AbstractSwitchOnFirstMain<?, T> parent,
				CoreSubscriber<? super T> delegate,
				boolean cancelSourceOnComplete) {
			this.parent = parent;
			this.delegate = delegate;
			this.cancelSourceOnComplete = cancelSourceOnComplete;
		}

		@Override
		public void sendSubscription() {
			delegate.onSubscribe(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				long previousState = markOutboundSubscribed(this.parent);

				if (hasOutboundCancelled(previousState)) {
					s.cancel();
				}
			}
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.delegate;
		}

		@Override
		public void onNext(T t) {
			if (this.done) {
				Operators.onNextDropped(t, currentContext());
				return;
			}

			this.delegate.onNext(t);
		}

		@Override
		public void onError(Throwable throwable) {
			if (this.done) {
				Operators.onErrorDropped(throwable, currentContext());
				return;
			}

			this.done = true;

			final AbstractSwitchOnFirstMain<?, T> parent = this.parent;
			long previousState = markOutboundTerminated(parent);

			if (hasOutboundCancelled(previousState) || hasOutboundTerminated(previousState)) {
				return;
			}

			if (!hasInboundCancelled(previousState) && !hasInboundTerminated(previousState)) {
				parent.cancelAndError();
			}

			this.delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			if (this.done) {
				return;
			}

			this.done = true;

			final AbstractSwitchOnFirstMain<?, T> parent = this.parent;
			long previousState = markOutboundTerminated(parent);

			if (cancelSourceOnComplete && !hasInboundCancelled(previousState) && !hasInboundTerminated(previousState)) {
				parent.cancelAndError();
			}

			this.delegate.onComplete();
		}

		@Override
		public void cancel() {
			REQUESTED.lazySet(this, STATE_CANCELLED);

			final long previousState = markOutboundCancelled(this.parent);
			if (hasOutboundCancelled(previousState) || hasOutboundTerminated(previousState)) {
				return;
			}

			final boolean shouldCancelInbound =
					!hasInboundTerminated(previousState) && !hasInboundCancelled(
							previousState);

			if (!hasOutboundSubscribed(previousState)) {
				if (shouldCancelInbound) {
					this.parent.cancel();
				}
				return;
			}

			this.s.cancel();

			if (shouldCancelInbound) {
				this.parent.cancelAndError();
			}
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) {
				return parent;
			}
			if (key == Attr.ACTUAL) {
				return delegate;
			}
			if (key == Attr.RUN_STYLE) {
				return Attr.RunStyle.SYNC;
			}

			return null;
		}
	}

	static final class SwitchOnFirstConditionalControlSubscriber<T>
			extends Operators.DeferredSubscription
			implements InnerOperator<T, T>, ControlSubscriber<T>,
			           Fuseable.ConditionalSubscriber<T> {

		final AbstractSwitchOnFirstMain<?, T>           parent;
		final Fuseable.ConditionalSubscriber<? super T> delegate;
		final boolean                                   cancelSourceOnComplete;

		boolean done;

		SwitchOnFirstConditionalControlSubscriber(AbstractSwitchOnFirstMain<?, T> parent,
				Fuseable.ConditionalSubscriber<? super T> delegate,
				boolean cancelSourceOnComplete) {
			this.parent = parent;
			this.delegate = delegate;
			this.cancelSourceOnComplete = cancelSourceOnComplete;
		}

		@Override
		public void sendSubscription() {
			delegate.onSubscribe(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				final long previousState = markOutboundSubscribed(this.parent);

				if (hasOutboundCancelled(previousState)) {
					s.cancel();
				}
			}
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.delegate;
		}

		@Override
		public boolean tryOnNext(T t) {
			if (this.done) {
				Operators.onNextDropped(t, currentContext());
				return true;
			}

			return this.delegate.tryOnNext(t);
		}

		@Override
		public void onNext(T t) {
			if (this.done) {
				Operators.onNextDropped(t, currentContext());
				return;
			}

			this.delegate.onNext(t);
		}

		@Override
		public void onError(Throwable throwable) {
			if (this.done) {
				Operators.onErrorDropped(throwable, currentContext());
				return;
			}

			this.done = true;

			final AbstractSwitchOnFirstMain<?, T> parent = this.parent;
			long previousState = markOutboundTerminated(parent);

			if (hasOutboundCancelled(previousState) || hasOutboundTerminated(previousState)) {
				return;
			}

			if (!hasInboundCancelled(previousState) && !hasInboundTerminated(previousState)) {
				parent.cancelAndError();
			}

			this.delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			if (this.done) {
				return;
			}

			this.done = true;

			final AbstractSwitchOnFirstMain<?, T> parent = this.parent;
			long previousState = markOutboundTerminated(parent);

			if (cancelSourceOnComplete && !hasInboundCancelled(previousState) && !hasInboundTerminated(previousState)) {
				parent.cancelAndError();
			}

			this.delegate.onComplete();
		}

		@Override
		public void cancel() {
			REQUESTED.lazySet(this, STATE_CANCELLED);

			final long previousState = markOutboundCancelled(this.parent);
			if (hasOutboundCancelled(previousState) || hasOutboundTerminated(previousState)) {
				return;
			}

			final boolean shouldCancelInbound =
					!hasInboundTerminated(previousState) && !hasInboundCancelled(
							previousState);

			if (!hasOutboundSubscribed(previousState)) {
				if (shouldCancelInbound) {
					this.parent.cancel();
				}
				return;
			}

			this.s.cancel();

			if (shouldCancelInbound) {
				this.parent.cancelAndError();
			}
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) {
				return parent;
			}
			if (key == Attr.ACTUAL) {
				return delegate;
			}
			if (key == Attr.RUN_STYLE) {
				return Attr.RunStyle.SYNC;
			}

			return null;
		}
	}

	interface ControlSubscriber<T> extends CoreSubscriber<T> {

		void sendSubscription();
	}
}
