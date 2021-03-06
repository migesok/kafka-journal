package com.evolutiongaming.kafka.journal

import com.datastax.driver.core.{GettableByNameData, SettableData}
import com.evolutiongaming.scassandra.syntax._
import com.evolutiongaming.scassandra.{DecodeRow, EncodeRow}
import com.evolutiongaming.skafka.Topic

final case class Key(id: Id, topic: Topic) {
  override def toString = s"$topic:$id"
}

object Key {

  implicit val EncodeImpl: EncodeRow[Key] = new EncodeRow[Key] {

    def apply[B <: SettableData[B]](data: B, value: Key) = {
      data
        .encode("id", value.id)
        .encode("topic", value.topic)
    }
  }

  implicit val DecodeImpl: DecodeRow[Key] = new DecodeRow[Key] {

    def apply(data: GettableByNameData) = {
      Key(
        id = data.decode[Id]("id"),
        topic = data.decode[Topic]("topic"))
    }
  }
}