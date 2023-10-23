package ox.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.*
import ox.kafka.ConsumerSettings.AutoOffsetReset.Earliest

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.util.Random

class PerfTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  val topicIn = "data"

  val bootstrapServer = "localhost:9092"
  val producerSettings = ProducerSettings.default.bootstrapServers(bootstrapServer)

  it should "produce data" in {
    timed("produce") {
      supervised {
        given StageCapacity = StageCapacity(100)
        Source
          .range(1, 1_000_000, 1)
          .mapAsView(_ => (1 to 100).map(_ => Random.nextPrintableChar()).mkString)
          .mapAsView(m => ProducerRecord(topicIn, m))
          .applied(KafkaDrain.publish(producerSettings))
      }
    }
  }

  it should "produce 'done'" in {
    timed("produce") {
      supervised {
        Source
          .fromValues("done")
          .mapAsView(m => ProducerRecord(topicIn, m))
          .applied(KafkaDrain.publish(producerSettings))
      }
    }
  }

  it should "simple consume ox-kafka" in {
    for (i <- 1 to 3) {
      timed("ox") {
        supervised {
          given StageCapacity = StageCapacity(10) // optional
          val consumerSettings = ConsumerSettings.default("g1").bootstrapServers(bootstrapServer).autoOffsetReset(Earliest)
          val counter = new AtomicInteger(0)
          val s = KafkaSource.subscribe(consumerSettings, topicIn).map(in => in.value + "m").mapPar(5)(newValue => newValue + "p")

          @tailrec def receiveWhileNotDone(): Unit =
            s.receive().orThrow match
              case v: String if v.startsWith("done") => println(s"DONE: ${counter.get()}")
              case v: String                         => counter.incrementAndGet(); receiveWhileNotDone()

          receiveWhileNotDone()
        }
      }
    }
  }

  it should "simple consume akka-kafka" in {
    for (i <- 1 to 3) {
      timed("akka") {
        supervised {
          implicit val as: ActorSystem = ActorSystem()
          import as.dispatcher

          val consumerSettings =
            org.apache.pekko.kafka
              .ConsumerSettings(as, new StringDeserializer, new StringDeserializer)
              .withBootstrapServers(bootstrapServer)
              .withGroupId(s"g2$i")
              .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

          val control = Consumer
            .plainSource(consumerSettings, Subscriptions.topics(topicIn))
            .map(in => in.value() + "m")
            .mapAsync(5)(newValue => Future(newValue + "p"))
            .takeWhile(in => !in.startsWith("done"))
            .toMat(Sink.fold(0)((c, _) => c + 1))(Keep.both)
            .run()

          println("DONE: " + Await.result(control._2, scala.concurrent.duration.Duration.Inf))
        }
      }
    }
  }
}

def timed[T](label: String)(t: => T): T =
  val start = System.currentTimeMillis()
  val r = t
  val end = System.currentTimeMillis()
  println(s"$label Took ${end - start}ms")
  r
