package net.corda.node.services.persistence

import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.ThreadBox
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.bufferUntilSubscribed
import net.corda.core.internal.concurrent.doneFuture
import net.corda.core.messaging.DataFeed
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.utilities.AppendOnlyPersistentMap
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import net.corda.nodeapi.internal.persistence.bufferUntilDatabaseCommit
import net.corda.nodeapi.internal.persistence.wrapWithDatabaseTransaction
import rx.Observable
import rx.subjects.PublishSubject
import javax.persistence.*

class DBTransactionStorage : WritableTransactionStorage, SingletonSerializeAsToken() {

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}transactions")
    class DBTransaction(
            @Id
            @Column(name = "tx_id", length = 64)
            var txId: String = "",

            @Lob
            @Column(name = "transaction_value")
            var transaction: ByteArray = ByteArray(0)
    )

    private companion object {
        fun createTransactionsMap(): AppendOnlyPersistentMap<SecureHash, SignedTransaction, DBTransaction, String> {
            return AppendOnlyPersistentMap(
                    toPersistentEntityKey = { it.toString() },
                    fromPersistentEntity = {
                        Pair(SecureHash.parse(it.txId),
                                it.transaction.deserialize<SignedTransaction>(context = SerializationDefaults.STORAGE_CONTEXT))
                    },
                    toPersistentEntity = { key: SecureHash, value: SignedTransaction ->
                        DBTransaction().apply {
                            txId = key.toString()
                            transaction = value.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                        }
                    },
                    persistentEntityClass = DBTransaction::class.java
            )
        }
    }

    private val txStorage = ThreadBox(createTransactionsMap())

    override fun addTransaction(transaction: SignedTransaction): Boolean =
            txStorage.locked {
                addWithDuplicatesAllowed(transaction.id, transaction).apply {
                    updatesPublisher.bufferUntilDatabaseCommit().onNext(transaction)
                }
            }

    override fun getTransaction(id: SecureHash): SignedTransaction? = txStorage.content[id]

    private val updatesPublisher = PublishSubject.create<SignedTransaction>().toSerialized()
    override val updates: Observable<SignedTransaction> = updatesPublisher.wrapWithDatabaseTransaction()

    override fun track(): DataFeed<List<SignedTransaction>, SignedTransaction> {
        return txStorage.locked {
            DataFeed(allPersisted().map { it.second }.toList(), updatesPublisher.bufferUntilSubscribed().wrapWithDatabaseTransaction())
        }
    }

    override fun trackTransaction(id: SecureHash): CordaFuture<SignedTransaction> {
        return txStorage.locked {
            val existingTransaction = get(id)
            if (existingTransaction == null) {
                updatesPublisher.filter { it.id == id }.toFuture()
            } else {
                doneFuture(existingTransaction)
            }
        }
    }

    @VisibleForTesting
    val transactions: Iterable<SignedTransaction> get() = txStorage.content.allPersisted().map { it.second }.toList()
}
