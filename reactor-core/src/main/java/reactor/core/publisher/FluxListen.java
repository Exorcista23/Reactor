/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Fuseable.ConditionalSubscriber;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.observability.SignalListener;
import reactor.util.observability.SignalListenerFactory;

/**
 * A generic per-Subscription side effect {@link Flux} that notifies a {@link SignalListener} of most events.
 *
 * @author Simon Baslé
 */
final class FluxListen<T, STATE> extends InternalFluxOperator<T, T> {

	final SignalListenerFactory<T, STATE> lifter;
	final STATE                           publisherState;

	FluxListen(Flux<? extends T> source, SignalListenerFactory<T, STATE> lifter) {
		super(source);
		this.lifter = lifter;
		this.publisherState = lifter.initializePublisherState(source);
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		//if the SequenceObserver cannot be created, all we can do is error the subscriber.
		//after it is created, in case doFirst fails we can additionally try to invoke doFinally.
		//note that if the later handler also fails, then that exception is thrown.
		SignalListener<T> signalListener;
		try {
			//TODO replace currentContext() with contextView() when available
			signalListener = lifter.createListener(source, actual.currentContext().readOnly(), publisherState);
		}
		catch (Throwable generatorError) {
			Operators.error(actual, generatorError);
			return null;
		}

		try {
			signalListener.doFirst();
		}
		catch (Throwable observerError) {
			Operators.error(actual, observerError);
			signalListener.doFinally(SignalType.ON_ERROR);
			return null;
		}

		if (actual instanceof ConditionalSubscriber) {
			//noinspection unchecked
			return new ListenConditionalSubscriber<>((ConditionalSubscriber<? super T>) actual, signalListener);
		}
		return new ListenSubscriber<>(actual, signalListener);
	}

	@Nullable
	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

		return super.scanUnsafe(key);
	}

	//TODO support onErrorContinue around listener errors
	static class ListenSubscriber<T> implements InnerOperator<T, T> {

		final CoreSubscriber<? super T> actual;
		final SignalListener<T>         listener;

		boolean done;
		Subscription s;

		ListenSubscriber(CoreSubscriber<? super T> actual, SignalListener<T> signalListener) {
			this.actual = actual;
			this.listener = signalListener;
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return InnerOperator.super.scanUnsafe(key);
		}

		/**
		 * Cancel the prepared subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)}
		 * and then terminate the downstream directly with same error (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method before the subscription was set
		 * @param toCancel the {@link Subscription} that was prepared but not sent downstream
		 */
		protected void handleListenerErrorPreSubscription(Throwable listenerError, Subscription toCancel) {
			toCancel.cancel();
			listener.handleListenerError(listenerError);
			Operators.error(actual, listenerError);
		}

		/**
		 * Cancel the active subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)}
		 * and then terminate the downstream directly with same error (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method
		 */
		protected void handleListenerErrorAndTerminate(Throwable listenerError) {
			s.cancel();
			listener.handleListenerError(listenerError);
			actual.onError(listenerError); //TODO wrap ? hooks ?
		}

		/**
		 * Cancel the active subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)},
		 * combine it with the original error and then terminate the downstream directly this combined exception
		 * (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method
		 * @param originalError the exception that was about to occur when handler was invoked
		 */
		protected void handleListenerErrorMultipleAndTerminate(Throwable listenerError, Throwable originalError) {
			s.cancel();
			listener.handleListenerError(listenerError);
			RuntimeException multiple = Exceptions.multiple(listenerError, originalError);
			actual.onError(multiple); //TODO wrap ? hooks ?
		}

		/**
		 * After the downstream is considered terminated (or cancelled), pass the listener error to
		 * {@link SignalListener#handleListenerError(Throwable)} then drop that error.
		 *
		 * @param listenerError the exception thrown from a handler method happening after sequence termination
		 */
		protected void handleListenerErrorPostTermination(Throwable listenerError) {
			listener.handleListenerError(listenerError);
			Operators.onErrorDropped(listenerError, actual.currentContext());
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				try {
					listener.doOnSubscription();
				}
				catch (Throwable observerError) {
					handleListenerErrorPreSubscription(observerError, s);
					return;
				}
				actual.onSubscribe(s);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				try {
					listener.doOnMalformedOnNext(t);
				}
				catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				}
				finally {
					Operators.onNextDropped(t, currentContext());
				}
				return;
			}
			try {
				listener.doOnNext(t);
			}
			catch (Throwable observerError) {
				handleListenerErrorAndTerminate(observerError);
				return;
			}
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				try {
					listener.doOnMalformedOnError(t);
				}
				catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				}
				finally {
					Operators.onErrorDropped(t, currentContext());
				}
				return;
			}
			done = true;

			try {
				listener.doOnError(t);
			}
			catch (Throwable observerError) {
				//any error in the hooks interrupts other hooks, including doFinally
				handleListenerErrorMultipleAndTerminate(observerError, t);
				return;
			}

			actual.onError(t); //RS: onError MUST terminate normally and not throw

			try {
				listener.doAfterError(t);
				listener.doFinally(SignalType.ON_ERROR);
			}
			catch (Throwable observerError) {
				handleListenerErrorPostTermination(observerError);
			}
		}

		@Override
		public void onComplete() {
			if (done) {
				try {
					listener.doOnMalformedOnComplete();
				}
				catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				}
				return;
			}
			done = true;

			try {
				listener.doOnComplete();
			}
			catch (Throwable observerError) {
				handleListenerErrorAndTerminate(observerError);
				return;
			}

			actual.onComplete(); //RS: onComplete MUST terminate normally and not throw

			try {
				listener.doAfterComplete();
				listener.doFinally(SignalType.ON_COMPLETE);
			}
			catch (Throwable observerError) {
				handleListenerErrorPostTermination(observerError);
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				try {
					listener.doOnRequest(n);
				}
				catch (Throwable observerError) {
					handleListenerErrorAndTerminate(observerError);
					return;
				}
				s.request(n);
			}
		}

		@Override
		public void cancel() {
			Context ctx = actual.currentContext();
			try {
				listener.doOnCancel();
			}
			catch (Throwable observerError) {
				handleListenerErrorAndTerminate(observerError);
				return;
			}

			try {
				s.cancel();
			}
			finally {
				try {
					listener.doFinally(SignalType.CANCEL);
				}
				catch (Throwable observerError) {
					handleListenerErrorAndTerminate(observerError); //redundant s.cancel
				}
			}
		}
	}

	static final class ListenConditionalSubscriber<T> extends ListenSubscriber<T> implements ConditionalSubscriber<T> {

		final ConditionalSubscriber<? super T> actualConditional;

		public ListenConditionalSubscriber(ConditionalSubscriber<? super T> actual, SignalListener<T> signalListener) {
			super(actual, signalListener);
			this.actualConditional = actual;
		}

		@Override
		public boolean tryOnNext(T t) {
			if (actualConditional.tryOnNext(t)) {
				try {
					listener.doOnNext(t);
				}
				catch (Throwable listenerError) {
					handleListenerErrorAndTerminate(listenerError);
				}
				return true;
			}
			return false;
		}
	}
}
