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
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.Fuseable;
import reactor.test.subscriber.AssertSubscriber;
import reactor.util.concurrent.QueueSupplier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static reactor.core.publisher.Flux.range;
import static reactor.core.publisher.Flux.zip;

public class FluxPublishMulticastTest extends AbstractFluxOperatorTest<String, String> {

	@Override
	protected int fusionModeThreadBarrierSupport() {
		return Fuseable.ANY;
	}

	@Override
	protected boolean shouldDropNextAfterTerminate() {
		return false;
	}

	@Override
	protected List<Scenario<String, String>> scenarios_errorInOperatorCallback() {
		return Arrays.asList(Scenario.from(f -> f.publish(p -> {
					throw exception();
				})),

				Scenario.from(f -> f.publish(p -> null),
						Fuseable.NONE,
						step -> step.verifyError(NullPointerException.class)),

				Scenario.from(f -> f.publish(p -> Flux.<String>error(exception()))),

				Scenario.from(f -> f.publish(p -> Flux.just(item(0), null)),
						Fuseable.SYNC,
						step -> step.expectNext(item(0))
						            .verifyError(NullPointerException.class)));
	}

	@Override
	protected List<Scenario<String, String>> scenarios_threeNextAndComplete() {
		return Arrays.asList(Scenario.from(f -> f.publish(p -> p)),

				Scenario.from(f -> f.publish(p -> finiteSourceOrDefault(null)),
						Fuseable.SYNC),

//				Scenario.from(f -> f.publish(p ->
//						p.subscribeWith(UnicastProcessor.create())), Fuseable.ASYNC),

				Scenario.from(f -> f.publish(p -> Flux.<String>empty()), Fuseable.NONE,
						step -> step.verifyComplete()),

				Scenario.withPrefetch(f -> f.publish(p -> p, 1), Fuseable.NONE, 1),

				Scenario.withPrefetch(f -> f.publish(p -> p, Integer.MAX_VALUE),
						Fuseable.NONE,
						Integer.MAX_VALUE)

		);
	}

	@Override
	protected List<Scenario<String, String>> scenarios_errorFromUpstreamFailure() {
		return Arrays.asList(Scenario.from(f -> f.publish(p -> p)),

				Scenario.from(f -> f.publish(p ->
						p.subscribeWith(UnicastProcessor.create())), Fuseable.ASYNC)

//				Scenario.from(f -> f.publish(p -> Flux.<String>empty()), Fuseable.NONE,
//						step -> step.verifyComplete())

		);
	}

	@Test
	public void subsequentSum() {

		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		range(1, 5).publish(o -> zip((Object[] a) -> (Integer) a[0] + (Integer) a[1], o, o.skip(1)))
		           .subscribe(ts);

		ts.assertValues(1 + 2, 2 + 3, 3 + 4, 4 + 5)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void subsequentSumHidden() {

		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		range(1, 5).hide()
		           .publish(o -> zip((Object[] a) -> (Integer) a[0] + (Integer) a[1], o, o
				           .skip(1)))
		           .subscribe(ts);

		ts.assertValues(1 + 2, 2 + 3, 3 + 4, 4 + 5)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void subsequentSumAsync() {

		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		UnicastProcessor<Integer> up =
				UnicastProcessor.create(QueueSupplier.<Integer>get(16).get());

		up.publish(o -> zip((Object[] a) -> (Integer) a[0] + (Integer) a[1], o, o.skip(1)))
		  .subscribe(ts);

		up.onNext(1);
		up.onNext(2);
		up.onNext(3);
		up.onNext(4);
		up.onNext(5);
		up.onComplete();

		ts.assertValues(1 + 2, 2 + 3, 3 + 4, 4 + 5)
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void cancelComposes() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		EmitterProcessor<Integer> sp = EmitterProcessor.create();

		sp.publish(o -> Flux.<Integer>never())
		  .subscribe(ts);

		Assert.assertTrue("Not subscribed?", sp.downstreamCount() != 0);

		ts.cancel();

		Assert.assertFalse("Still subscribed?", sp.downstreamCount() == 0);
	}

	@Test
	public void cancelComposes2() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		EmitterProcessor<Integer> sp = EmitterProcessor.create();

		sp.publish(o -> Flux.<Integer>empty())
		  .subscribe(ts);

		Assert.assertFalse("Still subscribed?", sp.downstreamCount() == 0);
	}

	@Test
	public void pairWise() {
		AssertSubscriber<Tuple2<Integer, Integer>> ts = AssertSubscriber.create();

		range(1, 9).transform(o -> zip(o, o.skip(1)))
		           .subscribe(ts);

		ts.assertValues(Tuples.of(1, 2),
				Tuples.of(2, 3),
				Tuples.of(3, 4),
				Tuples.of(4, 5),
				Tuples.of(5, 6),
				Tuples.of(6, 7),
				Tuples.of(7, 8),
				Tuples.of(8, 9))
		  .assertComplete();
	}

	@Test
	public void innerCanFuse() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();
		ts.requestedFusionMode(Fuseable.ANY);

		Flux.never()
		    .publish(o -> range(1, 5))
		    .subscribe(ts);

		ts.assertFuseableSource()
		  .assertFusionMode(Fuseable.SYNC)
		  .assertValues(1, 2, 3, 4, 5)
		  .assertComplete()
		  .assertNoError();
	}
}
