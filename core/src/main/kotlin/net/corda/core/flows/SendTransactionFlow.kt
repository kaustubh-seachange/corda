package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.NamedByHash
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.unwrap

/**
 * In the words of Matt Nesbit on 26/09/19 working code is more important then pretty code. This class that contains code that may
 * be serialized. If it were always serialized then the local disk fetch would need to serialize then de-serialize
 * which wastes time. However over the wire we get MULTI fetch items serialized. This is because we need to get the exact
 * length of the objects to pack them into the 10MB max message size buffer. We do not want to serialize them multiple times
 * so it's a lot more efficient to send the byte stream.
 */
@CordaSerializable
class MaybeSerializedSignedTransaction(override val id: SecureHash, val serialized: SerializedBytes<SignedTransaction>?,
                                       val nonSerialised: SignedTransaction?) : NamedByHash {
    fun get(): SignedTransaction {
        return if (nonSerialised != null) {
            nonSerialised
        } else if (serialized != null) {
            val tranBytes = SerializedBytes<SignedTransaction>(serialized.bytes)
            tranBytes.deserialize()
        } else {
            throw Exception("MaybeSerializedSignedTransaction.get(${id}): May not be called with null object content.")
        }
    }
    fun isNull(): Boolean {
        return serialized == null && nonSerialised == null
    }
    fun serializedByteCount(): Int {
        return if (serialized == null) { 0 } else { serialized.bytes.size }
    }
}

/**
 * The [SendTransactionFlow] should be used to send a transaction to another peer that wishes to verify that transaction's
 * integrity by resolving and checking the dependencies as well. The other side should invoke [ReceiveTransactionFlow] at
 * the right point in the conversation to receive the sent transaction and perform the resolution back-and-forth required
 * to check the dependencies and download any missing attachments.
 *
 * @param otherSide the target party.
 * @param stx the [SignedTransaction] being sent to the [otherSideSession].
 */
open class SendTransactionFlow(otherSide: FlowSession, stx: SignedTransaction) : DataVendingFlow(otherSide, stx)

/**
 * The [SendStateAndRefFlow] should be used to send a list of input [StateAndRef] to another peer that wishes to verify
 * the input's integrity by resolving and checking the dependencies as well. The other side should invoke [ReceiveStateAndRefFlow]
 * at the right point in the conversation to receive the input state and ref and perform the resolution back-and-forth
 * required to check the dependencies.
 *
 * @param otherSideSession the target session.
 * @param stateAndRefs the list of [StateAndRef] being sent to the [otherSideSession].
 */
open class SendStateAndRefFlow(otherSideSession: FlowSession, stateAndRefs: List<StateAndRef<*>>) : DataVendingFlow(otherSideSession, stateAndRefs)

open class DataVendingFlow(val otherSideSession: FlowSession, val payload: Any) : FlowLogic<Void?>() {
    @Suspendable
    protected open fun sendPayloadAndReceiveDataRequest(otherSideSession: FlowSession, payload: Any) = otherSideSession.sendAndReceive<FetchDataFlow.Request>(payload)

    @Suspendable
    protected open fun verifyDataRequest(dataRequest: FetchDataFlow.Request.Data) {
        // User can override this method to perform custom request verification.
    }

