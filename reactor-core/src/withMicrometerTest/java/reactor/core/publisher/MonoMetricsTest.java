/*
 * Copyright (c) 2018-2021 VMware Inc. or its affiliates, All Rights Reserved.
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

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.publisher.MonoMetrics.MetricsSubscriber;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.FluxMetrics.*;
import static reactor.test.publisher.TestPublisher.Violation.CLEANUP_ON_TERMINATE;

public class MonoMetricsTest {

	private MeterRegistry registry;

	@BeforeEach
	public void setupRegistry() {
		registry = new SimpleMeterRegistry();
	}

	@AfterEach
	public void removeRegistry() {
		registry.close();
	}

	@Test
	public void sequenceNameFromScanUnavailable() {
		Mono<String> delegate = Mono.just("foo");
		Mono<String> source = new Mono<String>() {

			@Override
			public void subscribe(CoreSubscriber<? super String> actual) {
				delegate.subscribe(actual);
			}
		};
		MonoMetrics<String> test = new MonoMetrics<>(source, registry);

		assertThat(Scannable.from(source).isScanAvailable())
				.as("source scan unavailable").isFalse();
		assertThat(test.name).isEqualTo(REACTOR_DEFAULT_NAME);
	}

	@Test
	public void sequenceNameFromScannableNoName() {
		Mono<String> source = Mono.just("foo");
		MonoMetrics<String> test = new MonoMetrics<>(source, registry);

		assertThat(Scannable.from(source).isScanAvailable())
				.as("source scan available").isTrue();
		assertThat(test.name).isEqualTo(REACTOR_DEFAULT_NAME);
	}

	@Test
	public void sequenceNameFromScannableName() {
		Mono<String> source = Mono.just("foo").name("foo").hide().hide();
		MonoMetrics<String> test = new MonoMetrics<>(source, registry);

		assertThat(Scannable.from(source).isScanAvailable())
				.as("source scan available").isTrue();
		assertThat(test.name).isEqualTo("foo");
	}

	@Test
	public void testUsesMicrometer() {
		AtomicReference<Subscription> subRef = new AtomicReference<>();

		new MonoMetrics<>(Mono.just("foo").hide(), registry)
				.doOnSubscribe(subRef::set)
				.subscribe();

		assertThat(subRef.get()).isInstanceOf(MetricsSubscriber.class);
	}

	@Test
	public void splitMetricsOnName() {
		final Mono<Integer> unnamedSource = Mono.<Integer>error(new ArithmeticException("boom"))
				.hide();
		final Mono<Integer> unnamed = new MonoMetrics<>(unnamedSource, registry)
				.onErrorResume(e -> Mono.empty());
		final Mono<Integer> namedSource = Mono.just(40)
		                                      .name("foo")
		                                      .map(i -> 100 / (40 - i))
		                                      .hide();
		final Mono<Integer> named = new MonoMetrics<>(namedSource, registry)
				.onErrorResume(e -> Mono.empty());

		Mono.when(unnamed, named).block();

		Timer unnamedMeter = registry
				.find(METER_FLOW_DURATION)
				.tags(Tags.of(TAG_ON_ERROR))
				.tag(TAG_KEY_EXCEPTION, ArithmeticException.class.getName())
				.tag(TAG_SEQUENCE_NAME, REACTOR_DEFAULT_NAME)
				.timer();

		Timer namedMeter = registry
				.find(METER_FLOW_DURATION)
				.tags(Tags.of(TAG_ON_ERROR))
				.tag(TAG_KEY_EXCEPTION, ArithmeticException.class.getName())
				.tag(TAG_SEQUENCE_NAME, "foo")
				.timer();

		assertThat(unnamedMeter).isNotNull();
		assertThat(namedMeter).isNotNull();

		assertThat(unnamedMeter.count()).isOne();
		assertThat(namedMeter.count()).isOne();
	}

	@Test
	public void usesTags() {
		Mono<Integer> source = Mono.just(1)
		                           .tag("tag1", "A")
		                           .name("usesTags")
		                           .tag("tag2", "foo")
		                           .hide();
		new MonoMetrics<>(source, registry)
				.block();

		Timer meter = registry
				.find(METER_FLOW_DURATION)
				.tags(Tags.of(TAG_ON_COMPLETE))
				.tag(TAG_SEQUENCE_NAME, "usesTags")
				.tag("tag1", "A")
				.tag("tag2", "foo")
				.timer();

		assertThat(meter).isNotNull();
		assertThat(meter.count()).isEqualTo(1L);
	}

	@Test
	public void noOnNextTimer() {
		Mono<Integer> source = Mono.just(1)
		                           .hide();
		new MonoMetrics<>(source, registry)
				.block();

		Timer nextMeter = registry
				.find(METER_ON_NEXT_DELAY)
				.timer();

		assertThat(nextMeter).isNull();
	}

	@Test
	public void malformedOnNext() {
		TestPublisher<Integer> testPublisher = TestPublisher.createNoncompliant(CLEANUP_ON_TERMINATE);
		Mono<Integer> source = testPublisher.mono().hide();

		new MonoMetrics<>(source, registry)
				.subscribe();

		testPublisher.complete()
		             .next(2);

		Counter malformedMeter = registry
				.find(METER_MALFORMED)
				.counter();

		assertThat(malformedMeter).isNotNull();
		assertThat(malformedMeter.count()).isEqualTo(1);
	}

	@Test
	public void malformedOnError() {
		AtomicReference<Throwable> errorDropped = new AtomicReference<>();
		Hooks.onErrorDropped(errorDropped::set);
		Exception dropError = new IllegalStateException("malformedOnError");
		TestPublisher<Integer> testPublisher = TestPublisher.createNoncompliant(CLEANUP_ON_TERMINATE);
		Mono<Integer> source = testPublisher.mono().hide();

		new MonoMetrics<>(source, registry)
				.subscribe();

		testPublisher.complete()
		             .error(dropError);

		Counter malformedMeter = registry
				.find(METER_MALFORMED)
				.counter();

		assertThat(malformedMeter).isNotNull();
		assertThat(malformedMeter.count()).isEqualTo(1);
		assertThat(errorDropped).hasValue(dropError);
	}

	@Test
	public void subscribeToComplete() {
		Mono<Long> source = Mono.delay(Duration.ofMillis(100))
		                          .hide();
		new MonoMetrics<>(source, registry)
				.block();

		Timer stcCompleteTimer = registry.find(METER_FLOW_DURATION)
		                                 .tags(Tags.of(TAG_ON_COMPLETE))
		                                 .timer();

		Timer stcErrorTimer = registry.find(METER_FLOW_DURATION)
		                              .tags(Tags.of(TAG_ON_ERROR))
		                              .timer();

		Timer stcCancelTimer = registry.find(METER_FLOW_DURATION)
		                               .tags(Tags.of(TAG_CANCEL))
		                               .timer();

		assertThat(stcCompleteTimer.max(TimeUnit.MILLISECONDS))
				.as("subscribe to complete timer")
				.isGreaterThanOrEqualTo(100);

		assertThat(stcErrorTimer)
				.as("subscribe to error timer is lazily registered")
				.isNull();

		assertThat(stcCancelTimer)
				.as("subscribe to cancel timer")
				.isNull();
	}

	@Test
	public void subscribeToError() {
		Mono<Long> source = Mono.delay(Duration.ofMillis(100))
		                           .map(v -> 100 / v)
		                           .hide();
		new MonoMetrics<>(source, registry)
				.onErrorReturn(-1L)
				.block();

		Timer stcCompleteTimer = registry.find(METER_FLOW_DURATION)
		                                 .tags(Tags.of(TAG_ON_COMPLETE))
		                                 .timer();

		Timer stcErrorTimer = registry.find(METER_FLOW_DURATION)
		                              .tags(Tags.of(TAG_ON_ERROR))
		                              .timer();

		Timer stcCancelTimer = registry.find(METER_FLOW_DURATION)
		                               .tags(Tags.of(TAG_CANCEL))
		                               .timer();

		SoftAssertions.assertSoftly(softly -> {
			softly.assertThat(stcCompleteTimer)
					.as("subscribe to complete timer")
					.isNull();

			softly.assertThat(stcErrorTimer.max(TimeUnit.MILLISECONDS))
					.as("subscribe to error timer")
					.isGreaterThanOrEqualTo(100);

			assertThat(stcCancelTimer)
					.as("subscribe to cancel timer")
					.isNull();
		});
	}

	@Test
	public void subscribeToCancel() throws InterruptedException {
		Mono<Long> source = Mono.delay(Duration.ofMillis(200))
		                           .hide();
		Disposable disposable = new MonoMetrics<>(source, registry).subscribe();
		Thread.sleep(100);
		disposable.dispose();

		Timer stcCompleteTimer = registry.find(METER_FLOW_DURATION)
		                                 .tags(Tags.of(TAG_ON_COMPLETE))
		                                 .timer();

		Timer stcErrorTimer = registry.find(METER_FLOW_DURATION)
		                              .tags(Tags.of(TAG_ON_ERROR))
		                              .timer();

		Timer stcCancelTimer = registry.find(METER_FLOW_DURATION)
		                               .tags(Tags.of(TAG_CANCEL))
		                               .timer();

		SoftAssertions.assertSoftly(softly -> {
			softly.assertThat(stcCompleteTimer)
					.as("subscribe to complete timer")
					.isNull();

			softly.assertThat(stcErrorTimer)
					.as("subscribe to error timer is lazily registered")
					.isNull();

			softly.assertThat(stcCancelTimer.max(TimeUnit.MILLISECONDS))
					.as("subscribe to cancel timer")
					.isGreaterThanOrEqualTo(100);
		});
	}

	@Test
	public void countsSubscriptions() {
		Mono<Integer> source = Mono.just(1)
		                           .hide();
		Mono<Integer> test = new MonoMetrics<>(source, registry);

		test.subscribe();
		Counter meter = registry.find(METER_SUBSCRIBED)
		                        .counter();

		assertThat(meter).isNotNull();
		assertThat(meter.count()).as("after 1s subscribe").isEqualTo(1);

		test.subscribe();
		test.subscribe();

		assertThat(meter.count()).as("after more subscribe").isEqualTo(3);
	}

	@Test
	public void noRequestTrackingEvenForNamedSequence() {
		Mono<Integer> source = Mono.just(10)
		                           .name("foo")
		                           .hide();
		new MonoMetrics<>(source, registry)
				.block();

		DistributionSummary meter = registry.find(METER_REQUESTED)
		                                    .summary();

		assertThat(meter).as("global find").isNull();

		meter = registry.find(METER_REQUESTED)
		                .tag(TAG_SEQUENCE_NAME, "foo")
		                .summary();

		assertThat(meter).as("tagged find").isNull();
	}

	//see https://github.com/reactor/reactor-core/issues/1425
	@Test
	public void flowDurationTagsConsistency() {
		Mono<Integer> source1 = Mono.just(1)
		                            .name("normal")
		                            .hide();
		Mono<Object> source2 = Mono.error(new IllegalStateException("dummy"))
		                           .name("error")
		                           .hide();

		new MonoMetrics<>(source1, registry)
				.block();
		new MonoMetrics<>(source2, registry)
				.onErrorReturn(0)
				.block();

		Set<Set<String>> uniqueTagKeySets = registry
				.find(METER_FLOW_DURATION)
				.meters()
				.stream()
				.map(it -> it.getId().getTags())
				.map(it -> it.stream().map(Tag::getKey).collect(Collectors.toSet()))
				.collect(Collectors.toSet());

		assertThat(uniqueTagKeySets).hasSize(1);
	}

	@Test
	public void ensureFuseablePropagateOnComplete_inCaseOfAsyncFusion() {
		Mono<List<Integer>> source = Mono.fromSupplier(() -> Arrays.asList(1, 2, 3));

		//smoke test that this form uses MonoMetricsFuseable
		assertThat(source.metrics()).isInstanceOf(MonoMetricsFuseable.class);

		//now use the test version with local registry
		new MonoMetricsFuseable<List<Integer>>(source, registry)
		    .flatMapIterable(Function.identity())
		    .as(StepVerifier::create)
		    .expectNext(1, 2, 3)
		    .expectComplete()
		    .verify(Duration.ofMillis(500));
	}

	@Test
	public void ensureOnNextInAsyncModeIsCapableToPropagateNulls() {
		Mono<List<Integer>> source = Mono.using(() -> "irrelevant",
				irrelevant -> Mono.fromSupplier(() -> Arrays.asList(1, 2, 3)),
				irrelevant -> { });

		//smoke test that this form uses MonoMetricsFuseable
		assertThat(source.metrics()).isInstanceOf(MonoMetricsFuseable.class);

		//now use the test version with local registry
		new MonoMetricsFuseable<List<Integer>>(source, registry)
		    .flatMapIterable(Function.identity())
		    .as(StepVerifier::create)
		    .expectNext(1, 2, 3)
		    .expectComplete()
		    .verify(Duration.ofMillis(500));
	}

	@Test
	void ensureMetricsUsesTheTagValueClosestToItWhenCalledMultipleTimes() {
		Mono<Integer> level1 = new MonoMetrics<>(
				Mono.just(1)
				    .name("pipelineMono")
				    .tag("operation", "rangeMono"),
				registry);

		Mono<String> level2 = new MonoMetricsFuseable<>(
				level1.map(Object::toString)
				      .name("pipelineMono")
				      .tag("operation", "mapMono"),
				registry);

		Mono<String> level3 = new MonoMetrics<>(
				level2.filter(i -> i.equals("one"))
				      .name("pipelineMono")
				      .tag("operation", "filterMono"),
				registry);

		level3.block();

		Collection<Meter> meters = registry
				.find(METER_FLOW_DURATION)
				.tag(TAG_SEQUENCE_NAME, "pipelineMono")
				.tagKeys("operation")
				.meters();

		assertThat(meters)
				.isNotEmpty()
				.extracting(m -> m.getId().getTag("operation"))
				.containsExactlyInAnyOrder("rangeMono", "mapMono", "filterMono");
	}
}
