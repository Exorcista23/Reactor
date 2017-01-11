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

package reactor.test.scheduler;

import java.util.function.Supplier;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * @author Stephane Maldini
 */
public class VirtualTimeSchedulerTests {

	@Test
	public void allEnabled() {
		Assert.assertFalse(Schedulers.newTimer("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newParallel("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newElastic("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newSingle("") instanceof VirtualTimeScheduler);

		VirtualTimeScheduler.enable(true);

		Assert.assertTrue(Schedulers.newParallel("") instanceof VirtualTimeScheduler);
		Assert.assertTrue(Schedulers.newElastic("") instanceof VirtualTimeScheduler);
		Assert.assertTrue(Schedulers.newSingle("") instanceof VirtualTimeScheduler);
		Assert.assertTrue(Schedulers.newTimer("") instanceof VirtualTimeScheduler);

		VirtualTimeScheduler t = VirtualTimeScheduler.get();

		Assert.assertEquals(Schedulers.newParallel(""), t);
		Assert.assertEquals(Schedulers.newElastic(""), t);
		Assert.assertEquals(Schedulers.newSingle(""), t);
		Assert.assertEquals(Schedulers.newTimer(""), t);
	}

	@Test
	public void timerOnlyEnabled() {
		Assert.assertFalse(Schedulers.newTimer("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newParallel("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newElastic("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newSingle("") instanceof VirtualTimeScheduler);

		VirtualTimeScheduler.enable(false);

		Assert.assertTrue(Schedulers.newTimer("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newParallel("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newElastic("") instanceof VirtualTimeScheduler);
		Assert.assertFalse(Schedulers.newSingle("") instanceof VirtualTimeScheduler);

		VirtualTimeScheduler t = VirtualTimeScheduler.get();

		Assert.assertNotSame(Schedulers.newParallel(""), t);
		Assert.assertNotSame(Schedulers.newElastic(""), t);
		Assert.assertNotSame(Schedulers.newSingle(""), t);
		Assert.assertEquals(Schedulers.newTimer(""), t);
	}

	@Test
	public void enableProvidedSchedulerIdempotent() {
		VirtualTimeScheduler vts = VirtualTimeScheduler.create();

		VirtualTimeScheduler.enable(vts);

		Assert.assertSame(vts, uncache(Schedulers.timer()));
		Assert.assertNotSame(vts, uncache(Schedulers.single()));
		Assert.assertFalse(vts.isEnabledOnAllSchedulers());
		Assert.assertFalse(vts.shutdown);


		VirtualTimeScheduler.enable(vts);

		Assert.assertSame(vts, uncache(Schedulers.timer()));
		Assert.assertNotSame(vts, uncache(Schedulers.single()));
		Assert.assertFalse(vts.isEnabledOnAllSchedulers());
		Assert.assertFalse(vts.shutdown);
	}

	@Test
	public void enableProvidedAllSchedulerIdempotent() {
		VirtualTimeScheduler vts = VirtualTimeScheduler.createForAll();

		VirtualTimeScheduler.enable(vts);

		Assert.assertSame(vts, uncache(Schedulers.timer()));
		Assert.assertSame(vts, uncache(Schedulers.single()));
		Assert.assertTrue(vts.isEnabledOnAllSchedulers());
		Assert.assertFalse(vts.shutdown);


		VirtualTimeScheduler.enable(vts);

		Assert.assertSame(vts, uncache(Schedulers.timer()));
		Assert.assertSame(vts, uncache(Schedulers.single()));
		Assert.assertTrue(vts.isEnabledOnAllSchedulers());
		Assert.assertFalse(vts.shutdown);
	}

	@Test
	public void enableTwoSimilarSchedulersUsesFirst() {
		VirtualTimeScheduler vts1 = VirtualTimeScheduler.createForAll();
		VirtualTimeScheduler vts2 = VirtualTimeScheduler.createForAll();

		VirtualTimeScheduler firstEnableResult = VirtualTimeScheduler.enable(vts1);
		VirtualTimeScheduler secondEnableResult = VirtualTimeScheduler.enable(vts2);

		Assert.assertSame(vts1, firstEnableResult);
		Assert.assertSame(vts1, secondEnableResult);
		Assert.assertSame(vts1, uncache(Schedulers.timer()));
		Assert.assertSame(vts1, uncache(Schedulers.single()));
		Assert.assertFalse(vts1.shutdown);
	}


	@SuppressWarnings("unchecked")
	private static Scheduler uncache(Scheduler potentialCached) {
		if (potentialCached instanceof Supplier) {
			return ((Supplier<Scheduler>) potentialCached).get();
		}
		return potentialCached;
	}

	@After
	public void cleanup() {
		VirtualTimeScheduler.reset();
	}

}