package ox.plugin

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.{Pickler, PostTyper}
import dotty.tools.dotc.util.Property

class RequireIO extends StandardPlugin:
  val name = "requireIO"
  override val description = "Require the IO capability when a Java method throws an IOException."

  def init(options: List[String]): List[PluginPhase] = new RequireIOPhase :: Nil

class RequireIOPhase extends PluginPhase:
  import tpd.*

  val phaseName = "requireIO"

  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(Pickler.name)

  override def allowsImplicitSearch: Boolean = true

  private var ioException: Symbol = _
  private var io: Symbol = _

  override def run(using Context): Unit = {
    val javaIOPackage: Symbol = requiredPackage("java.io").moduleClass
    ioException = javaIOPackage.requiredClass("IOException")

    val oxPackage: Symbol = requiredPackage("ox").moduleClass
    io = oxPackage.requiredClass("IO")

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

  private def throwsIOException(a: Annotation)(using ctx: Context): Boolean =
    a.symbol == ctx.definitions.ThrowsAnnot && a.argument(0).exists(_.tpe.<:<(ioException.namedType))

  override def transformApply(tree: Apply)(implicit ctx: Context): Tree =
    if tree.fun.symbol.annotations.exists(throwsIOException) then
      val ctxAtPhase = ctx.withPhase(this) // needed so that inferImplicit works, which checks if the phase allowsImplicitSearch
      val ioAvailableAsImplicit = ctxAtPhase.typer.inferImplicit(io.namedType, EmptyTree, tree.span)(using ctxAtPhase).isSuccess

      if !ioAvailableAsImplicit && !ctxAtPhase.property(ioAvailableProperty).getOrElse(false) then
        report.error(
          s"""The ${tree.fun.symbol.showFullName} method throws an IOException, but the ox.IO
             |capability is not available in the implicit scope.
             |
             |Try adding a `using IO` clause to the enclosing method.
             |
             |Alternatively, you can wrap your code with `IO.unsafe`, however this should only
             |be used in special circumstances, as it bypasses Ox's IO-tracking.""".stripMargin,
          tree.sourcePos
        )

    tree
