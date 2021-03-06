package com.evolutiongaming.kafka.journal

import com.evolutiongaming.kafka.journal.util.TestSync
import io.prometheus.client.CollectorRegistry
import org.scalatest.{FunSuite, Matchers}

class EventualJournalMetricsSpec extends FunSuite with Matchers {
  import EventualJournalMetricsSpec._

  test("read") {
    new Scope {
      metrics.read(topic, latency = 1000)
      registry.latency("read") shouldEqual Some(1)
    }
  }

  test("read event") {
    new Scope {
      metrics.read(topic)
      registry.events() shouldEqual Some(1)
    }
  }

  test("pointer") {
    new Scope {
      metrics.pointer(topic, 1000)
      registry.latency("pointer") shouldEqual Some(1)
    }
  }

  test("pointers") {
    new Scope {
      metrics.pointers(topic, 1000)
      registry.latency("pointers") shouldEqual Some(1)
    }
  }

  private trait Scope {
    implicit val syncId = TestSync[cats.Id](cats.catsInstancesForId)
    val registry = new CollectorRegistry()
    val metrics = EventualJournalMetrics.of[cats.Id](registry, prefix)
  }
}

object EventualJournalMetricsSpec {

  val prefix = "test"
  val topic = "topic"

  implicit class CollectorRegistryOps(val self: CollectorRegistry) extends AnyVal {

    def latency(name: String) = Option {
      self.getSampleValue(
        s"${ prefix }_topic_latency_sum",
        Array("topic", "type"),
        Array(topic, name))
    }

    def events() = Option {
      self.getSampleValue(
        s"${ prefix }_events_sum",
        Array("topic"),
        Array(topic))
    }
  }
}
