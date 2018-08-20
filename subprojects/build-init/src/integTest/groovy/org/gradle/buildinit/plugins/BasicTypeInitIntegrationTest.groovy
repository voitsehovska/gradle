/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.buildinit.plugins

import org.gradle.buildinit.plugins.fixtures.ScriptDslFixture
import spock.lang.Unroll


class BasicTypeInitIntegrationTest extends AbstractInitIntegrationSpec {
    @Unroll
    def "can configure root project name with #scriptDsl build scripts"() {
        when:
        run('init', '--project-name', 'someApp', '--dsl', scriptDsl.id)

        then:
        def dslFixture = dslFixtureFor(scriptDsl)
        dslFixture.assertGradleFilesGenerated()
        dslFixture.settingsFile.assertContents(dslFixture.containsStringAssignment('rootProject.name', 'someApp'))

        when:
        run('help')

        then:
        noExceptionThrown()

        where:
        scriptDsl << ScriptDslFixture.SCRIPT_DSLS
    }
}
