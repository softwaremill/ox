package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import ox.channels.Channel

class FlowOpsPipeToTest extends AnyFlatSpec with Matchers with Eventually:

  it should "pipe one source to another" in supervised:
    val c1 = Flow.fromValues(1, 2, 3)
    val c2 = Channel.rendezvous[Int]

    forkDiscard:
      c1.runPipeToSink(c2, propagateDone = false)
      c2.done()

    c2.toList shouldBe List(1, 2, 3)

  it should "pipe one source to another (with done propagation)" in supervised:
    val c1 = Flow.fromValues(1, 2, 3)
    val c2 = Channel.rendezvous[Int]

    forkDiscard:
      c1.runPipeToSink(c2, propagateDone = true)

    c2.toList shouldBe List(1, 2, 3)
end FlowOpsPipeToTest
