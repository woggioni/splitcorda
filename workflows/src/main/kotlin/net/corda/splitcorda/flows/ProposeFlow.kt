package net.corda.splitcorda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.TransactionBuilder
import net.corda.splitcorda.contracts.BillEntry
import net.corda.splitcorda.contracts.SplitContract
import java.math.BigDecimal

@InitiatingFlow
@StartableByRPC
class ProposeFlowInitiator(
    private val description: String,
    private val amount: BigDecimal,
    private val beneficiaries: Set<Party>,
    private val paidBy: Party
) : FlowLogic<SecureHash>() {
    @Suspendable
    override fun call(): SecureHash {
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val billEntry = BillEntry(
            description = description,
            amount = amount,
            beneficiaries = beneficiaries,
            paidBy = paidBy,
            approvers = (beneficiaries.asSequence() + sequenceOf(paidBy)).map { Pair(it, it == ourIdentity) }.toMap()
        )
        val txBuilder = TransactionBuilder(notary)
        txBuilder.addOutputState(billEntry)
        txBuilder.addCommand(SplitContract.Commands.CreateBill, ourIdentity.owningKey)
        val tx = serviceHub.signInitialTransaction(txBuilder, ourIdentity.owningKey)
        txBuilder.verify(serviceHub)
        serviceHub.recordTransactions(tx)
        billEntry.participants.forEach {
            if(it != ourIdentity) {
                subFlow(SendTransactionFlow(initiateFlow(it as Party), tx))
            }
        }
        return tx.id
    }
}

@InitiatedBy(ProposeFlowInitiator::class)
class ProposeFlowResponder(private val otherSide: FlowSession) : FlowLogic<Unit>() {
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
