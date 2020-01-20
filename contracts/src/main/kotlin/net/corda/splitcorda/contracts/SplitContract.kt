package net.corda.splitcorda.contracts

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.StatePersistable
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import java.math.BigDecimal
import java.util.*

class SplitContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "net.corda.splitcorda.contracts.SplitContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val signers = command.signers.toSet()
        when (command.value) {
            Commands.CreateBill -> {
                requireThat {
                    "Inputs must be empty" using tx.inputStates.isEmpty()
                    "Output must be non empty" using tx.outputStates.isNotEmpty()
                    for (state in tx.outputStates) {
                        val billEntry = (state as? BillEntry) ?: throw IllegalArgumentException(
                            "State is not an instance of ${BillEntry::class}"
                        )
                        "Outputs is not in ${BillEntry.State.Proposed} state" using
                                (billEntry.state == BillEntry.State.Proposed)
                        billEntry.approvers.forEach {
                            if(it.value) {
                                "The signature of ${it.key} is required" using signers.contains(it.key.owningKey)
                            }
                        }
                    }
                }
            }
            Commands.ApproveBill -> {
                requireThat {
                    val inputs =
                        tx.inputStates.asSequence().map { it as BillEntry }.map { Pair(it.linearId, it) }.toMap()
                    "Input entries shouldn't be duplicated" using (tx.inputs.size == inputs.size)
                    val outputs =
                        tx.outputStates.asSequence().map { it as BillEntry }.map { Pair(it.linearId, it) }.toMap()
                    "Output entries shouldn't be duplicated" using (tx.outputs.size == outputs.size)
                    "The number of inputs must be equal to the number of outputs" using (inputs.size == outputs.size)
                    var stateChanged = false
                    for (input in inputs.values) {
                        val output = outputs.getOrElse(input.linearId, {
                            throw IllegalArgumentException("Entry $input missing from outputs")
                        })
                        "Description has changed" using (output.description == input.description)
                        "Payer has changed" using (output.paidBy == input.paidBy)
                        "Amount has changed" using (output.amount == input.amount)
                        "Beneficiaries have changed" using (output.beneficiaries == input.beneficiaries)
                        val inputApprovers = input.approvers.asSequence().filter { it.value }.map { it.key }.toSet()
                        val outputApprovers =
                            output.approvers.asSequence().filter { it.value }.map { it.key }.toSet()
                        "Approvers have been removed" using outputApprovers.containsAll(inputApprovers)
                        for (additionalApprover in outputApprovers.subtract(inputApprovers)) {
                            "Approver $additionalApprover was added but didn't sign the transaction"
                                .using(signers.contains(additionalApprover.owningKey))
                        }
                        if (output.approvers.all { it.value }) {
                            "BillEntry has been approved by everyone, " +
                                    "it should now be moved to the ${BillEntry.State.Approved}} state" using
                                    (output.state == BillEntry.State.Approved)
                        } else {
                            "BillEntry lacks approval by some of the involved parties, it should be kept " +
                                    "in the ${BillEntry.State.Proposed}} state" using
                                    (output.state == BillEntry.State.Proposed)
                        }
                        stateChanged = stateChanged || input != output
                    }
                    "at least one state need to be different from input and output" using stateChanged
                }
            }
            Commands.Split -> {
                requireThat {
                    val outputBillEntries = tx.outputStates
                        .asSequence()
                        .mapNotNull { it as? BillEntry }
                        .map { Pair(it.linearId, it) }
                        .toMap()
                    for (input in tx.inputStates) {
                        "Every input state must be an instance of ${BillEntry::class.qualifiedName}" using
                                (input is BillEntry)
                        val inputBillEntry = input as BillEntry
                        "Input entries must be in ${BillEntry.State.Approved} state" using
                                (BillEntry.State.Approved == inputBillEntry.state)
                        val outputBillEntry = outputBillEntries.getOrElse(inputBillEntry.linearId, {
                            throw IllegalArgumentException("Entry $input missing from outputs")
                        })
                        "Output entries must be in ${BillEntry.State.Settled} state" using
                                (BillEntry.State.Settled == outputBillEntry.state)

                        "Detected difference between input and output for entry with id: ${inputBillEntry.linearId}"
                            .using(
                                inputBillEntry.beneficiaries == outputBillEntry.beneficiaries &&
                                        inputBillEntry.amount == outputBillEntry.amount &&
                                        inputBillEntry.paidBy == outputBillEntry.paidBy &&
                                        inputBillEntry.description == outputBillEntry.description &&
                                        inputBillEntry.approvers == outputBillEntry.approvers &&
                                        inputBillEntry.beneficiaries == outputBillEntry.beneficiaries
                            )
                    }
                    val settlements = tx.outputStates.mapNotNull { it as? Settlement }
                    "There must be exactly one state of type ${Settlement::class} in the output"
                        .using(settlements.size == 1)
                    val settlement = settlements[0]
                    "Incorrect values in settlement"
                        .using(settlement == Settlement.fromBillEntries(tx.inputStates.mapNotNull { it as? BillEntry }))
                }
            }
        }
    }

    enum class Commands : CommandData {
        CreateBill, ApproveBill, Split, Pay,
    }
}

@BelongsToContract(SplitContract::class)
data class BillEntry(
    override val linearId: UniqueIdentifier = UniqueIdentifier(),
    val state: State = State.Proposed,
    val description: String,
    val amount: BigDecimal,
    val beneficiaries: Set<AbstractParty>,
    val paidBy: AbstractParty,
    val approvers: Map<AbstractParty, Boolean> = (sequenceOf(paidBy) + beneficiaries)
        .map { Pair(it, false) }.toMap()
) : LinearState, StatePersistable {

    override val participants: List<AbstractParty>
        get() = approvers.map { it.key }

    @CordaSerializable
    enum class State {
        Proposed, Approved, Settled
    }
}

@BelongsToContract(SplitContract::class)
data class Settlement(val amounts: Map<AbstractParty, BigDecimal>) : ContractState {
    override val participants = amounts.map { it.key }

    companion object {
        fun fromBillEntries(entries: Iterable<BillEntry>): Settlement {
            val balance = HashMap<AbstractParty, BigDecimal>()
            for (entry in entries) {
                balance
                    .computeIfAbsent(entry.paidBy) { BigDecimal(0) }
                    .add(entry.amount)
                val numberOfBeneficiaries = entry.beneficiaries.size
                for (beneficiary in entry.beneficiaries) {
                    balance.computeIfAbsent(beneficiary) { BigDecimal(0) }
                        .subtract(entry.amount.divide(BigDecimal(numberOfBeneficiaries)))
                }
            }
            return Settlement(balance)
        }
    }

    class Builder {
        val balance = HashMap<AbstractParty, BigDecimal>()

        fun addEntries(entries : Iterable<BillEntry>) {
            entries.forEach(this::addEntry)
        }

        fun addEntry(entry : BillEntry) {
            balance
                .computeIfAbsent(entry.paidBy) { BigDecimal(0) }
                .add(entry.amount)
            val numberOfBeneficiaries = entry.beneficiaries.size
            for (beneficiary in entry.beneficiaries) {
                balance.computeIfAbsent(beneficiary) { BigDecimal(0) }
                    .subtract(entry.amount.divide(BigDecimal(numberOfBeneficiaries)))
            }
        }

        fun build() : Settlement {
            return Settlement(balance)
        }
    }
}
