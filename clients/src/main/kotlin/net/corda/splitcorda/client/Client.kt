package net.corda.splitcorda.client

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import net.corda.splitcorda.contracts.BillEntry
import net.corda.splitcorda.flows.ApproveFlowInitiator
import net.corda.splitcorda.flows.ProposeFlowInitiator
import java.lang.RuntimeException
import java.math.BigDecimal
import java.util.concurrent.ExecutionException


object Client {

    internal class LinearIdConverter : IStringConverter<UniqueIdentifier> {
        override fun convert(value: String): UniqueIdentifier = UniqueIdentifier.fromString(value)
    }

    internal data class CliArgs(
        @Parameter
        var parameters: List<String> = ArrayList(),

        @Parameter(names = arrayOf("-H", "--host"), description = "Node hostname")
        var host: String = "localhost",

        @Parameter(names = arrayOf("-P", "--port"), description = "Node RPC port number")
        var port: Int = 10046,

        @Parameter(names = arrayOf("-u", "--user"), description = "Node username")
        var username: String = "admin",

        @Parameter(names = arrayOf("-p", "--password"), description = "Node password")
        var password: String = "password",

        @Parameter(names = arrayOf("-h", "--help"), help = true)
        var help: Boolean = false
    )

    @Parameters(commandDescription = "List known network parties")
    internal class ListParties

    @Parameters(commandDescription = "List known network parties")
    internal data class ApproveEntries(
        @Parameter(names = arrayOf("-u", "--uuid"), required = true, converter = LinearIdConverter::class)
        var entries : List<UniqueIdentifier> = ArrayList()
    )

    @Parameters(commandDescription = "List bill entries")
    internal data class ListEntries(
        @Parameter(names = arrayOf("-s", "--state"))
        var state: BillEntry.State = BillEntry.State.Proposed
    )

    @Parameters(commandDescription = "Create new BillEntry")
    internal data class CreateEntry(
        @Parameter(names = arrayOf("-a", "--amount"))
        var amount: BigDecimal = BigDecimal(0.0),

        @Parameter(names = arrayOf("-d", "--description"), required = true)
        var description: String = "",

        @Parameter(names = arrayOf("-b", "--beneficiary"), required = true)
        var beneficiaries: List<String> = ArrayList(),

        @Parameter(names = arrayOf("-p", "--paidBy"), required = true)
        var paidBy: String = ""
    )

    @Parameters(commandDescription = "Split existing bill entries BillEntry")
    internal data class SplitEntries(
        @Parameter(names = arrayOf("-u", "--uuid"), required = true, converter = LinearIdConverter::class)
        var entries: List<UniqueIdentifier> = ArrayList()
    )

    val logger = loggerFor<Client>()

    @JvmStatic
    fun main(argv: Array<String>) {
        val cliArgs = CliArgs()
        val listEntries = ListEntries()
        val approveEntries = ApproveEntries()
        val createEntry = CreateEntry()
        val splitEntries = SplitEntries()
        val listParties = ListParties()
        val jc = JCommander.newBuilder()
            .addObject(cliArgs)
            .addCommand("listParties", listParties)
            .addCommand("listEntries", listEntries)
            .addCommand("createEntry", createEntry)
            .addCommand("splitEntries", splitEntries)
            .addCommand("approveEntries", approveEntries)
            .build()
        jc.parse(*argv)
        if (cliArgs.help) {
            jc.usage()
            return
        }

        val proxy = CordaRPCClient(NetworkHostAndPort(cliArgs.host, cliArgs.port))
            .start(cliArgs.username, cliArgs.password).proxy

        fun partyFromName(name: String): Party {
            try {
                return proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(name))!!
            } catch (e: IllegalArgumentException) {
                println("Party name: $name")
                throw e
            }
        }

        when (jc.getParsedCommand()) {
            "listEntries" -> {
                val pageSize = 100
                var pageNumber = DEFAULT_PAGE_NUM
                do {
                    val page = builder {
                        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
                            status = Vault.StateStatus.UNCONSUMED
                        )
                        queryCriteria
                            .and(QueryCriteria.VaultCustomQueryCriteria(BillEntry::state.equal(listEntries.state)))
                        proxy.vaultQueryByWithPagingSpec(
                            BillEntry::class.java,
                            queryCriteria,
                            PageSpecification(pageNumber, pageSize)
                        )
                    }
                    page.states.forEach {
                        val state = it.state.data
                        println(state)
                    }
                } while (pageNumber++ < page.totalStatesAvailable / pageSize)
            }
            "createEntry" -> {
                val flowHandle = proxy.startFlow(
                    ::ProposeFlowInitiator,
                    createEntry.description,
                    createEntry.amount,
                    createEntry.beneficiaries.asSequence().map(::partyFromName).toSet(),
                    partyFromName(createEntry.paidBy)
                )
//                val flowHandle = proxy.startFlow {
//                    ProposeFlowInitiator(
//                        description = createEntry.description,
//                        amount = createEntry.amount,
//                        paidBy = partyFromName(createEntry.paidBy),
//                        beneficiaries = createEntry.beneficiaries.asSequence().map(::partyFromName).toSet()
//                    )
//                }
                try {
                    println(flowHandle.returnValue.get())
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
            "listParties" -> {
                proxy.networkMapSnapshot().forEach {
                    it.legalIdentities.forEach(::println)
                }
            }
            "approveEntries" -> {
                val flowHandle = proxy.startFlow(
                    ::ApproveFlowInitiator,
                    approveEntries.entries
                )
                try {
                    println(flowHandle.returnValue.get())
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
            else -> {
                throw NotImplementedError("This should never happen")
            }
        }
    }
}