package ox.plugin

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.{Flags, Symbols}
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.{Pickler, PostTyper}
import dotty.tools.dotc.util.Property

class RequireIO extends StandardPlugin:
  val name = "requireIO"
  override val description = "Require the IO capability when a Java method throws an IOException."

  def init(options: List[String]): List[PluginPhase] = new RequireIOPhase(options) :: Nil

class RequireIOPhase(ioLikeExceptionClasses: List[String]) extends PluginPhase:
  import tpd.*

  val phaseName = "requireIO"

  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(Pickler.name)

  override def allowsImplicitSearch: Boolean = true

  // exceptions, which signal that a method performs I/O
  private var ioLikeExceptions: List[Symbol] = _
  private var io: Symbol = _

  override def run(using Context): Unit = {
    ioLikeExceptions = ("java.io.IOException" :: ioLikeExceptionClasses).map(requiredClass)
    io = requiredClass("ox.IO")

    super.run
  }

  private object ioAvailableProperty extends Property.Key[Boolean]

  override def prepareForDefDef(tree: tpd.DefDef)(using ctx: Context): Context =
    val hasGivenIOParameter = tree.paramss.exists { params =>
      params.exists { param =>
        param.tpe <:< io.namedType && param.mods.is(Flags.Given)
      }
    }

    if hasGivenIOParameter then ctx.withProperty(ioAvailableProperty, Some(true)) else ctx

  private def throwsIOLikeException(a: Annotation)(using ctx: Context): Option[Symbol] =
    if a.symbol == ctx.definitions.ThrowsAnnot then
      a.argument(0).flatMap(thrownException => ioLikeExceptions.find(ioLikeException => thrownException.tpe <:< ioLikeException.namedType))
    else None

  override def transformApply(tree: Apply)(implicit ctx: Context): Tree =
    tree.fun.symbol.annotations
      .flatMap(throwsIOLikeException)
      .headOption // we only want to check once per method
      .foreach: ex =>
        val ctxAtPhase = ctx.withPhase(this) // needed so that inferImplicit works, which checks if the phase allowsImplicitSearch
        val ioAvailableAsImplicit = ctxAtPhase.typer.inferImplicit(io.namedType, EmptyTree, tree.span)(using ctxAtPhase).isSuccess

        if !ioAvailableAsImplicit && !ctxAtPhase.property(ioAvailableProperty).getOrElse(false) then
          report.error(
            s"""The ${tree.fun.symbol.showFullName} method throws an ${ex.showFullName},
               |but the ox.IO capability is not available in the implicit scope.
               |
               |Try adding a `using IO` clause to the enclosing method.
               |
               |Alternatively, you can wrap your code with `IO.unsafe`, however this should only
               |be used in special circumstances, as it bypasses Ox's tracking of I/O.""".stripMargin,
            tree.sourcePos
          )

    tree
