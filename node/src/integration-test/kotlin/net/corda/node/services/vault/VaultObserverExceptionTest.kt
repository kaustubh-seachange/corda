package net.corda.node.services.vault

import co.paralleluniverse.strands.concurrent.Semaphore
import com.r3.dbfailure.workflows.CreateStateFlow
import com.r3.dbfailure.workflows.CreateStateFlow.Initiator
import com.r3.dbfailure.workflows.CreateStateFlow.errorTargetsToNum
import com.r3.dbfailure.workflows.DbListenerService
import com.r3.transactionfailure.workflows.ErrorHandling
import com.r3.transactionfailure.workflows.ErrorHandling.CheckpointAfterErrorFlow
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.node.services.Permissions
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.OutOfProcess
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.findCordapp
import org.junit.After
import org.junit.Assert
import org.junit.Test
import java.lang.IllegalStateException
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.persistence.PersistenceException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VaultObserverExceptionTest {
    companion object {

        val log = contextLogger()

        private fun testCordapps() = listOf(
                findCordapp("com.r3.dbfailure.contracts"),
                findCordapp("com.r3.dbfailure.workflows"),
                findCordapp("com.r3.dbfailure.schemas"))
    }

    @After
    fun tearDown() {
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.clear()
        StaffedFlowHospital.onFlowKeptForOvernightObservation.clear()
        StaffedFlowHospital.onFlowAdmitted.clear()
        DbListenerService.onError = null
    }

    /**
     * Causing an SqlException via a syntax error in a vault observer causes the flow to hit the
     * DatabsaseEndocrinologist in the FlowHospital and being kept for overnight observation
     */
    @Test
    fun unhandledSqlExceptionFromVaultObserverGetsHospitalised() {
        val testControlFuture = openFuture<Boolean>().toCompletableFuture()

        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.add {
            when (it) {
                is SQLException -> {
                    testControlFuture.complete(true)
                }
            }
            false
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(
                    ::Initiator,
                    "Syntax Error in Custom SQL",
                    CreateStateFlow.errorTargetsToNum(CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError)
            ).returnValue.then { testControlFuture.complete(false) }
            val foundExpectedException = testControlFuture.getOrThrow(30.seconds)

            Assert.assertTrue(foundExpectedException)
        }
    }

    /**
     * None exception thrown from a vault observer can be suppressible in the flow that triggered the observer
     * because the recording of transaction states failed. The flow will be hospitalized.
     * The exception will bring the rx.Observer down.
     */
    @Test
    fun exceptionFromVaultObserverCannotBeSuppressedInFlow() {
        var observation = 0
        val waitUntilHospitalised = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
            waitUntilHospitalised.release()
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(::Initiator, "Exception", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceThrowMotherOfAllExceptions,
                    CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            waitUntilHospitalised.acquire() // wait here until flow gets hospitalised
        }

        Assert.assertEquals(1, observation)
    }

    /**
     * None runtime exception thrown from a vault observer can be suppressible in the flow that triggered the observer
     * because the recording of transaction states failed. The flow will be hospitalized.
     * The exception will bring the rx.Observer down.
     */
    @Test
    fun runtimeExceptionFromVaultObserverCannotBeSuppressedInFlow() {
        var observation = 0
        val waitUntilHospitalised = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
            waitUntilHospitalised.release()
        }

        driver(DriverParameters(
            startNodesInProcess = true,
            cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(::Initiator, "InvalidParameterException", CreateStateFlow.errorTargetsToNum(
                CreateStateFlow.ErrorTarget.ServiceThrowInvalidParameter,
                CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            waitUntilHospitalised.acquire() // wait here until flow gets hospitalised
        }

        Assert.assertEquals(1, observation)
    }

    /**
     * If we have a state causing a persistence exception during record transactions (in NodeVaultService#processAndNotify),
     * the flow will be kept in for observation.
     */
    @Test
    fun persistenceExceptionDuringRecordTransactionsGetsKeptForObservation() {
        var counter = 0
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.add {
            when (it) {
                is PersistenceException -> {
                    ++counter
                    log.info("Got a PersistentException in the flow hospital count = $counter")
                }
            }
            false
        }
        var observation = 0
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            assertFailsWith<TimeoutException>("PersistenceException") {
                aliceNode.rpc.startFlow(::Initiator, "EntityManager", errorTargetsToNum(
                        CreateStateFlow.ErrorTarget.TxInvalidState))
                        .returnValue.getOrThrow(30.seconds)
            }
        }
        Assert.assertTrue("Flow has not been to hospital", counter > 0)
        Assert.assertEquals(1, observation)
    }

    /**
     * If we have a state causing a persistence exception during record transactions (in NodeVaultService#processAndNotify),
     * trying to catch and suppress that exception inside the flow does protect the flow, but the new
     * interceptor will fail the flow anyway. The flow will be kept in for observation.
     */
    @Test
    fun persistenceExceptionDuringRecordTransactionsCannotBeSuppressedInFlow() {
        var counter = 0
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.add {
            when (it) {
                is PersistenceException -> {
                    ++counter
                    log.info("Got a PersistentException in the flow hospital count = $counter")
                }
            }
            false
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(
                    ::Initiator, "EntityManager",
                    CreateStateFlow.errorTargetsToNum(
                            CreateStateFlow.ErrorTarget.TxInvalidState,
                            CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            val flowResult = flowHandle.returnValue
            assertFailsWith<TimeoutException>("PersistenceException") { flowResult.getOrThrow(30.seconds) }
            Assert.assertTrue("Flow has not been to hospital", counter > 0)
        }
    }

    /**
     * User code throwing a syntax error in a raw vault observer will break the recordTransaction call,
     * therefore handling it in flow code is no good, and the error will be passed to the flow hospital via the
     * interceptor.
     */
    @Test
    fun syntaxErrorInUserCodeInServiceCannotBeSuppressedInFlow() {
        val testControlFuture = openFuture<Boolean>()
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            log.info("Flow has been kept for overnight observation")
            testControlFuture.set(true)
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(::Initiator, "EntityManager", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError,
                    CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            val flowResult = flowHandle.returnValue
            flowResult.then {
                log.info("Flow has finished")
                testControlFuture.set(false)
            }
            Assert.assertTrue("Flow has not been kept in hospital", testControlFuture.getOrThrow(30.seconds))
        }
    }

    /**
     * User code throwing a syntax error and catching suppressing that within the observer code is fine
     * and should not have any impact on the rest of the flow
     */
    @Test
    fun syntaxErrorInUserCodeInServiceCanBeSuppressedInService() {
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(::Initiator, "EntityManager", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError,
                    CreateStateFlow.ErrorTarget.ServiceSwallowErrors))
            val flowResult = flowHandle.returnValue
            flowResult.getOrThrow(30.seconds)
        }
    }

    /**
     * Exceptions thrown from a vault observer ,are now wrapped and rethrown as a HospitalizeFlowException.
     * The flow should get hospitalised and any potential following checkpoint should fail.
     * In case of a SQLException or PersistenceException, this was already "breaking" the database transaction
     * and therefore, the next checkpoint was failing.
     */
    @Test
    fun `attempt to checkpoint, following an error thrown in vault observer which gets supressed in flow, will fail`() {
        var counterBeforeFirstCheckpoint = 0
        var counterAfterFirstCheckpoint = 0
        var counterAfterSecondCheckpoint = 0

        ErrorHandling.hookBeforeFirstCheckpoint = { counterBeforeFirstCheckpoint++ }
        ErrorHandling.hookAfterFirstCheckpoint = { counterAfterFirstCheckpoint++ }
        ErrorHandling.hookAfterSecondCheckpoint = { counterAfterSecondCheckpoint++ }

        val waitUntilHospitalised = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            waitUntilHospitalised.release()
        }

        driver(DriverParameters(
                    inMemoryDB = false,
                    startNodesInProcess = true,
                    isDebug = true,
                    cordappsForAllNodes = listOf(findCordapp("com.r3.dbfailure.contracts"),
                                                 findCordapp("com.r3.dbfailure.workflows"),
                                                 findCordapp("com.r3.transactionfailure.workflows"),
                                                 findCordapp("com.r3.dbfailure.schemas")))) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()

            node.rpc.startFlow(::CheckpointAfterErrorFlow, CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceThrowMotherOfAllExceptions, // throw not persistence exception
                    CreateStateFlow.ErrorTarget.FlowSwallowErrors
                )
            )
            waitUntilHospitalised.acquire()

            // restart node, see if flow retries from correct checkpoint
            node.stop()
            startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            waitUntilHospitalised.acquire()

            // check flow retries from correct checkpoint
            assertTrue(counterBeforeFirstCheckpoint == 1)
            assertTrue(counterAfterFirstCheckpoint == 2)
            assertTrue(counterAfterSecondCheckpoint == 0)
        }
    }

    @Test
    fun `vault observer failing with OnErrorFailedException gets hospitalised`() {
        DbListenerService.onError = {
            log.info("Error in rx.Observer#OnError! - Observer will fail with OnErrorFailedException")
            throw it
        }

        var observation = 0
        val waitUntilHospitalised = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
            waitUntilHospitalised.release()
        }

        driver(DriverParameters(
            startNodesInProcess = true,
            cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(::Initiator, "Exception", CreateStateFlow.errorTargetsToNum(
                CreateStateFlow.ErrorTarget.ServiceThrowInvalidParameter,
                CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            waitUntilHospitalised.acquire() // wait here until flow gets hospitalised
        }

        Assert.assertEquals(1, observation)
    }

    @Test
    fun `out of memory error halts JVM, on node restart flow retries, and succeeds`() {
        driver(DriverParameters(inMemoryDB = false, cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            var aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser), startInSameProcess = false).getOrThrow()
            aliceNode.rpc.startFlow(DbListenerService.Companion::MakeServiceThrowErrorFlow).returnValue.getOrThrow()
            aliceNode.rpc.startFlow(::Initiator, "UnrecoverableError", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceThrowUnrecoverableError))

            val terminated = (aliceNode as OutOfProcess).process.waitFor(30, TimeUnit.SECONDS)
            if (terminated) {
                aliceNode.stop()
                // starting node within the same process this time to take advantage of threads sharing same heap space
                val testControlFuture = openFuture<Boolean>().toCompletableFuture()
                CreateStateFlow.Initiator.onExitingCall = {
                    testControlFuture.complete(true)
                }
                startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser), startInSameProcess = true).getOrThrow()
                assert(testControlFuture.getOrThrow(30.seconds))
            } else {
                throw IllegalStateException("Out of process node is still up and running!")
            }
        }
    }

}