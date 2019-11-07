package com.r3.demos.ubin2a.validity

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.*
import net.corda.finance.contracts.asset.*
import net.corda.finance.utils.sumCash
import net.corda.finance.utils.sumCashBy
import net.corda.finance.utils.sumCashOrNull
import net.corda.finance.utils.sumCashOrZero
import net.corda.node.services.vault.NodeVaultService
import net.corda.node.utilities.CordaPersistence
import net.corda.testing.*
import net.corda.testing.contracts.DummyState
import net.corda.testing.contracts.fillWithSomeTestCash
import net.corda.testing.node.MockServices
import net.corda.testing.node.MockServices.Companion.makeTestDatabaseAndMockServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.util.*
import kotlin.test.*

class CashTests : TestDependencyInjectionBase() {
    val defaultRef = OpaqueBytes(ByteArray(1, { 1 }))
    val defaultIssuer = MEGA_CORP.ref(defaultRef)
    val inState = Cash.State(
            amount = 1000.DOLLARS `issued by` defaultIssuer,
            owner = AnonymousParty(ALICE_PUBKEY)
    )
    // Input state held by the issuer
    val issuerInState = inState.copy(owner = defaultIssuer.party)
    val outState = issuerInState.copy(owner = AnonymousParty(BOB_PUBKEY))

    fun Cash.State.editDepositRef(ref: Byte) = copy(
            amount = Amount(amount.quantity, token = amount.token.copy(amount.token.issuer.copy(reference = OpaqueBytes.of(ref))))
    )

    lateinit var miniCorpServices: MockServices
    lateinit var megaCorpServices: MockServices
    val vault: VaultService get() = miniCorpServices.vaultService
    lateinit var database: CordaPersistence
    lateinit var vaultStatesUnconsumed: List<StateAndRef<Cash.State>>

