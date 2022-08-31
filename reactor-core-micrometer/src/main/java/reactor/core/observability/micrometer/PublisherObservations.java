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

package reactor.core.observability.micrometer;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.DocumentedObservation;

/**
 * Documentation of {@link MicrometerObservationListener} tags and of the anonymous variant
 * of the observation (no {@link reactor.core.publisher.Flux#name(String)}).
 */
public enum PublisherObservations implements DocumentedObservation {

	/**
	 * Anonymous version of the Micrometer.observation(), when the sequence hasn't been
	 * explicitly named via e.g. Flux#name(String) operator.
	 */
	ANONYMOUS {
		@Override
		public String getName() {
			return "reactor.observation";
		}

		@Override
		public String getContextualName() {
			return "reactor anonymous observation";
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return ObservationTags.values();
		}
	}
	;

	/**
	 * Tags used in the Observation set up by Micrometer.observation() tap listeners.
	 */
	public static enum ObservationTags implements KeyName {
		/**
		 * The status of the sequence, which indicates how it terminated (completed, completedEmpty,
		 * error or cancelled).
		 */
		STATUS {
			@Override
			public String asString() {
				return "reactor.status";
			}
		},
		/**
		 * The type of the sequence, i.e. Flux or Mono.
		 */
		TYPE {
			@Override
			public String asString() {
				return "reactor.type";
			}
		};

		/**
		 * {@link #STATUS} for when the subscription to the sequence was cancelled.
		 */
		public static final String TAG_STATUS_CANCELLED = PublisherMeters.TerminationTags.TAG_STATUS_CANCELLED;
		/**
		 * {@link #STATUS} for when the sequence completed with values.
		 */
		public static final String TAG_STATUS_COMPLETED = PublisherMeters.TerminationTags.TAG_STATUS_COMPLETED;
		/**
		 * {@link #STATUS} for when the sequence completed without value (no onNext).
		 */
		public static final String TAG_STATUS_COMPLETED_EMPTY = PublisherMeters.TerminationTags.TAG_STATUS_COMPLETED_EMPTY;
		/**
		 * {@link #STATUS} for when the sequence terminated with an error. The {@link io.micrometer.observation.Observation#error(Throwable)}
		 * method is used to capture the exception.
		 */
		public static final String TAG_STATUS_ERROR = PublisherMeters.TerminationTags.TAG_STATUS_ERROR;
	}


}
