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
package reactor.util.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Provide a 1-producer/1-consumer ready queue adapted for a given capacity.
 *
 * @param <T> the queue element type
 */
public final class QueueSupplier<T> implements Supplier<Queue<T>> {

	/**
	 * An allocation friendly default of available slots in a given container, e.g. slow publishers and or fast/few
	 * subscribers
	 *
	 * @deprecated use the same constant in {@link Queues}
	 */
	@Deprecated
	public static final int XS_BUFFER_SIZE    = Queues.XS_BUFFER_SIZE;
	/**
	 * A small default of available slots in a given container, compromise between intensive pipelines, small
	 * subscribers numbers and memory use.
	 * @deprecated use the same constant in {@link Queues}
	 */
	@Deprecated
	public static final int SMALL_BUFFER_SIZE =  Queues.SMALL_BUFFER_SIZE;

	/**
	 * Calculate the next power of 2, greater than or equal to x.<p> From Hacker's Delight, Chapter 3, Harry S. Warren
	 * Jr.
	 *
	 * @param x Value to round up
	 *
	 * @return The next power of 2 from x inclusive
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static int ceilingNextPowerOfTwo(final int x) {
		return Queues.ceilingNextPowerOfTwo(x);
	}

	/**
	 *
	 * @param batchSize the bounded or unbounded (int.max) queue size
	 * @param <T> the reified {@link Queue} generic type
	 * @return an unbounded or bounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> get(int batchSize) {
		return Queues.get(batchSize);
	}

	/**
	 * @param x the int to test
	 *
	 * @return true if x is a power of 2
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static boolean isPowerOfTwo(final int x) {
		return Queues.isPowerOfTwo(x);
	}

	/**
	 *
	 * @param <T> the reified {@link Queue} generic type
	 * @return a bounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> one() {
		return Queues.one();
	}

	/**
	 * @param <T> the reified {@link Queue} generic type
	 *
	 * @return a bounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> small() {
		return Queues.small();
	}

	/**
	 *
	 * @param <T> the reified {@link Queue} generic type
	 * @return an unbounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> unbounded() {
		return Queues.unbounded();
	}

	/**
	 * Returns an unbounded, linked-array-based Queue. Integer.max sized link will
	 * return the default {@link #SMALL_BUFFER_SIZE} size.
	 * @param linkSize the link size
	 * @param <T> the reified {@link Queue} generic type
	 * @return an unbounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> unbounded(int linkSize) {
		return Queues.unbounded(linkSize);
	}

	/**
	 *
	 * @param <T> the reified {@link Queue} generic type
	 * @return a bounded {@link Queue} {@link Supplier}
	 * @deprecated use the same method in {@link Queues}
	 */
	@Deprecated
	public static <T> Supplier<Queue<T>> xs() {
		return Queues.xs();
	}



	final long    batchSize;

	QueueSupplier(long batchSize) {
		this.batchSize = batchSize;
	}

	@Override
	public Queue<T> get() {

		if(batchSize > 10_000_000){
			return new SpscLinkedArrayQueue<>(Queues.SMALL_BUFFER_SIZE);
		}
		else if (batchSize == 1) {
			return new OneQueue<>();
		}
		else{
			return new SpscArrayQueue<>((int)batchSize);
		}
	}

	static final class OneQueue<T> extends AtomicReference<T> implements Queue<T> {
        @Override
		public boolean add(T t) {

		    while (!offer(t));

		    return true;
		}

		@Override
		public boolean addAll(Collection<? extends T> c) {
			return false;
		}

		@Override
		public void clear() {
			set(null);
		}

		@Override
		public boolean contains(Object o) {
			return Objects.equals(get(), o);
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			return false;
		}

		@Override
		public T element() {
			return get();
		}

		@Override
		public boolean isEmpty() {
			return get() == null;
		}

		@Override
		public Iterator<T> iterator() {
			return new QueueIterator<>(this);
		}

		@Override
		public boolean offer(T t) {
			if (get() != null) {
			    return false;
			}
			lazySet(t);
			return true;
		}

		@Override
		@Nullable
		public T peek() {
			return get();
		}

		@Override
		@Nullable
		public T poll() {
			T v = get();
			if (v != null) {
			    lazySet(null);
			}
			return v;
		}

		@Override
		public T remove() {
			return getAndSet(null);
		}

		@Override
		public boolean remove(Object o) {
			return false;
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			return false;
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			return false;
		}

		@Override
		public int size() {
			return get() == null ? 0 : 1;
		}

		@Override
		public Object[] toArray() {
			T t = get();
			if (t == null) {
				return new Object[0];
			}
			return new Object[]{t};
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T1> T1[] toArray(T1[] a) {
			if (a.length > 0) {
				a[0] = (T1)get();
				if (a.length > 1) {
				    a[1] = null;
				}
				return a;
			}
			return (T1[])toArray();
		}
		/** */
        private static final long serialVersionUID = -6079491923525372331L;
	}

	static final class QueueIterator<T> implements Iterator<T> {

		final Queue<T> queue;

		public QueueIterator(Queue<T> queue) {
			this.queue = queue;
		}

		@Override
		public boolean hasNext() {
			return !queue.isEmpty();
		}

		@Override
		public T next() {
			return queue.poll();
		}

		@Override
		public void remove() {
			queue.remove();
		}
	}


    @SuppressWarnings("rawtypes")
    static final Supplier ONE_SUPPLIER   = OneQueue::new;
	@SuppressWarnings("rawtypes")
    static final Supplier XS_SUPPLIER    = () -> new SpscArrayQueue<>(Queues.XS_BUFFER_SIZE);
	@SuppressWarnings("rawtypes")
    static final Supplier SMALL_SUPPLIER = () -> new SpscArrayQueue<>(Queues.SMALL_BUFFER_SIZE);
	@SuppressWarnings("rawtypes")
	static final Supplier SMALL_UNBOUNDED =
			() -> new SpscLinkedArrayQueue<>(Queues.SMALL_BUFFER_SIZE);
	@SuppressWarnings("rawtypes")
	static final Supplier XS_UNBOUNDED = () -> new SpscLinkedArrayQueue<>(Queues.XS_BUFFER_SIZE);
}
