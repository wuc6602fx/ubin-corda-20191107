package com.r3.demos.ubin2a.validity

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.UntrustworthyData
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.sequence
import net.corda.node.internal.StartedNode
import net.corda.testing.*
import net.corda.testing.contracts.DummyContract
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResolveTransactionsFlowTest {
    lateinit var mockNet: MockNetwork
    lateinit var a: StartedNode<MockNetwork.MockNode>
    lateinit var b: StartedNode<MockNetwork.MockNode>
    lateinit var notary: Party
    lateinit var megaCorpServices: MockServices
    lateinit var notaryServices: MockServices

    @Before
    fun setup() {
        setCordappPackages("net.corda.testing.contracts")
        megaCorpServices = MockServices(MEGA_CORP_KEY)
        notaryServices = MockServices(DUMMY_NOTARY_KEY)
        mockNet = MockNetwork()
        val nodes = mockNet.createSomeNodes()
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        a.internals.registerInitiatedFlow(TestResponseFlow::class.java)
        b.internals.registerInitiatedFlow(TestResponseFlow::class.java)
        mockNet.runNetwork()
        notary = a.services.getDefaultNotary()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        unsetCordappPackages()
    }

    // DOCSTART 1
    @Test
    fun `resolve from two hashes`() {
        val (stx1, stx2) = makeTransactions()
        val p = TestFlow(setOf(stx2.id), a.info.chooseIdentity())
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        val results = future.getOrThrow()
        assertEquals(listOf(stx1.id, stx2.id), results.map { it.id })
        b.database.transaction {
            assertEquals(stx1, b.services.validatedTransactions.getTransaction(stx1.id))
            assertEquals(stx2, b.services.validatedTransactions.getTransaction(stx2.id))
        }
    }
    // DOCEND 1

    @Test
    fun `dependency with an error`() {
        val stx = makeTransactions(signFirstTX = false).second
        val p = TestFlow(setOf(stx.id), a.info.chooseIdentity())
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        assertFailsWith(SignedTransaction.SignaturesMissingException::class) { future.getOrThrow() }
    }

    @Test
    fun `resolve from a signed transaction`() {
        val (stx1, stx2) = makeTransactions()
        val p = TestFlow(stx2, a.info.chooseIdentity())
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
        b.database.transaction {
            assertEquals(stx1, b.services.validatedTransactions.getTransaction(stx1.id))
            // But stx2 wasn't inserted, just stx1.
            assertNull(b.services.validatedTransactions.getTransaction(stx2.id))
        }
    }

    @Test
    fun `denial of service check`() {
        // Chain lots of txns together.
        val stx2 = makeTransactions().second
        val count = 50
        var cursor = stx2
        repeat(count) {
            val builder = DummyContract.move(cursor.tx.outRef(0), MINI_CORP)
            val stx = megaCorpServices.signInitialTransaction(builder)
            a.database.transaction {
                a.services.recordTransactions(stx)
            }
            cursor = stx
        }
        val p = TestFlow(setOf(cursor.id), a.info.chooseIdentity(), 40)
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        assertFailsWith<ResolveTransactionsFlow.ExcessivelyLargeTransactionGraph> { future.getOrThrow() }
    }

    @Test
    fun `triangle of transactions resolves fine`() {
        val stx1 = makeTransactions().first

        val stx2 = DummyContract.move(stx1.tx.outRef(0), MINI_CORP).run {
            val ptx = megaCorpServices.signInitialTransaction(this)
            notaryServices.addSignature(ptx)
        }

        val stx3 = DummyContract.move(listOf(stx1.tx.outRef(0), stx2.tx.outRef(0)), MINI_CORP).run {
            val ptx = megaCorpServices.signInitialTransaction(this)
            notaryServices.addSignature(ptx)
        }

        a.database.transaction {
            a.services.recordTransactions(stx2, stx3)
        }

        val p = TestFlow(setOf(stx3.id), a.info.chooseIdentity())
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
    }

    @Test
    fun attachment() {
        fun makeJar(): InputStream {
            val bs = ByteArrayOutputStream()
            val jar = JarOutputStream(bs)
            jar.putNextEntry(JarEntry("TEST"))
            jar.write("Some test file".toByteArray())
            jar.closeEntry()
            jar.close()
            return bs.toByteArray().sequence().open()
        }
        // TODO: this operation should not require an explicit transaction
        val id = a.database.transaction {
            a.services.attachments.importAttachment(makeJar())
        }
        val stx2 = makeTransactions(withAttachment = id).second
        val p = TestFlow(stx2, a.info.chooseIdentity())
        val future = b.services.startFlow(p).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()

        // TODO: this operation should not require an explicit transaction
        b.database.transaction {
            assertNotNull(b.services.attachments.openAttachment(id))
        }
    }

    // DOCSTART 2
    private fun makeTransactions(signFirstTX: Boolean = true, withAttachment: SecureHash? = null): Pair<SignedTransaction, SignedTransaction> {
        // Make a chain of custody of dummy states and insert into node A.
        val dummy1: SignedTransaction = DummyContract.generateInitial(0, notary, MEGA_CORP.ref(1)).let {
            if (withAttachment != null)
                it.addAttachment(withAttachment)
            when (signFirstTX) {
                true -> {
                    val ptx = megaCorpServices.signInitialTransaction(it)
                    notaryServices.addSignature(ptx)
                }
                false -> {
                    notaryServices.signInitialTransaction(it)
                }
            }
        }
        val dummy2: SignedTransaction = DummyContract.move(dummy1.tx.outRef(0), MINI_CORP).let {
            val ptx = megaCorpServices.signInitialTransaction(it)
            notaryServices.addSignature(ptx)
        }
        a.database.transaction {
            a.services.recordTransactions(dummy1, dummy2)
        }
        return Pair(dummy1, dummy2)
    }
    // DOCEND 2

    @InitiatingFlow
    private class TestFlow(val otherSide: Party, private val resolveTransactionsFlowFactory: (FlowSession) -> ResolveTransactionsFlow, private val txCountLimit: Int? = null) : FlowLogic<List<SignedTransaction>>() {
        constructor(txHashes: Set<SecureHash>, otherSide: Party, txCountLimit: Int? = null) : this(otherSide, { ResolveTransactionsFlow(txHashes, it) }, txCountLimit = txCountLimit)
        constructor(stx: SignedTransaction, otherSide: Party) : this(otherSide, { ResolveTransactionsFlow(stx, it) })

        @Suspendable
        override fun call(): List<SignedTransaction> {
            val session = initiateFlow(otherSide)
            val resolveTransactionsFlow = resolveTransactionsFlowFactory(session)
            txCountLimit?.let { resolveTransactionsFlow.transactionCountLimit = it }
            return subFlow(resolveTransactionsFlow)
        }
    }

    @InitiatedBy(TestFlow::class)
    private class TestResponseFlow(val otherSideSession: FlowSession) : FlowLogic<Void?>() {
        @Suspendable
        override fun call() = subFlow(TestDataVendingFlow(otherSideSession))
    }

    class TestDataVendingFlow(otherSideSession: FlowSession) : SendStateAndRefFlow(otherSideSession, emptyList()) {
        @Suspendable
        override fun sendPayloadAndReceiveDataRequest(otherSideSession: FlowSession, payload: Any): UntrustworthyData<FetchDataFlow.Request> {
            return if (payload is List<*> && payload.isEmpty()) {
                // Hack to not send the first message.
                otherSideSession.receive()
            } else {
                super.sendPayloadAndReceiveDataRequest(this.otherSideSession, payload)
            }
        }
    }
}
