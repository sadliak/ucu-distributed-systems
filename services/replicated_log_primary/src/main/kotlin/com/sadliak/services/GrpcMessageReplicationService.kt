package com.sadliak.services

import com.sadliak.config.ReplicationConfig
import com.sadliak.enums.NodeStatus
import com.sadliak.exceptions.AppException
import com.sadliak.grpc.MutinyReplicatedLogGrpc
import com.sadliak.grpc.ReplicateMessageRequest
import com.sadliak.models.Message
import com.sadliak.models.WriteConcern
import io.grpc.ManagedChannelBuilder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import javax.enterprise.context.ApplicationScoped


@ApplicationScoped
class GrpcMessageReplicationService(private val replicationConfig: ReplicationConfig,
                                    private val heartbeatService: HeartbeatService) : MessageReplicationService {

    private val secondaryGrpcClients = this.replicationConfig.enabledNodes()
            .map { (nodeId, config) -> nodeId to this.buildGrpcClient(config) }
            .toMap()

    override fun replicateMessage(message: Message, writeConcern: WriteConcern, latch: CountDownLatch) {
        if (this.secondaryGrpcClients.isEmpty()) {
            throw AppException("There are no secondary nodes to replicate messages to")
        }

        try {
            val replicaClients = this.secondaryGrpcClients.entries
            val replicationRequest = ReplicateMessageRequest.newBuilder()
                    .setMessage(message.text)
                    .setMessageId(message.id)
                    .build()

            replicaClients.map { grpcClient ->
                val nodeId = grpcClient.key
                val (initialBackoffDuration, maxBackoffDuration) = this.getRetryBackoffDurations(nodeId)
                println("Replicating asynchronously to '$nodeId' node...")
                println("Initial retry backoff - $initialBackoffDuration, max retry backoff - $maxBackoffDuration")
                grpcClient.value.replicateMessage(replicationRequest)
                        .onFailure().invoke { e -> println("Retrying because of an error during replication to '${nodeId}': ${e.message}") }
                        .onFailure().retry().withBackOff(initialBackoffDuration, maxBackoffDuration).withJitter(0.3).indefinitely()
                        .onItem().invoke { r ->
                            if (r.response != "ok") {
                                throw AppException("Response from replication was not 'ok'")
                            }

                            println("Successfully replicated to '${nodeId}' node")
                            latch.countDown()
                        }
                        .subscribe()
                        .with(
                                { r -> println("Received final response from '${nodeId}': ${r.response}") },
                                { err -> println("Received final error from '${nodeId}': ${err.message}") }
                        )
            }
        } catch (e: Throwable) {
            throw AppException("Error while replicating message to secondary nodes", cause = e)
        }
    }

    private fun getRetryBackoffDurations(nodeId: String): Pair<Duration, Duration> {
        val nodeStatus = heartbeatService.getNodeStatus(nodeId)

        val defaultInitialRetryBackoff = Duration.ofSeconds(1)
        val defaultMaxRetryBackoff = Duration.ofMinutes(5)

        val initialRetryBackoffMap = mapOf(
                NodeStatus.UNHEALTHY to (Duration.ofSeconds(15)),
                NodeStatus.SUSPECTED to Duration.ofSeconds(5),
                NodeStatus.HEALTHY to defaultInitialRetryBackoff
        )

        val maxRetryBackoffMap = mapOf(
                NodeStatus.UNHEALTHY to Duration.ofMinutes(10),
                NodeStatus.SUSPECTED to defaultMaxRetryBackoff,
                NodeStatus.HEALTHY to defaultMaxRetryBackoff
        )

        return initialRetryBackoffMap.getOrDefault(nodeStatus, defaultInitialRetryBackoff) to
                maxRetryBackoffMap.getOrDefault(nodeStatus, defaultMaxRetryBackoff)
    }

    private fun buildGrpcClient(nodeConfig: ReplicationConfig.Node): MutinyReplicatedLogGrpc.MutinyReplicatedLogStub {
        return MutinyReplicatedLogGrpc.newMutinyStub(
                ManagedChannelBuilder
                        .forAddress(nodeConfig.host, nodeConfig.port)
                        .usePlaintext()
                        .build()
        )
    }
}
