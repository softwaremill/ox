package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ox.either.ok

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
  }

  it should "work correctly when invoked on options" in {
    val r1 = either((optionOk1.ok(), optionOk2.ok()))
    r1 shouldBe (Right(10, "x"))

    val r2 = either((optionOk1.ok(), optionFail.ok()))
    r2 shouldBe Left(())
  }
