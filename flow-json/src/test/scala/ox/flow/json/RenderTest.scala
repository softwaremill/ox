package ox.flow.json

import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.flow.Flow

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

class RenderTest extends AnyFlatSpec with Matchers:

  behavior of "renderNdjson"

  it should "render one chunk holding a value and its LF, leaving line breaks escaped" in:
    val values = Flow.fromValues(Person("Ada", 36), Person("Łukasz\nJr", 41))
    values.renderNdjson().runToList().map(_.asStringUtf8) shouldBe
      List("{\"name\":\"Ada\",\"age\":36}\n", "{\"name\":\"Łukasz\\nJr\",\"age\":41}\n")

  it should "render an empty flow as nothing" in:
    Flow.empty[Person].renderNdjson().runToList() shouldBe Nil

  it should "reject an indenting config" in:
    an[IllegalArgumentException] should be thrownBy Flow.fromValues(Person("Ada", 36)).renderNdjson(WriterConfig.withIndentionStep(2))

  behavior of "renderJsonArray"

  it should "render one chunk holding a value and its separator" in:
    Flow.fromValues(1, 2, 3).renderJsonArray().runToList().map(_.asStringUtf8) shouldBe List("[1", ",2", ",3", "]")

  it should "render an empty flow as an empty array" in:
    Flow.empty[Person].renderJsonArray().runToList().map(_.asStringUtf8) shouldBe List("[]")

  it should "leave partial output and propagate an upstream failure" in:
    val failure = new IllegalStateException("upstream failed")
    val output = ByteArrayOutputStream()
    val values = Flow.concat(List(Flow.fromValues(Person("Ada", 36)), Flow.failed[Person](failure)))
    (the[IllegalStateException] thrownBy values.renderJsonArray().runToOutputStream(output)) shouldBe failure
    output.toString(UTF_8) shouldBe "[{\"name\":\"Ada\",\"age\":36}"
end RenderTest
