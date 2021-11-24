package com.sadliak.services

import com.sadliak.dtos.AddMessageRequestDto
import com.sadliak.models.Message
import com.sadliak.models.WriteConcern
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.enterprise.context.ApplicationScoped


@ApplicationScoped
class MessageServiceImpl(val messageStorageService: MessageStorageService,
                         val messageReplicationService: MessageReplicationService) : MessageService {

    override fun addMessage(requestDto: AddMessageRequestDto) {
        val message = Message(requestDto.message)
        val writeConcern = WriteConcern(requestDto.w)

        val latch = CountDownLatch(writeConcern.value)
        addToStorage(message, latch)
        messageReplicationService.replicateMessage(message, writeConcern, latch)

        // Wait at most 1 minute.
        val replicationCompleted = latch.await(1, TimeUnit.MINUTES)
        require(replicationCompleted) {
            "Timed out waiting for the replication to complete with write concern set to ${writeConcern.value}"
        }
    }

    private fun addToStorage(message: Message, latch: CountDownLatch) {
        messageStorageService.addMessage(message)
        latch.countDown()
    }

    override fun listMessages(): List<Message> {
        return this.messageStorageService.listMessages()
    }
}