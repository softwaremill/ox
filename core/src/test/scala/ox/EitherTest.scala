package ox

import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.either.{fail, ok}

import scala.util.boundary.Label

class EitherTest extends AnyFlatSpec with Matchers:
  val ok1: Either[Int, String] = Right("x")
  val ok2: Either[Int, String] = Right("y")
  val ok3: Either[Int, Long] = Right(50L)
  val fail1: Either[Int, String] = Left(1)
  val fail2: Either[Int, String] = Left(2)
  val fail3: Either[String, String] = Left("x")
  val optionOk1: Option[Int] = Some(10)
  val optionOk2: Option[String] = Some("x")
  val optionFail: Option[Int] = None

  "either" should "work correctly when invoked on eithers" in {
    val r1 = either((ok1.ok(), ok2.ok()))
    r1 shouldBe Right(("x", "y"))

    //

    val r2 = either((ok1.ok(), fail1.ok()))
    r2 shouldBe Left(1)

    //

    val r3 = either((fail1.ok(), fail2.ok()))
    r3 shouldBe Left(1)

    //

    val r4 = either((ok1.ok(), ok3.ok()))
    r4 shouldBe Right(("x", 50L))

    //

    val r5: Either[Int | String, (String, String)] = either((fail3.ok(), fail1.ok()))
    r5 shouldBe Left("x")

    //

    val r6 = either("x".fail())
    r6 shouldBe Left("x")
  }

  it should "work correctly when invoked on options" in {
    val r1 = either((optionOk1.ok(), optionOk2.ok()))
    r1 shouldBe (Right(10, "x"))

    val r2 = either((optionOk1.ok(), optionFail.ok()))
    r2 shouldBe Left(())

    val r3: Either[Unit, (Int, Int)] = either((optionOk1.ok(), optionFail.ok()))
    r3 shouldBe Left(())
  }

  it should "report a proper compilation error when used outside of either:" in {
    val e = intercept[TestFailedException](assertCompiles("ok1.ok()"))

    e.getMessage should include("`.ok()` can only be used within an `either` call.")
  }

  it should "report a proper compilation error when wrong error type is used for ok() (explicit type params)" in {
    val e = intercept[TestFailedException](assertCompiles("either[String, String](fail1.ok())"))

    e.getMessage should include("The enclosing `either` call uses a different error type.")
  }

  it should "report a proper compilation error when wrong successful type is used (explicit type params)" in {
    val e = intercept[TestFailedException](assertCompiles("either[Int, Int](fail1.ok())"))

    e.getMessage should include("Found:    String")
    e.getMessage should include("Required: Int")
  }

  it should "report a proper compilation error when wrong type annotation is used for ok() (error)" in {
    val e = intercept[TestFailedException](assertCompiles("val r: Either[String, String] = either(fail1.ok())"))

    e.getMessage should include("The enclosing `either` call uses a different error type.")
  }

  it should "report a proper compilation error when wrong type annotation is used (success)" in {
    val e = intercept[TestFailedException](assertCompiles("val r: Either[Int, Int] = either(fail1.ok())"))

    e.getMessage should include("Found:    String")
    e.getMessage should include("Required: Int")
  }

  it should "report a proper compilation error when wrong error type is used for fail() (explicit type params)" in {
    val e = intercept[TestFailedException](assertCompiles("""either[Int, Int]("x".fail())"""))

    e.getMessage should include("The enclosing `either` call uses a different error type.")
  }

  it should "report a proper compilation error when wrong type annotation is used for fail() (error)" in {
    val e = intercept[TestFailedException](assertCompiles("""val r: Either[Int, Int] = either("x".fail())"""))

    e.getMessage should include("The enclosing `either` call uses a different error type.")
  }

  it should "catch exceptions" in {
    catching(throw new RuntimeException("boom")).left.map(_.getMessage) shouldBe Left("boom")
  }

  it should "not catch fatal exceptions" in {
    val e = intercept[InterruptedException](catching(throw new InterruptedException()))

    e shouldBe a[InterruptedException]
  }

  it should "work when combined with mapPar" in {
    def intToEither(i: Int): Either[String, Int] =
      if i % 2 == 0 then Right(i) else Left(s"$i is odd")

    val r1 = either((1 to 20).toVector.mapPar(3)(i => Right(i).ok()))
    r1 shouldBe Right(1 to 20)

    val r2 = either((1 to 20).toVector.mapPar(3)(i => intToEither(i).ok()))
    (1 to 20).filter(_ % 2 == 1).map(i => Left(s"$i is odd")) should contain(r2)
  }

  it should "not allow nesting of eithers" in {
    receivesNoEitherNestingError("""
      val effectMatchingInnerEither: Either[String, Int] = Right(3)
      val effectMatchingOuterEither: Either[Throwable, Unit] = Left(Exception("oh no"))
      val outer: Either[Throwable, Unit] = either {
        val inner: Either[String, Int] = either {
          effectMatchingOuterEither.ok()
          effectMatchingInnerEither.ok()
        }

        ()
      }
      """)
  }

  private transparent inline def receivesNoEitherNestingError(inline code: String): Unit =
    val errs = scala.compiletime.testing.typeCheckErrors(code)
    if !errs
        .map(_.message)
        .contains(
          "Nesting of either blocks is not allowed as it's error prone, due to type inference. Consider extracting the nested either block to a separate function."
        )
    then throw Exception(errs.mkString("\n"))
