# Parallelize collection operations

## mapPar

```scala
import ox.syntax.mapPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

val result: List[Int] = input.mapPar(4)(_ + 1)
// (2, 3, 4, 5, 6, 7, 8, 9, 10)
```

If any transformation fails, others are interrupted and `mapPar` rethrows exception that was
thrown by the transformation. Parallelism
limits how many concurrent forks are going to process the collection.

## foreachPar

```scala
import ox.syntax.foreachPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

input.foreachPar(4)(i => println())
// Prints each element of the list, might be in any order
```

Similar to `mapPar` but doesn't return anything.

## filterPar

```scala
import ox.syntax.filterPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  
val result:List[Int] = input.filterPar(4)(_ % 2 == 0)
// (2, 4, 6, 8, 10)
``` 

Filters collection in parallel using provided predicate. If any predicate fails, rethrows the exception
and other forks calculating predicates are interrupted.

## collectPar

```scala
import ox.syntax.collectPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  
val result: List[Int] = input.collectPar(4) {
  case i if i % 2 == 0 => i + 1
} 
// (3, 5, 7, 9, 11)
```

Similar to `mapPar` but only applies transformation to elements for which
the partial function is defined. Other elements are skipped.
