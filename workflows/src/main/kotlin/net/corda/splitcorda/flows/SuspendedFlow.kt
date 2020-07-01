package net.corda.splitcorda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.unwrap
import java.lang.RuntimeException
import java.time.Duration

@InitiatingFlow
@StartableByRPC
class SuspendedFlow : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        sleep(Duration.ofMinutes(5), false)
    }

    companion object {
        val log = contextLogger()
    }
}

