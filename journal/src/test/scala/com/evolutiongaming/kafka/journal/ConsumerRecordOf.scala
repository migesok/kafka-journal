package com.evolutiongaming.kafka.journal

import com.evolutiongaming.kafka.journal.KafkaConverters._
import com.evolutiongaming.skafka.consumer.{ConsumerRecord, ConsumerRecords, WithSize}
import com.evolutiongaming.skafka.{Offset, TimestampAndType, TimestampType, TopicPartition}

object ConsumerRecordOf {

  def apply(
    action: Action,
    topicPartition: TopicPartition,
    offset: Offset): ConsumerRecord[Id, Bytes] = {

    val producerRecord = action.toProducerRecord
    val timestampAndType = TimestampAndType(action.timestamp, TimestampType.Create)
    ConsumerRecord[Id, Bytes](
      topicPartition = topicPartition,
      offset = offset,
      timestampAndType = Some(timestampAndType),
      key = producerRecord.key.map(bytes => WithSize(bytes, bytes.length)),
      value = producerRecord.value.map(bytes => WithSize(bytes, bytes.length)),
      headers = producerRecord.headers)
  }
}

object ConsumerRecordsOf {

  def apply[K, V](records: List[ConsumerRecord[K, V]]): ConsumerRecords[K, V] = {
    ConsumerRecords(records.groupBy(_.topicPartition))
  }
}
