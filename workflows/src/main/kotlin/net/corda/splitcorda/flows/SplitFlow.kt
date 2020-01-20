package net.corda.splitcorda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.TransactionBuilder
import net.corda.splitcorda.contracts.BillEntry
import net.corda.splitcorda.contracts.Settlement
import net.corda.splitcorda.contracts.SplitContract

@InitiatingFlow
@StartableByRPC
class SplitFlowInitiator(
    private val uuids: List<UniqueIdentifier>
) : FlowLogic<SecureHash>() {
    @Suspendable
    override fun call(): SecureHash {
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val txBuilder = TransactionBuilder(notary)
        val participants = mutableSetOf<AbstractParty>()
        val settlementBuilder = Settlement.Builder()
        var pageNumber = DEFAULT_PAGE_NUM
        val pageSize = 100
        do {
            val page = builder {
                val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
                    status = Vault.StateStatus.UNCONSUMED,
                    linearId = uuids
                )
                queryCriteria
                    .and(QueryCriteria.VaultCustomQueryCriteria(BillEntry::state.equal(BillEntry.State.Approved)))
                serviceHub.vaultService.queryBy<BillEntry>(queryCriteria, PageSpecification(pageNumber, pageSize))
            }
            page.states.forEach {
                val state = it.state.data
                txBuilder.addInputState(it)
                txBuilder.addOutputState(state.copy(state = BillEntry.State.Settled))
                participants.addAll(state.participants)
                settlementBuilder.addEntry(state)
            }
        } while(pageNumber++ < page.totalStatesAvailable / pageSize)

        txBuilder.addCommand(SplitContract.Commands.ApproveBill, ourIdentity.owningKey)
        txBuilder.addOutputState(settlementBuilder.build())
        val tx = serviceHub.signInitialTransaction(txBuilder, ourIdentity.owningKey)
        txBuilder.verify(serviceHub)
        serviceHub.recordTransactions(tx)
        participants.forEach {
            if(it != ourIdentity) {
                subFlow(DataVendingFlow(initiateFlow(it as Party), tx))
            }
        }
        return tx.id
    }
}

@InitiatedBy(SplitFlowInitiator::class)
class SplitFlowResponder(private val otherSide: FlowSession) : FlowLogic<Unit>() {
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
