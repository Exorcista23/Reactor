/*
 * Copyright (c) 2016-2023 VMware Inc. or its affiliates, All Rights Reserved.
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

import java.util.Objects;
import java.util.function.Function;

import io.micrometer.context.ContextSnapshot;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.util.context.Context;

final class MonoContextWriteRestoringThreadLocals<T> extends MonoOperator<T, T> {

	final Function<Context, Context> doOnContext;

	MonoContextWriteRestoringThreadLocals(Mono<? extends T> source,
			Function<Context, Context> doOnContext) {
		super(source);
		this.doOnContext = Objects.requireNonNull(doOnContext, "doOnContext");
	}

	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		Context c = doOnContext.apply(actual.currentContext());
		try (ContextSnapshot.Scope __ = ContextSnapshot.setAllThreadLocalsFrom(c)) {
			source.subscribe(
					new FluxContextWriteRestoringThreadLocals
							.ContextWriteRestoringThreadLocalsSubscriber<>(actual, c)
			);
		}
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return super.scanUnsafe(key);
	}
}
