/*
 * Copyright 2026, FAIRSCAPE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.prov.parity

import spock.lang.Requires
import spock.lang.Specification

/**
 * The guard that keeps the parity suite honest in CI.
 *
 * <p>Every other spec in this package skips when fairscape-cli is not importable,
 * so a CI job that failed to install it would report a green build having compared
 * the port against nothing. This spec runs only when
 * {@code FAIRSCAPE_PARITY_REQUIRED} is set -- which CI does -- and fails if the
 * ground truth is missing.
 *
 * @author FAIRSCAPE
 */
@Requires({ System.getenv('FAIRSCAPE_PARITY_REQUIRED') })
class CliAvailableTest extends Specification {

    def 'fairscape-cli must be runnable when parity is required'() {
        expect:
        CliParity.available()

        and: 'record what parity is being claimed against'
        println "parity ground truth: fairscape-cli ${CliParity.version()} via ${CliParity.command().join(' ')}"
    }

    def 'every declared fixture crate must be present'() {
        expect:
        CliParity.CRATES.every { name ->
            CliParity.FIXTURES.resolve(name).resolve('ro-crate-metadata.json').toFile().exists()
        }
    }
}
