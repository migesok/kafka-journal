package com.evolutiongaming.kafka.journal


import akka.persistence.kafka.journal.KafkaJournalConfig
import cats.Monad
import cats.implicits._
import cats.effect.{Clock, IO}
import com.evolutiongaming.kafka.journal.eventual.cassandra.EventualCassandra
import com.evolutiongaming.kafka.journal.util.IOSuite._
import com.evolutiongaming.kafka.journal.util.ClockHelper._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.consumer.Consumer
import org.scalatest.{Matchers, Suite}


trait JournalSuite extends ActorSuite with Matchers { self: Suite =>

  lazy val config: KafkaJournalConfig = {
    val config = system.settings.config.getConfig("evolutiongaming.kafka-journal.persistence.journal")
    KafkaJournalConfig(config)
  }

  implicit val kafkaConsumerOf: KafkaConsumerOf[IO] = KafkaConsumerOf[IO](
    system.dispatcher,
    Some(Consumer.Metrics.Empty))

  implicit val kafkaProducerOf: KafkaProducerOf[IO] = KafkaProducerOf[IO](
    system.dispatcher,
    Some(KafkaProducer.Metrics.empty[IO]))

  lazy val ((eventual, producer), release) = {
    val resource = for {
      eventualJournal <- EventualCassandra.of[IO](config.cassandra, None)
      producer   <- Journal.Producer.of[IO](config.journal.producer)
    } yield {
      (eventualJournal, producer)
    }

    resource.allocated.unsafeRunSync()
  }

  override def beforeAll() = {
    super.beforeAll()
    IntegrationSuite.start()
//    eventual
//    producer
  }

  override def afterAll() = {
    release.unsafeRunSync()
    super.afterAll()
  }
}

object JournalSuite {

  trait KeyJournal[F[_]] {

    def append(events: Nel[Event]): F[PartitionOffset]

    def read: F[List[Event]]

    def size: F[Long]

    def pointer: F[Option[SeqNr]]

    def delete(to: SeqNr): F[Option[PartitionOffset]]
  }

  object KeyJournal {

    def apply[F[_] : Monad : Clock](key: Key, journal: Journal[F]): KeyJournal[F] = new KeyJournal[F] {

      def append(events: Nel[Event]) = {
        for {
          timestamp <- Clock[F].instant
          result    <- journal.append(key, events, timestamp)
        } yield result
      }

      def read = journal.read(key, SeqNr.Min).toList

      def size = journal.read(key, SeqNr.Min).length

      def pointer = journal.pointer(key)

      def delete(to: SeqNr) = {
        for {
          timestamp <- Clock[F].instant
          result    <- journal.delete(key, to, timestamp)
        } yield result
      }
    }
  }
}