    @Suspendable
    override fun call(): Void? {
        //++++val plt = payload.javaClass.typeName //++++
        val networkMaxMessageSize = serviceHub.networkParameters.maxMessageSize //++++
        var maxTransactionSize = networkMaxMessageSize / 2 //++++ make val again


        maxTransactionSize = 100000 //++++ REMOVE REMOVE REMOVE


        println("DataVendingFlow: Call: Network max message size = ${networkMaxMessageSize}, Max Transaction Size = $maxTransactionSize")


        // The first payload will be the transaction data, subsequent payload will be the transaction/attachment/network parameters data.
        var payload = payload

        // Depending on who called this flow, the type of the initial payload is different.
        // The authorisation logic is to maintain a dynamic list of transactions that the caller is authorised to make based on the transactions that were made already.
        // Each time an authorised transaction is requested, the input transactions are added to the list.
        // Once a transaction has been requested, it will be removed from the authorised list. This means that it is a protocol violation to request a transaction twice.
        val authorisedTransactions = when (payload) {
            is NotarisationPayload -> TransactionAuthorisationFilter().addAuthorised(getInputTransactions(payload.signedTransaction))
            is SignedTransaction -> TransactionAuthorisationFilter().addAuthorised(getInputTransactions(payload))
            is RetrieveAnyTransactionPayload -> TransactionAuthorisationFilter(acceptAll = true)
            is List<*> -> TransactionAuthorisationFilter().addAuthorised(payload.flatMap { stateAndRef ->
                if (stateAndRef is StateAndRef<*>) {
                    getInputTransactions(serviceHub.validatedTransactions.getTransaction(stateAndRef.ref.txhash)!!) + stateAndRef.ref.txhash
                } else {
                    throw Exception("Unknown payload type: ${stateAndRef!!::class.java} ?")
                }
            }.toSet())
            else -> throw Exception("Unknown payload type: ${payload::class.java} ?")
        }

        // This loop will receive [FetchDataFlow.Request] continuously until the `otherSideSession` has all the data they need
        // to resolve the transaction, a [FetchDataFlow.EndRequest] will be sent from the `otherSideSession` to indicate end of
        // data request.
        var loopCount = 0
        while (true) {
            val loopCnt = loopCount++
            println("DataVendingFlow: Main While [$loopCnt]...");
            val dataRequest = sendPayloadAndReceiveDataRequest(otherSideSession, payload).unwrap { request ->
                //++++val rqt = request.javaClass.name//++++
                //++++val nmx = request.namexxxx()
                println("sendPayloadAndReceiveDataRequest(): ${request.javaClass.name}")//++++
                when (request) {
                    is FetchDataFlow.Request.Data -> {
                        // Security TODO: Check for abnormally large or malformed data requests
                        verifyDataRequest(request)
                        request
                    }
                    FetchDataFlow.Request.End -> {
                        println("DataVendingFlow: END"); return null } //++++
                }
            }

            //++++val st = dataRequest.dataType.name
            println("Sending data (Type = ${dataRequest.dataType.name})")//++++
            var failCntIndex = 0 //++++ remove for test reject third item
            var maxByteCount = maxTransactionSize // Sample once before the loop
            var totalByteCount = 0
            var firstItem = true
            var multiFetchCountExceeded = false
            var numSent = 0
            payload = when (dataRequest.dataType) {
                FetchDataFlow.DataType.TRANSACTION -> dataRequest.hashes.map { txId ->
                    //++++val sz1=dataRequest.hashes.size //++++
                    println("Sending: TRANSACTION (dataRequest.hashes.size=${dataRequest.hashes.size})")//++++
                    if (!authorisedTransactions.isAuthorised(txId)) {
                        throw FetchDataFlow.IllegalTransactionRequest(txId)
                    }
                    val tx = serviceHub.validatedTransactions.getTransaction(txId)
                            ?: throw FetchDataFlow.HashNotFound(txId)
                    authorisedTransactions.removeAuthorised(tx.id)
                    authorisedTransactions.addAuthorised(getInputTransactions(tx))
                    totalByteCount += tx.txBits.size
                    numSent++
                    tx
                }
                FetchDataFlow.DataType.MULTI_TRANSACTION -> dataRequest.hashes.map { txId -> //++++
                   //++++ val sz1=dataRequest.hashes.size //++++
                    val txs = txId.toString() //++++
                    //++++  val sendItem = failCntInd != 2 && failCntInd != 3 //++++ test fail

                    println("[$failCntIndex]: Sending: MULTI_TRANSACTION Exceeded = ${multiFetchCountExceeded}, ID = '$txId' (num=${dataRequest.hashes.size})")//++++
                    if (!authorisedTransactions.isAuthorised(txId)) {
                        //++++if (sendItem && !authorisedTransactions.isAuthorised(txId)) {
                        throw FetchDataFlow.IllegalTransactionRequest(txId)
                    }
                    // Maybe we should not just throw here as it's not recoverable on the client side. Might be better to send a reason code or
                    // remove the restriction on sending once.
                    println("Transaction authorised OK: '$txs'")//++++
                    // ++++   var sendItem = false
                    var serialized: SerializedBytes<SignedTransaction>? = null
                    if (!multiFetchCountExceeded) {
                        // Only fetch and serialize if we have not already exceeded the maximum byte count. Once we have, no more fetching
                        // is required, just reject all additional items.
                        val tx = serviceHub.validatedTransactions.getTransaction(txId)
                                ?: throw FetchDataFlow.HashNotFound(txId) //++++ how do we handle not found on one item?
                        println("Transaction get OK: '$txs'")//++++
                        serialized = tx.serialize() //++++zzzz might break the code

                        val itemByteCount = serialized.size
                        println("Multi-Send: first = ${firstItem}, Total bytes = ${totalByteCount}, Item byte count = '$itemByteCount' maximum = '$maxByteCount'")//++++
                        if (firstItem || (totalByteCount + itemByteCount) < maxByteCount) {
                            totalByteCount += itemByteCount
                            numSent++
                            // Always include at least one item else if the max is set too low nothing will ever get returned.
                            // Splitting items will be a separate Jira if need be
                            //++++ sendItem = true
                            authorisedTransactions.removeAuthorised(tx.id)
                            authorisedTransactions.addAuthorised(getInputTransactions(tx))
                            //++++SerializedBytes<SignedTransaction>
                            //++++println("adding to return set ++++ ${tx.javaClass.name}")//++++
                            println("Adding item to return set: '${txId}'")//++++
                        } else {
                            println("Fetch block size EXCEEDED.") //++++
                            multiFetchCountExceeded = true
                        }
                    }

                    //++++if (!multiFetchCountExceeded) {
                    //++++    println("NOT adding to return set ++++ $txId")//++++
                    //++++}
                    //val serTran = SerializedSignedTransaction(txId, serialized)
//++++ put back                    val maybeser = MaybeSerializedSignedTransaction(txId, serialized, null)
                    if (multiFetchCountExceeded) { println("Excluding '${txId}' from return set due to exceeded count.") }

                    // Send null if limit is exceeded
                    val maybeserialized = MaybeSerializedSignedTransaction(txId, if (multiFetchCountExceeded) { null } else { serialized }, null)
                    firstItem = false
                    failCntIndex++//REMOVE++++
                    maybeserialized
                }
                FetchDataFlow.DataType.ATTACHMENT -> dataRequest.hashes.map {
                    println("Sending: Attachments")//++++
                    serviceHub.attachments.openAttachment(it)?.open()?.readFully()
                            ?: throw FetchDataFlow.HashNotFound(it)
                }
                FetchDataFlow.DataType.PARAMETERS -> dataRequest.hashes.map {
                    println("Sending: Parameters")//++++
                    (serviceHub.networkParametersService as NetworkParametersStorage).lookupSigned(it)
                            ?: throw FetchDataFlow.MissingNetworkParameters(it)
                }
                FetchDataFlow.DataType.UNKNOWN -> dataRequest.hashes.map {
                    println("Message from from a future version of Corda with UNKNOWN enum value for FetchDataFlow.DataType")//++++warn
                }
            }
            println("Block total size = ${totalByteCount}: Num Items = (${numSent} of ${dataRequest.hashes.size} total)")//++++
        }
    }

    @Suspendable
    private fun getInputTransactions(tx: SignedTransaction): Set<SecureHash> {
        return tx.inputs.map { it.txhash }.toSet() + tx.references.map { it.txhash }.toSet()
    }

    private class TransactionAuthorisationFilter(private val authorisedTransactions: MutableSet<SecureHash> = mutableSetOf(), val acceptAll: Boolean = false) {
        fun isAuthorised(txId: SecureHash) = acceptAll || authorisedTransactions.contains(txId)

        fun addAuthorised(txs: Set<SecureHash>): TransactionAuthorisationFilter {
            authorisedTransactions.addAll(txs)
            return this
        }

        fun removeAuthorised(txId: SecureHash) {
            authorisedTransactions.remove(txId)
        }
    }
}
