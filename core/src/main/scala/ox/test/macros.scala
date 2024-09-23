package ox.test

import scala.quoted.*

inline def printTree[T](inline x: T): Unit = ${ printTreeImpl('x) }
def printTreeImpl[T: Type](x: Expr[T])(using qctx: Quotes): Expr[Unit] =
  import qctx.reflect.*
  println(x.asTerm.show)
  //    println(x.asTerm.show(using Printer.TreeCode))
  // println(x.asTerm.show(using Printer.TreeStructure))
  //    println(x.asTerm.show(using Printer.TreeAnsiCode))
  '{ () }

def pipeImpl[T: Type, U: Type](t: Expr[T], f: Expr[T => U])(using qctx: Quotes): Expr[U] =
  import qctx.reflect.*
  println(f.asTerm.show)
  println(f.asTerm.show(using Printer.TreeStructure))
  null
