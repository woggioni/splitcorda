package net.corda.splitcorda.contracts

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.math.BigDecimal

class SplitContractTest {

    private val ledgerServices = MockServices(TestIdentity(CordaX500Name("TestId", "", "GB")))
    private val alice = TestIdentity(CordaX500Name("Alice", "", "GB"))
    private val bob = TestIdentity(CordaX500Name("Bob", "", "GB"))
    private val charlie = TestIdentity(CordaX500Name("Charlie", "", "GB"))

    @Test
    fun requestContractRequiresZeroInputsInTheTransaction() {
        ledgerServices.ledger {
            val billEntry = BillEntry(
                description = "beers",
                amount = BigDecimal(18),
                paidBy = alice.party,
                beneficiaries = sequenceOf(alice, bob, charlie).map { it.party }.toSet()
            )
            transaction {
                input(SplitContract.ID, billEntry)
                output(SplitContract.ID, billEntry)
                command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.CreateBill)
                fails()
            }
            transaction {
                output(SplitContract.ID, billEntry)
                command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.CreateBill)
                verifies()
            }
        }
    }

    @Test
    fun approveShouldNotChangeState() {
        val id = UniqueIdentifier()
        val billEntryIn = BillEntry(
            linearId = id,
            description = "beers",
            amount = BigDecimal(18),
            paidBy = alice.party,
            beneficiaries = sequenceOf(alice, bob, charlie).map { it.party }.toSet(),
            state = BillEntry.State.Proposed
        )

        run {
            val billEntryOutWithDifferentAmount = billEntryIn.copy(
                amount = BigDecimal(24)
            )

            ledgerServices.ledger {
                transaction {
                    input(SplitContract.ID, billEntryIn)
                    output(SplitContract.ID, billEntryOutWithDifferentAmount)
                    command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.ApproveBill)
                    fails()
                }
            }
        }

        run {
            val billEntryOutWithDifferentPayer = billEntryIn.copy(
                paidBy = bob.party
            )

            ledgerServices.ledger {
                transaction {
                    input(SplitContract.ID, billEntryIn)
                    output(SplitContract.ID, billEntryOutWithDifferentPayer)
                    command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.ApproveBill)
                    fails()
                }
            }
        }

        run {
            val billEntryOutWithDifferentBeneficiaries = billEntryIn.copy(
                beneficiaries = sequenceOf(alice, charlie).map { it.party }.toSet()
            )

            ledgerServices.ledger {
                transaction {
                    input(SplitContract.ID, billEntryIn)
                    output(SplitContract.ID, billEntryOutWithDifferentBeneficiaries)
                    command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.ApproveBill)
                    fails()
                }
            }
        }

        run {
            val billEntryOutWithDifferentDescription = billEntryIn.copy(description = "beer")

            ledgerServices.ledger {
                transaction {
                    input(SplitContract.ID, billEntryIn)
                    output(SplitContract.ID, billEntryOutWithDifferentDescription)
                    command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.ApproveBill)
                    fails()
                }
            }
        }

        run {
            val billEntryOutEqualToIn = billEntryIn.copy()
            ledgerServices.ledger {
                transaction {
                    input(SplitContract.ID, billEntryIn)
                    output(SplitContract.ID, billEntryOutEqualToIn)
                    command(listOf(alice, bob, charlie).map { it.publicKey }, SplitContract.Commands.ApproveBill)
                    fails()
                }
            }
        }
    }

}