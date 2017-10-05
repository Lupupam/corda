package net.corda.node.services.persistence

import net.corda.core.context.InvocationContext
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.serialize
import net.corda.node.internal.configureDatabase
import net.corda.node.services.api.CheckpointStorage
import net.corda.node.services.statemachine.Checkpoint
import net.corda.node.services.statemachine.FlowStart
import net.corda.node.services.transactions.PersistentUniquenessProvider
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.ALICE
import net.corda.testing.LogHelper
import net.corda.testing.SerializationEnvironmentRule
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import net.corda.testing.rigorousMock
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.streams.toList

internal fun CheckpointStorage.checkpoints(): List<SerializedBytes<Checkpoint>> {
    val checkpoints = getAllCheckpoints().toList()
    return checkpoints.map { it.second }
}

class DBCheckpointStorageTests {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private lateinit var checkpointStorage: DBCheckpointStorage
    private lateinit var database: CordaPersistence

    @Before
    fun setUp() {
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(), rigorousMock())
        newCheckpointStorage()
    }

    @After
    fun cleanUp() {
        database.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test
    fun `add new checkpoint`() {
        val (id, checkpoint) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id, checkpoint)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(checkpoint)
        }
        newCheckpointStorage()
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(checkpoint)
        }
    }

    @Test
    fun `remove checkpoint`() {
        val (id, checkpoint) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id, checkpoint)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
        newCheckpointStorage()
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
    }

    @Test
    fun `add and remove checkpoint in single commit operate`() {
        val (id, checkpoint) = newCheckpoint()
        val (id2, checkpoint2) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id, checkpoint)
            checkpointStorage.addCheckpoint(id2, checkpoint2)
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(checkpoint2)
        }
        newCheckpointStorage()
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(checkpoint2)
        }
    }

    @Test
    fun `add two checkpoints then remove first one`() {
        val (id, firstCheckpoint) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id, firstCheckpoint)
        }
        val (id2, secondCheckpoint) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id2, secondCheckpoint)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(secondCheckpoint)
        }
        newCheckpointStorage()
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).containsExactly(secondCheckpoint)
        }
    }

    @Test
    fun `add checkpoint and then remove after 'restart'`() {
        val (id, originalCheckpoint) = newCheckpoint()
        database.transaction {
            checkpointStorage.addCheckpoint(id, originalCheckpoint)
        }
        newCheckpointStorage()
        val reconstructedCheckpoint = database.transaction {
            checkpointStorage.checkpoints().single()
        }
        database.transaction {
            assertThat(reconstructedCheckpoint).isEqualTo(originalCheckpoint).isNotSameAs(originalCheckpoint)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
    }

    private fun newCheckpointStorage() {
        database.transaction {
            checkpointStorage = DBCheckpointStorage()
        }
    }

    private fun newCheckpoint(): Pair<StateMachineRunId, SerializedBytes<Checkpoint>> {
        val id = StateMachineRunId.createRandom()
        val logic: FlowLogic<*> = object : FlowLogic<Unit>() {
            override fun call() {}
        }
        val frozenLogic = logic.serialize(context = SerializationDefaults.CHECKPOINT_CONTEXT)
        val checkpoint = Checkpoint.create(InvocationContext.shell(), FlowStart.Explicit, logic.javaClass, frozenLogic, ALICE, "").getOrThrow()
        return id to checkpoint.serialize(context = SerializationDefaults.CHECKPOINT_CONTEXT)
    }

}
