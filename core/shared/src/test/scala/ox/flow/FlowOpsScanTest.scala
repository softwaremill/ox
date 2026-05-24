package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsScanTest extends AnyFlatSpec with Matchers:
  behavior of "scan"

  it should "scan the empty flow" in:
    val flow: Flow[Int] = Flow.empty
    val scannedFlow = flow.scan(0)((acc, el) => acc + el)
    scannedFlow.runToList() shouldBe List(0)

  it should "scan a flow of summed Int" in:
    val flow = Flow.fromValues(1 to 10: _*)
    val scannedFlow = flow.scan(0)((acc, el) => acc + el)
    scannedFlow.runToList() shouldBe List(0, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55)

  it should "scan a flow of multiplied Int" in:
    val flow = Flow.fromValues(1 to 10: _*)
    val scannedFlow = flow.scan(1)((acc, el) => acc * el)
    scannedFlow.runToList() shouldBe List(1, 1, 2, 6, 24, 120, 720, 5040, 40320, 362880, 3628800)

  it should "scan a flow of concatenated String" in:
    val flow = Flow.fromValues("f", "l", "o", "w")
    val scannedFlow = flow.scan("my")((acc, el) => acc + el)
    scannedFlow.runToList() shouldBe List("my", "myf", "myfl", "myflo", "myflow")

end FlowOpsScanTest
