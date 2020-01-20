package net.corda.splitcorda.flows

import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Test
import java.math.BigDecimal

class SplitFlowTest {

    private val network = MockNetwork(
        MockNetworkParameters(
            cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("net.corda.splitcorda.contracts"),
                TestCordapp.findCordapp("net.corda.splitcorda.flows")
            )
//            notarySpecs = listOf(MockNetworkNotarySpec(DUMMY_NOTARY_NAME))
        )
    )

    private val aliceNode = network.createPartyNode(null)
    private val bobNode = network.createPartyNode(null)
    private val charlieNode = network.createPartyNode(null)


    init {
        network.runNetwork()
    }

    @After
    fun tearDown() = network.startNodes()

    @Test
    fun transactionConstructedByFlowUsesTheCorrectNotary() {
    }
}