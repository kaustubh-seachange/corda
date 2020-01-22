package net.corda.docs

import net.corda.core.internal.toPath
import net.corda.node.services.config.ConfigHelper
import net.corda.node.services.config.parseAsNodeConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties

class ExampleConfigTest {

    private fun <A : Any> readAndCheckConfigurations(vararg configFilenames: String, loadConfig: (Path) -> A) {
        configFilenames.forEach {
            println("Checking $it")
            val configFileResource = ExampleConfigTest::class.java.classLoader.getResource(it)
            val config = loadConfig(configFileResource.toPath())
            // Force the config fields as they are resolved lazily
            config.javaClass.kotlin.declaredMemberProperties.forEach { member ->
                if (member.visibility == KVisibility.PUBLIC) {
                    member.get(config)
                }
            }
        }
    }

    @Test(timeout=300_000)
fun `example node_confs parses fine`() {
        readAndCheckConfigurations("example-node.conf") {
            val baseDirectory = Paths.get("some-example-base-dir")
            assertThat(ConfigHelper.loadConfig(baseDirectory = baseDirectory, configFile = it).parseAsNodeConfiguration().isValid).isTrue()
        }
    }
}