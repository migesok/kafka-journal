package com.evolutiongaming.kafka.journal.replicator

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import akka.persistence.kafka.journal.KafkaJournalConfig
import cats.implicits._
import cats.effect.{IO, Resource}
import com.evolutiongaming.concurrent.FutureHelper._
import com.evolutiongaming.kafka.journal.FixEquality.Implicits._
import com.evolutiongaming.kafka.journal.FoldWhile._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.cassandra.{CassandraCluster, EventualCassandra}
import com.evolutiongaming.kafka.journal.util.IOSuite._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.Offset
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._

class ReplicatorIntSpec extends WordSpec with ActorSuite with Matchers {

  lazy val config = {
    val config = system.settings.config.getConfig("evolutiongaming.kafka-journal.replicator")
    KafkaJournalConfig(config)
  }

  implicit lazy val ec = system.dispatcher

  lazy val actorLog = ActorLog(system, getClass)

  val timeout = 30.seconds

  lazy val ((eventual, producer), release) = {
    val resource = for {
      cassandraCluster <- CassandraCluster.of[IO](config.cassandra.client, config.cassandra.retries)
      cassandraSession <- cassandraCluster.session
      eventualJournal  <- {
        implicit val cassandraSession1 = cassandraSession
        Resource.liftF(EventualCassandra.of[IO](config.cassandra, None))
      }
      kafkaProducer <- KafkaProducer.of[IO](config.journal.producer, ec)
    } yield {
      (eventualJournal, kafkaProducer)
    }

    resource.allocated.unsafeRunSync()
  }

  override def configOf(): Config = ConfigFactory.load("replicator.conf")

  override def beforeAll() = {
    super.beforeAll()
    IntegrationSuit.start()
//    release
  }

  override def afterAll() = {
    release.unsafeRunSync()
    super.afterAll()
  }


  "Replicator" should {

    implicit val fixEquality = FixEquality.array[Byte]()

    val topic = "journal"
    val origin = Origin(system.name)

    lazy val journal = {

      // TODO we don't need consumer here...
      val topicConsumer = TopicConsumer[IO](config.journal.consumer, ec)
      implicit val log = Log[IO](actorLog)

      Journal[IO](
        Some(origin),
        kafkaProducer = producer,
        topicConsumer = topicConsumer,
        eventualJournal = eventual,
        pollTimeout = config.journal.pollTimeout,
        headCache = HeadCache.empty[IO])
    }

    def read(key: Key)(until: List[ReplicatedEvent] => Boolean) = {
      val future = Retry() {
        for {
          switch <- eventual.read[List[ReplicatedEvent]](key, SeqNr.Min, Nil) { case (xs, x) => Switch.continue(x :: xs) }.unsafeToFuture()
          events = switch.s
          result <- if (until(events)) Some(events.reverse).future else None.future
        } yield result
      }

      Await.result(future, timeout)
    }

    def append(key: Key, events: Nel[Event]) = {
      val timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      val partitionOffset = journal.append(key, events, timestamp).unsafeRunSync()
      for {
        event <- events
      } yield {
        ReplicatedEvent(event, timestamp, partitionOffset, Some(origin))
      }
    }

    def pointer(key: Key) = journal.pointer(key).unsafeRunSync()

    def topicPointers() = eventual.pointers(topic).unsafeRunSync().values

    for {
      seqNr <- List(1, 2, 10)
    } {

      s"replicate events and then delete, seqNr: $seqNr" in {

        val key = Key(id = UUID.randomUUID().toString, topic = topic)

        pointer(key) shouldEqual None

        val pointers = topicPointers()

        val expected1 = append(key, Nel(event(seqNr)))
        val partitionOffset = expected1.head.partitionOffset
        val partition = partitionOffset.partition

        for {
          offset <- pointers.get(partitionOffset.partition)
        } partitionOffset.offset should be > offset

        val actual1 = read(key)(_.nonEmpty)
        actual1 shouldEqual expected1.toList
        pointer(key) shouldEqual Some(expected1.last.seqNr)

        journal.delete(key, expected1.last.event.seqNr, Instant.now()).unsafeRunSync().map(_.partition) shouldEqual Some(partition)
        read(key)(_.isEmpty) shouldEqual Nil
        pointer(key) shouldEqual Some(expected1.last.seqNr)

        val expected2 = append(key, Nel(event(seqNr + 1), event(seqNr + 2)))
        val actual2 = read(key)(_.nonEmpty)
        actual2 shouldEqual expected2.toList
        pointer(key) shouldEqual Some(expected2.last.seqNr)
      }

      val numberOfEvents = 100

      s"replicate append of $numberOfEvents events, seqNr: $seqNr" in {
        val key = Key(id = UUID.randomUUID().toString, topic = topic)
        val events = for {
          n <- 0 until numberOfEvents
        } yield {
          event(seqNr + n, Payload("kafka-journal"))
        }
        val expected = append(key, Nel.unsafe(events))
        val actual = read(key)(_.nonEmpty)
        actual.fix shouldEqual expected.toList.fix

        pointer(key) shouldEqual Some(events.last.seqNr)
      }

      for {
        (name, events) <- List(
          ("empty", Nel(event(seqNr))),
          ("binary", Nel(event(seqNr, Payload.Binary("binary")))),
          ("text", Nel(event(seqNr, Payload.Text("text")))),
          ("json", Nel(event(seqNr, Payload.Json("json")))),
          ("empty-many", Nel(
            event(seqNr),
            event(seqNr + 1),
            event(seqNr + 2))),
          ("binary-many", Nel(
            event(seqNr, Payload.Binary("1")),
            event(seqNr + 1, Payload.Binary("2")),
            event(seqNr + 2, Payload.Binary("3")))),
          ("text-many", Nel(
            event(seqNr, Payload.Text("1")),
            event(seqNr + 1, Payload.Text("2")),
            event(seqNr + 2, Payload.Text("3")))),
          ("json-many", Nel(
            event(seqNr, Payload.Json("1")),
            event(seqNr + 1, Payload.Json("2")),
            event(seqNr + 2, Payload.Json("3")))),
          ("empty-binary-text-json", Nel(
            event(seqNr),
            event(seqNr + 1, Payload.Binary("binary")),
            event(seqNr + 2, Payload.Text("text")),
            event(seqNr + 3, Payload.Json("json")))))
      } {
        s"consume event from kafka and replicate to eventual journal, seqNr: $seqNr, payload: $name" in {
          val key = Key(id = UUID.randomUUID().toString, topic = topic)
          val pointers = topicPointers()
          val expected = append(key, events)
          val partition = expected.head.partitionOffset.partition
          val offsetBefore = pointers.getOrElse(partition, Offset.Min)
          val actual = read(key)(_.nonEmpty)
          actual.fix shouldEqual expected.toList.fix

          pointer(key) shouldEqual Some(events.last.seqNr)

          val offsetAfter = topicPointers().getOrElse(partition, Offset.Min)
          offsetAfter should be > offsetBefore
        }
      }
    }
  }

  private def event(seqNr: Int, payload: Option[Payload] = None): Event = {
    val tags = (0 to seqNr).map(_.toString).toSet
    Event(SeqNr(seqNr.toLong), tags, payload)
  }

  private def event(seqNr: Int, payload: Payload): Event = {
    event(seqNr, Some(payload))
  }
}
