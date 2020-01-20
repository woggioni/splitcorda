package net.corda.splitcorda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import net.corda.splitcorda.contracts.BillEntry
import net.corda.splitcorda.contracts.SplitContract

@InitiatingFlow
@StartableByRPC
class ApproveFlowInitiator(
    private val uuids: List<UniqueIdentifier>
) : FlowLogic<SecureHash>() {
    @Suspendable
    override fun call(): SecureHash {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
            status = Vault.StateStatus.UNCONSUMED,
            linearId = uuids
        )
        val page = serviceHub.vaultService.queryBy(BillEntry::class.java, queryCriteria)

        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        val txBuilder = TransactionBuilder(notary)
        val participants = mutableSetOf<AbstractParty>()
        page.states.forEach { stateAndRef ->
            txBuilder.addInputState(stateAndRef)
            val approvers = stateAndRef.state.data.approvers.asSequence().map {
                if(it.key == ourIdentity) {
                    Pair(it.key, true)
                } else {
                    Pair(it.key, it.value)
                }
            }.toMap()
            val state = if (approvers.all { it.value }) {
                BillEntry.State.Approved
            } else {
                BillEntry.State.Proposed
            }
            txBuilder.addOutputState(stateAndRef.state.data.copy(
                approvers = approvers,
                state = state))
            participants.addAll(stateAndRef.state.data.participants)
        }
        txBuilder.addCommand(SplitContract.Commands.ApproveBill, ourIdentity.owningKey)
        val tx = serviceHub.signInitialTransaction(txBuilder, ourIdentity.owningKey)
        txBuilder.verify(serviceHub)
        serviceHub.recordTransactions(tx)
        participants.forEach {
            if(it != ourIdentity) {
                println(it)
                subFlow(SendTransactionFlow(initiateFlow(it as Party), tx))
            }
        }
        return tx.id
    }
}

@InitiatedBy(ApproveFlowInitiator::class)
class ApproveFlowResponder(private val otherSide: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(
            ReceiveTransactionFlow(
                otherSideSession = otherSide,
                checkSufficientSignatures = true,
                statesToRecord = StatesToRecord.ONLY_RELEVANT
            )
        )
    }
}
