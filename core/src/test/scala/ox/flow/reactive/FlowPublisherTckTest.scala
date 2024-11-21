package ox.flow.reactive

import org.reactivestreams.tck.flow.FlowPublisherVerification
import org.reactivestreams.tck.TestEnvironment
import java.util.concurrent.Flow.Publisher
import ox.flow.Flow
import ox.supervised
import org.scalatest.funsuite.AnyFunSuite
import ox.Ox

class FlowPublisherTckTest extends AnyFunSuite:
  var ox: Ox = null
  val verification = new FlowPublisherVerification[Int](new TestEnvironment()):
    override def createFailedFlowPublisher(): Publisher[Int] = Flow.failed[Int](new Exception("failed")).toPublisher(using ox)

    override def createFlowPublisher(elements: Long): Publisher[Int] = Flow.fromIterable(1 to elements.toInt).toPublisher(using ox)

  verification.getClass().getMethods().foreach { m =>
    if m.getAnnotation(classOf[org.testng.annotations.Test]) != null then
      if m.getName().startsWith("untested_") then ignore(m.getName()) {}
      else
        test(m.getName()) {
          supervised {
            ox = summon[Ox]
            m.invoke(verification)
          }
        }
  }
end FlowPublisherTckTest