    @Before
    fun setUp() {
        LogHelper.setLevel(NodeVaultService::class)
        megaCorpServices = MockServices(listOf("net.corda.finance.contracts.asset"), MEGA_CORP_KEY)
        val databaseAndServices = makeTestDatabaseAndMockServices(cordappPackages = listOf("net.corda.finance.contracts.asset"), keys = listOf(MINI_CORP_KEY, MEGA_CORP_KEY, OUR_KEY))
        database = databaseAndServices.first
        miniCorpServices = databaseAndServices.second

        // Create some cash. Any attempt to spend >$500 will require multiple issuers to be involved.
        database.transaction {
            miniCorpServices.fillWithSomeTestCash(howMuch = 100.DOLLARS, atLeastThisManyStates = 1, atMostThisManyStates = 1,
                    ownedBy = OUR_IDENTITY_1, issuedBy = MEGA_CORP.ref(1), issuerServices = megaCorpServices)
            miniCorpServices.fillWithSomeTestCash(howMuch = 400.DOLLARS, atLeastThisManyStates = 1, atMostThisManyStates = 1,
                    ownedBy = OUR_IDENTITY_1, issuedBy = MEGA_CORP.ref(1), issuerServices = megaCorpServices)
            miniCorpServices.fillWithSomeTestCash(howMuch = 80.DOLLARS, atLeastThisManyStates = 1, atMostThisManyStates = 1,
                    ownedBy = OUR_IDENTITY_1, issuedBy = MINI_CORP.ref(1), issuerServices = miniCorpServices)
            miniCorpServices.fillWithSomeTestCash(howMuch = 80.SWISS_FRANCS, atLeastThisManyStates = 1, atMostThisManyStates = 1,
                    ownedBy = OUR_IDENTITY_1, issuedBy = MINI_CORP.ref(1), issuerServices = miniCorpServices)
        }
        database.transaction {
            vaultStatesUnconsumed = miniCorpServices.vaultService.queryBy<Cash.State>().states
        }
        resetTestSerialization()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun trivial() {
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }

            tweak {
                output(CASH_PROGRAM_ID) { outState.copy(amount = 2000.DOLLARS `issued by` defaultIssuer) }
                command(ALICE_PUBKEY) { Cash.Commands.Move() }
                this `fails with` "the amounts balance"
            }
            tweak {
                output(CASH_PROGRAM_ID) { outState }
                command(ALICE_PUBKEY) { DummyCommandData }
                // Invalid command
                this `fails with` "required net.corda.finance.contracts.asset.Cash.Commands.Move command"
            }
            tweak {
                output(CASH_PROGRAM_ID) { outState }
                command(BOB_PUBKEY) { Cash.Commands.Move() }
                this `fails with` "the owning keys are a subset of the signing keys"
            }
            tweak {
                output(CASH_PROGRAM_ID) { outState }
                output(CASH_PROGRAM_ID) { outState `issued by` MINI_CORP }
                command(ALICE_PUBKEY) { Cash.Commands.Move() }
                this `fails with` "at least one cash input"
            }
            // Simple reallocation works.
            tweak {
                output(CASH_PROGRAM_ID) { outState }
                command(ALICE_PUBKEY) { Cash.Commands.Move() }
                this.verifies()
            }
        }
    }

    @Test
    fun `issue by move`() {
        // Check we can't "move" money into existence.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { DummyState() }
            output(CASH_PROGRAM_ID) { outState }
            command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }

            this `fails with` "there is at least one cash input for this group"
        }
    }

    @Test
    fun issue() {
        // Check we can issue money only as long as the issuer institution is a command signer, i.e. any recognised
        // institution is allowed to issue as much cash as they want.
        transaction {
            attachment(CASH_PROGRAM_ID)
            output(CASH_PROGRAM_ID) { outState }
            command(ALICE_PUBKEY) { Cash.Commands.Issue() }
            this `fails with` "output states are issued by a command signer"
        }
        transaction {
            attachment(CASH_PROGRAM_ID)
            output(CASH_PROGRAM_ID) {
                Cash.State(
                        amount = 1000.DOLLARS `issued by` MINI_CORP.ref(12, 34),
                        owner = AnonymousParty(ALICE_PUBKEY)
                )
            }
            command(MINI_CORP_PUBKEY) { Cash.Commands.Issue() }
            this.verifies()
        }
    }

    @Test
    fun generateIssueRaw() {
        initialiseTestSerialization()
        // Test generation works.
        val tx: WireTransaction = TransactionBuilder(notary = null).apply {
            Cash().generateIssue(this, 100.DOLLARS `issued by` MINI_CORP.ref(12, 34), owner = AnonymousParty(ALICE_PUBKEY), notary = DUMMY_NOTARY)
        }.toWireTransaction(miniCorpServices)
        assertTrue(tx.inputs.isEmpty())
        val s = tx.outputsOfType<Cash.State>().single()
        assertEquals(100.DOLLARS `issued by` MINI_CORP.ref(12, 34), s.amount)
        assertEquals(MINI_CORP as AbstractParty, s.amount.token.issuer.party)
        assertEquals(AnonymousParty(ALICE_PUBKEY), s.owner)
        assertTrue(tx.commands[0].value is Cash.Commands.Issue)
        assertEquals(MINI_CORP_PUBKEY, tx.commands[0].signers[0])
    }

    @Test
    fun generateIssueFromAmount() {
        initialiseTestSerialization()
        // Test issuance from an issued amount
        val amount = 100.DOLLARS `issued by` MINI_CORP.ref(12, 34)
        val tx: WireTransaction = TransactionBuilder(notary = null).apply {
            Cash().generateIssue(this, amount, owner = AnonymousParty(ALICE_PUBKEY), notary = DUMMY_NOTARY)
        }.toWireTransaction(miniCorpServices)
        assertTrue(tx.inputs.isEmpty())
        assertEquals(tx.outputs[0], tx.outputs[0])
    }

    @Test
    fun `extended issue examples`() {
        // We can consume $1000 in a transaction and output $2000 as long as it's signed by an issuer.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { issuerInState }
            output(CASH_PROGRAM_ID) { inState.copy(amount = inState.amount * 2) }

            // Move fails: not allowed to summon money.
            tweak {
                command(ALICE_PUBKEY) { Cash.Commands.Move() }
                this `fails with` "the amounts balance"
            }

            // Issue works.
            tweak {
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Issue() }
                this.verifies()
            }
        }

        // Can't use an issue command to lower the amount.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { inState.copy(amount = inState.amount.splitEvenly(2).first()) }
            command(MEGA_CORP_PUBKEY) { Cash.Commands.Issue() }
            this `fails with` "output values sum to more than the inputs"
        }

        // Can't have an issue command that doesn't actually issue money.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { inState }
            command(MEGA_CORP_PUBKEY) { Cash.Commands.Issue() }
            this `fails with` "output values sum to more than the inputs"
        }

        // Can't have any other commands if we have an issue command (because the issue command overrules them)
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { inState.copy(amount = inState.amount * 2) }
            command(MEGA_CORP_PUBKEY) { Cash.Commands.Issue() }
            tweak {
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Issue() }
                this `fails with` "there is only a single issue command"
            }
            this.verifies()
        }
    }

    /**
     * Test that the issuance builder rejects building into a transaction with existing
     * cash inputs.
     */
    @Test(expected = IllegalStateException::class)
    fun `reject issuance with inputs`() {
        initialiseTestSerialization()
        // Issue some cash
        var ptx = TransactionBuilder(DUMMY_NOTARY)

        Cash().generateIssue(ptx, 100.DOLLARS `issued by` MINI_CORP.ref(12, 34), owner = MINI_CORP, notary = DUMMY_NOTARY)
        val tx = miniCorpServices.signInitialTransaction(ptx)

        // Include the previously issued cash in a new issuance command
        ptx = TransactionBuilder(DUMMY_NOTARY)
        ptx.addInputState(tx.tx.outRef<Cash.State>(0))
        Cash().generateIssue(ptx, 100.DOLLARS `issued by` MINI_CORP.ref(12, 34), owner = MINI_CORP, notary = DUMMY_NOTARY)
    }

    @Test
    fun testMergeSplit() {
        // Splitting value works.
        transaction {
            attachment(CASH_PROGRAM_ID)
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            tweak {
                input(CASH_PROGRAM_ID) { inState }
                val splits4 = inState.amount.splitEvenly(4)
                for (i in 0..3) output(CASH_PROGRAM_ID) { inState.copy(amount = splits4[i]) }
                this.verifies()
            }
            // Merging 4 inputs into 2 outputs works.
            tweak {
                val splits2 = inState.amount.splitEvenly(2)
                val splits4 = inState.amount.splitEvenly(4)
                for (i in 0..3) input(CASH_PROGRAM_ID) { inState.copy(amount = splits4[i]) }
                for (i in 0..1) output(CASH_PROGRAM_ID) { inState.copy(amount = splits2[i]) }
                this.verifies()
            }
            // Merging 2 inputs into 1 works.
            tweak {
                val splits2 = inState.amount.splitEvenly(2)
                for (i in 0..1) input(CASH_PROGRAM_ID) { inState.copy(amount = splits2[i]) }
                output(CASH_PROGRAM_ID) { inState }
                this.verifies()
            }
        }
    }

    @Test
    fun zeroSizedValues() {
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            input(CASH_PROGRAM_ID) { inState.copy(amount = 0.DOLLARS `issued by` defaultIssuer) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "zero sized inputs"
        }
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { inState.copy(amount = 0.DOLLARS `issued by` defaultIssuer) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "zero sized outputs"
        }
    }

    @Test
    fun trivialMismatches() {
        // Can't change issuer.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { outState `issued by` MINI_CORP }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
        // Can't change deposit reference when splitting.
        transaction {
            attachment(CASH_PROGRAM_ID)
            val splits2 = inState.amount.splitEvenly(2)
            input(CASH_PROGRAM_ID) { inState }
            for (i in 0..1) output(CASH_PROGRAM_ID) { outState.copy(amount = splits2[i]).editDepositRef(i.toByte()) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
        // Can't mix currencies.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { outState.copy(amount = 800.DOLLARS `issued by` defaultIssuer) }
            output(CASH_PROGRAM_ID) { outState.copy(amount = 200.POUNDS `issued by` defaultIssuer) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            input(CASH_PROGRAM_ID) {
                inState.copy(
                        amount = 150.POUNDS `issued by` defaultIssuer,
                        owner = AnonymousParty(BOB_PUBKEY)
                )
            }
            output(CASH_PROGRAM_ID) { outState.copy(amount = 1150.DOLLARS `issued by` defaultIssuer) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
        // Can't have superfluous input states from different issuers.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            input(CASH_PROGRAM_ID) { inState `issued by` MINI_CORP }
            output(CASH_PROGRAM_ID) { outState }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
        // Can't combine two different deposits at the same issuer.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            input(CASH_PROGRAM_ID) { inState.editDepositRef(3) }
            output(CASH_PROGRAM_ID) { outState.copy(amount = inState.amount * 2).editDepositRef(3) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "for reference [01]"
        }
    }

    @Test
    fun exitLedger() {
        // Single input/output straightforward case.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { issuerInState }
            output(CASH_PROGRAM_ID) { issuerInState.copy(amount = issuerInState.amount - (200.DOLLARS `issued by` defaultIssuer)) }

            tweak {
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Exit(100.DOLLARS `issued by` defaultIssuer) }
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                this `fails with` "the amounts balance"
            }

            tweak {
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Exit(200.DOLLARS `issued by` defaultIssuer) }
                this `fails with` "required net.corda.finance.contracts.asset.Cash.Commands.Move command"

                tweak {
                    command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                    this.verifies()
                }
            }
        }
    }

    @Test
    fun `exit ledger with multiple issuers`() {
        // Multi-issuer case.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { issuerInState }
            input(CASH_PROGRAM_ID) { issuerInState.copy(owner = MINI_CORP) `issued by` MINI_CORP }

            output(CASH_PROGRAM_ID) { issuerInState.copy(amount = issuerInState.amount - (200.DOLLARS `issued by` defaultIssuer)) `issued by` MINI_CORP }
            output(CASH_PROGRAM_ID) { issuerInState.copy(owner = MINI_CORP, amount = issuerInState.amount - (200.DOLLARS `issued by` defaultIssuer)) }

            command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { Cash.Commands.Move() }

            this `fails with` "the amounts balance"

            command(MEGA_CORP_PUBKEY) { Cash.Commands.Exit(200.DOLLARS `issued by` defaultIssuer) }
            this `fails with` "the amounts balance"

            command(MINI_CORP_PUBKEY) { Cash.Commands.Exit(200.DOLLARS `issued by` MINI_CORP.ref(defaultRef)) }
            this.verifies()
        }
    }

    @Test
    fun `exit cash not held by its issuer`() {
        // Single input/output straightforward case.
        transaction {
            attachment(CASH_PROGRAM_ID)
            input(CASH_PROGRAM_ID) { inState }
            output(CASH_PROGRAM_ID) { outState.copy(amount = inState.amount - (200.DOLLARS `issued by` defaultIssuer)) }
            command(MEGA_CORP_PUBKEY) { Cash.Commands.Exit(200.DOLLARS `issued by` defaultIssuer) }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }
            this `fails with` "the amounts balance"
        }
    }

    @Test
    fun multiIssuer() {
        transaction {
            attachment(CASH_PROGRAM_ID)
            // Gather 2000 dollars from two different issuers.
            input(CASH_PROGRAM_ID) { inState }
            input(CASH_PROGRAM_ID) { inState `issued by` MINI_CORP }
            command(ALICE_PUBKEY) { Cash.Commands.Move() }

            // Can't merge them together.
            tweak {
                output(CASH_PROGRAM_ID) { inState.copy(owner = AnonymousParty(BOB_PUBKEY), amount = 2000.DOLLARS `issued by` defaultIssuer) }
                this `fails with` "the amounts balance"
            }
            // Missing MiniCorp deposit
            tweak {
                output(CASH_PROGRAM_ID) { inState.copy(owner = AnonymousParty(BOB_PUBKEY)) }
                output(CASH_PROGRAM_ID) { inState.copy(owner = AnonymousParty(BOB_PUBKEY)) }
                this `fails with` "the amounts balance"
            }

            // This works.
            output(CASH_PROGRAM_ID) { inState.copy(owner = AnonymousParty(BOB_PUBKEY)) }
            output(CASH_PROGRAM_ID) { inState.copy(owner = AnonymousParty(BOB_PUBKEY)) `issued by` MINI_CORP }
            this.verifies()
        }
    }

    @Test
    fun multiCurrency() {
        // Check we can do an atomic currency trade tx.
        transaction {
            attachment(CASH_PROGRAM_ID)
            val pounds = Cash.State(658.POUNDS `issued by` MINI_CORP.ref(3, 4, 5), AnonymousParty(BOB_PUBKEY))
            input(CASH_PROGRAM_ID) { inState `owned by` AnonymousParty(ALICE_PUBKEY) }
            input(CASH_PROGRAM_ID) { pounds }
            output(CASH_PROGRAM_ID) { inState `owned by` AnonymousParty(BOB_PUBKEY) }
            output(CASH_PROGRAM_ID) { pounds `owned by` AnonymousParty(ALICE_PUBKEY) }
            command(ALICE_PUBKEY, BOB_PUBKEY) { Cash.Commands.Move() }

            this.verifies()
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Spend tx generation

    val OUR_KEY: KeyPair by lazy { generateKeyPair() }
    val OUR_IDENTITY_1: AbstractParty get() = AnonymousParty(OUR_KEY.public)

    val THEIR_IDENTITY_1 = AnonymousParty(MINI_CORP_PUBKEY)
    val THEIR_IDENTITY_2 = AnonymousParty(CHARLIE_PUBKEY)

    fun makeCash(amount: Amount<Currency>, corp: Party, depositRef: Byte = 1) =
            StateAndRef(
                    TransactionState<Cash.State>(Cash.State(amount `issued by` corp.ref(depositRef), OUR_IDENTITY_1), CASH_PROGRAM_ID, DUMMY_NOTARY),
                    StateRef(SecureHash.randomSHA256(), Random().nextInt(32))
            )

    val WALLET = listOf(
            makeCash(100.DOLLARS, MEGA_CORP),
            makeCash(400.DOLLARS, MEGA_CORP),
            makeCash(80.DOLLARS, MINI_CORP),
            makeCash(80.SWISS_FRANCS, MINI_CORP, 2)
    )

    /**
     * Generate an exit transaction, removing some amount of cash from the ledger.
     */
    private fun makeExit(amount: Amount<Currency>, corp: Party, depositRef: Byte = 1): WireTransaction {
        val tx = TransactionBuilder(DUMMY_NOTARY)
        Cash().generateExit(tx, Amount(amount.quantity, Issued(corp.ref(depositRef), amount.token)), WALLET)
        return tx.toWireTransaction(miniCorpServices)
    }

    private fun makeSpend(amount: Amount<Currency>, dest: AbstractParty): WireTransaction {
        val tx = TransactionBuilder(DUMMY_NOTARY)
        database.transaction {
            Cash.generateSpend(miniCorpServices, tx, amount, dest)
        }
        return tx.toWireTransaction(miniCorpServices)
    }

    /**
     * Try exiting an amount which matches a single state.
     */
    @Test
    fun generateSimpleExit() {
        initialiseTestSerialization()
        val wtx = makeExit(100.DOLLARS, MEGA_CORP, 1)
        assertEquals(WALLET[0].ref, wtx.inputs[0])
        assertEquals(0, wtx.outputs.size)

        val expectedMove = Cash.Commands.Move()
        val expectedExit = Cash.Commands.Exit(Amount(10000, Issued(MEGA_CORP.ref(1), USD)))

        assertEquals(listOf(expectedMove, expectedExit), wtx.commands.map { it.value })
    }

    /**
     * Try exiting an amount smaller than the smallest available input state, and confirm change is generated correctly.
     */
    @Test
    fun generatePartialExit() {
        initialiseTestSerialization()
        val wtx = makeExit(50.DOLLARS, MEGA_CORP, 1)
        assertEquals(WALLET[0].ref, wtx.inputs[0])
        assertEquals(1, wtx.outputs.size)
        assertEquals(WALLET[0].state.data.copy(amount = WALLET[0].state.data.amount.splitEvenly(2).first()), wtx.getOutput(0))
    }

    /**
     * Try exiting a currency we don't have.
     */
    @Test
    fun generateAbsentExit() {
        initialiseTestSerialization()
        assertFailsWith<InsufficientBalanceException> { makeExit(100.POUNDS, MEGA_CORP, 1) }
    }

    /**
     * Try exiting with a reference mis-match.
     */
    @Test
    fun generateInvalidReferenceExit() {
        initialiseTestSerialization()
        assertFailsWith<InsufficientBalanceException> { makeExit(100.POUNDS, MEGA_CORP, 2) }
    }

    /**
     * Try exiting an amount greater than the maximum available.
     */
    @Test
    fun generateInsufficientExit() {
        initialiseTestSerialization()
        assertFailsWith<InsufficientBalanceException> { makeExit(1000.DOLLARS, MEGA_CORP, 1) }
    }

    /**
     * Try exiting for an owner with no states
     */
    @Test
    fun generateOwnerWithNoStatesExit() {
        initialiseTestSerialization()
        assertFailsWith<InsufficientBalanceException> { makeExit(100.POUNDS, CHARLIE, 1) }
    }

    /**
     * Try exiting when vault is empty
     */
    @Test
    fun generateExitWithEmptyVault() {
        initialiseTestSerialization()
        assertFailsWith<InsufficientBalanceException> {
            val tx = TransactionBuilder(DUMMY_NOTARY)
            Cash().generateExit(tx, Amount(100, Issued(CHARLIE.ref(1), GBP)), emptyList())
        }
    }

    @Test
    fun generateSimpleDirectSpend() {
        initialiseTestSerialization()
        val wtx =
                database.transaction {
                    makeSpend(100.DOLLARS, THEIR_IDENTITY_1)
                }
        database.transaction {
            val vaultState = vaultStatesUnconsumed.elementAt(0)
            assertEquals(vaultState.ref, wtx.inputs[0])
            assertEquals(vaultState.state.data.copy(owner = THEIR_IDENTITY_1), wtx.getOutput(0))
            assertEquals(OUR_IDENTITY_1.owningKey, wtx.commands.single { it.value is Cash.Commands.Move }.signers[0])
        }
    }

    @Test
    fun generateSimpleSpendWithParties() {
        initialiseTestSerialization()
        database.transaction {

            val tx = TransactionBuilder(DUMMY_NOTARY)
            Cash.generateSpend(miniCorpServices, tx, 80.DOLLARS, ALICE, setOf(MINI_CORP))

            assertEquals(vaultStatesUnconsumed.elementAt(2).ref, tx.inputStates()[0])
        }
    }

    @Test
    fun generateSimpleSpendWithChange() {
        initialiseTestSerialization()
        val wtx =
                database.transaction {
                    makeSpend(10.DOLLARS, THEIR_IDENTITY_1)
                }
        database.transaction {
            val vaultState = vaultStatesUnconsumed.elementAt(0)
            val changeAmount = 90.DOLLARS `issued by` defaultIssuer
            val likelyChangeState = wtx.outputs.map(TransactionState<*>::data).filter { state ->
                if (state is Cash.State) {
                    state.amount == changeAmount
                } else {
                    false
                }
            }.single()
            val changeOwner = (likelyChangeState as Cash.State).owner
            assertEquals(1, miniCorpServices.keyManagementService.filterMyKeys(setOf(changeOwner.owningKey)).toList().size)
            assertEquals(vaultState.ref, wtx.inputs[0])
            assertEquals(vaultState.state.data.copy(owner = THEIR_IDENTITY_1, amount = 10.DOLLARS `issued by` defaultIssuer), wtx.outputs[0].data)
            assertEquals(vaultState.state.data.copy(amount = changeAmount, owner = changeOwner), wtx.outputs[1].data)
            assertEquals(OUR_IDENTITY_1.owningKey, wtx.commands.single { it.value is Cash.Commands.Move }.signers[0])
        }
    }

    @Test
    fun generateSpendWithTwoInputs() {
        initialiseTestSerialization()
        val wtx =
                database.transaction {
                    makeSpend(500.DOLLARS, THEIR_IDENTITY_1)
                }
        database.transaction {
            val vaultState0 = vaultStatesUnconsumed.elementAt(0)
            val vaultState1 = vaultStatesUnconsumed.elementAt(1)
            assertEquals(vaultState0.ref, wtx.inputs[0])
            assertEquals(vaultState1.ref, wtx.inputs[1])
            assertEquals(vaultState0.state.data.copy(owner = THEIR_IDENTITY_1, amount = 500.DOLLARS `issued by` defaultIssuer), wtx.getOutput(0))
            assertEquals(OUR_IDENTITY_1.owningKey, wtx.commands.single { it.value is Cash.Commands.Move }.signers[0])
        }
    }

    @Test
    fun generateSpendMixedDeposits() {
        initialiseTestSerialization()
        val wtx =
                database.transaction {
                    val wtx = makeSpend(580.DOLLARS, THEIR_IDENTITY_1)
                    assertEquals(3, wtx.inputs.size)
                    wtx
                }
        database.transaction {
            val vaultState0: StateAndRef<Cash.State> = vaultStatesUnconsumed.elementAt(0)
            val vaultState1: StateAndRef<Cash.State> = vaultStatesUnconsumed.elementAt(1)
            val vaultState2: StateAndRef<Cash.State> = vaultStatesUnconsumed.elementAt(2)
            assertEquals(vaultState0.ref, wtx.inputs[0])
            assertEquals(vaultState1.ref, wtx.inputs[1])
            assertEquals(vaultState2.ref, wtx.inputs[2])
            assertEquals(vaultState0.state.data.copy(owner = THEIR_IDENTITY_1, amount = 500.DOLLARS `issued by` defaultIssuer), wtx.outputs[1].data)
            assertEquals(vaultState2.state.data.copy(owner = THEIR_IDENTITY_1), wtx.outputs[0].data)
            assertEquals(OUR_IDENTITY_1.owningKey, wtx.commands.single { it.value is Cash.Commands.Move }.signers[0])
        }
    }

    @Test
    fun generateSpendInsufficientBalance() {
        initialiseTestSerialization()
        database.transaction {

            val e: InsufficientBalanceException = assertFailsWith("balance") {
                makeSpend(1000.DOLLARS, THEIR_IDENTITY_1)
            }
            assertEquals((1000 - 580).DOLLARS, e.amountMissing)

            assertFailsWith(InsufficientBalanceException::class) {
                makeSpend(81.SWISS_FRANCS, THEIR_IDENTITY_1)
            }
        }
    }

    /**
     * Confirm that aggregation of states is correctly modelled.
     */
    @Test
    fun aggregation() {
        val fiveThousandDollarsFromMega = Cash.State(5000.DOLLARS `issued by` MEGA_CORP.ref(2), MEGA_CORP)
        val twoThousandDollarsFromMega = Cash.State(2000.DOLLARS `issued by` MEGA_CORP.ref(2), MINI_CORP)
        val oneThousandDollarsFromMini = Cash.State(1000.DOLLARS `issued by` MINI_CORP.ref(3), MEGA_CORP)

        // Obviously it must be possible to aggregate states with themselves
        assertEquals(fiveThousandDollarsFromMega.amount.token, fiveThousandDollarsFromMega.amount.token)

        // Owner is not considered when calculating whether it is possible to aggregate states
        assertEquals(fiveThousandDollarsFromMega.amount.token, twoThousandDollarsFromMega.amount.token)

        // States cannot be aggregated if the deposit differs
        assertNotEquals(fiveThousandDollarsFromMega.amount.token, oneThousandDollarsFromMini.amount.token)
        assertNotEquals(twoThousandDollarsFromMega.amount.token, oneThousandDollarsFromMini.amount.token)

        // States cannot be aggregated if the currency differs
        assertNotEquals(oneThousandDollarsFromMini.amount.token,
                Cash.State(1000.POUNDS `issued by` MINI_CORP.ref(3), MEGA_CORP).amount.token)

        // States cannot be aggregated if the reference differs
        assertNotEquals(fiveThousandDollarsFromMega.amount.token, (fiveThousandDollarsFromMega `with deposit` defaultIssuer).amount.token)
        assertNotEquals((fiveThousandDollarsFromMega `with deposit` defaultIssuer).amount.token, fiveThousandDollarsFromMega.amount.token)
    }

    @Test
    fun `summing by owner`() {
        val states = listOf(
                Cash.State(1000.DOLLARS `issued by` defaultIssuer, MINI_CORP),
                Cash.State(2000.DOLLARS `issued by` defaultIssuer, MEGA_CORP),
                Cash.State(4000.DOLLARS `issued by` defaultIssuer, MEGA_CORP)
        )
        assertEquals(6000.DOLLARS `issued by` defaultIssuer, states.sumCashBy(MEGA_CORP))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `summing by owner throws`() {
        val states = listOf(
                Cash.State(2000.DOLLARS `issued by` defaultIssuer, MEGA_CORP),
                Cash.State(4000.DOLLARS `issued by` defaultIssuer, MEGA_CORP)
        )
        states.sumCashBy(MINI_CORP)
    }

    @Test
    fun `summing no currencies`() {
        val states = emptyList<Cash.State>()
        assertEquals(0.POUNDS `issued by` defaultIssuer, states.sumCashOrZero(GBP `issued by` defaultIssuer))
        assertNull(states.sumCashOrNull())
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `summing no currencies throws`() {
        val states = emptyList<Cash.State>()
        states.sumCash()
    }

    @Test
    fun `summing a single currency`() {
        val states = listOf(
                Cash.State(1000.DOLLARS `issued by` defaultIssuer, MEGA_CORP),
                Cash.State(2000.DOLLARS `issued by` defaultIssuer, MEGA_CORP),
                Cash.State(4000.DOLLARS `issued by` defaultIssuer, MEGA_CORP)
        )
        // Test that summing everything produces the total number of dollars
        val expected = 7000.DOLLARS `issued by` defaultIssuer
        val actual = states.sumCash()
        assertEquals(expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `summing multiple currencies`() {
        val states = listOf(
                Cash.State(1000.DOLLARS `issued by` defaultIssuer, MEGA_CORP),
                Cash.State(4000.POUNDS `issued by` defaultIssuer, MEGA_CORP)
        )
        // Test that summing everything fails because we're mixing units
        states.sumCash()
    }

    // Double spend.
    @Test
    fun chainCashDoubleSpendFailsWith() {
        val mockService = MockServices(listOf("net.corda.finance.contracts.asset"), MEGA_CORP_KEY)

        ledger(mockService) {
            unverifiedTransaction {
                attachment(CASH_PROGRAM_ID)
                output(CASH_PROGRAM_ID, "MEGA_CORP cash") {
                    Cash.State(
                            amount = 1000.DOLLARS `issued by` MEGA_CORP.ref(1, 1),
                            owner = MEGA_CORP
                    )
                }
            }

            transaction {
                attachment(CASH_PROGRAM_ID)
                input("MEGA_CORP cash")
                output(CASH_PROGRAM_ID, "MEGA_CORP cash 2", "MEGA_CORP cash".output<Cash.State>().copy(owner = AnonymousParty(ALICE_PUBKEY)))
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                this.verifies()
            }

            tweak {
                transaction {
                    attachment(CASH_PROGRAM_ID)
                    input("MEGA_CORP cash")
                    // We send it to another pubkey so that the transaction is not identical to the previous one
                    output(CASH_PROGRAM_ID, "MEGA_CORP cash 3", "MEGA_CORP cash".output<Cash.State>().copy(owner = ALICE))
                    command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                    this.verifies()
                }
                this.fails()
            }

            this.verifies()
        }
    }

    @Test
    fun multiSpend() {
        initialiseTestSerialization()
        val tx = TransactionBuilder(DUMMY_NOTARY)
        database.transaction {
            val payments = listOf(
                    PartyAndAmount(THEIR_IDENTITY_1, 400.DOLLARS),
                    PartyAndAmount(THEIR_IDENTITY_2, 150.DOLLARS)
            )
            Cash.generateSpend(miniCorpServices, tx, payments)
        }
        val wtx = tx.toWireTransaction(miniCorpServices)
        fun out(i: Int) = wtx.getOutput(i) as Cash.State
        assertEquals(4, wtx.outputs.size)
        assertEquals(80.DOLLARS, out(0).amount.withoutIssuer())
        assertEquals(320.DOLLARS, out(1).amount.withoutIssuer())
        assertEquals(150.DOLLARS, out(2).amount.withoutIssuer())
        assertEquals(30.DOLLARS, out(3).amount.withoutIssuer())
        assertEquals(MINI_CORP, out(0).amount.token.issuer.party)
        assertEquals(MEGA_CORP, out(1).amount.token.issuer.party)
        assertEquals(MEGA_CORP, out(2).amount.token.issuer.party)
        assertEquals(MEGA_CORP, out(3).amount.token.issuer.party)
    }
}
