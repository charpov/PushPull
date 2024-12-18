---
title: Note on Push versus Pull
numbersections: true
cnote: 17 Dec 2024
---

The following are code snippets (in Scala) that I wrote to try to (finally) understand the differences between---and combinations of---*push* and *pull*.

# Pure Pull

```scala
trait Pull[+A]:
   up =>
   def next(): Option[A]

   def map[B](f: A => B): Pull[B] = Mapper(f)

   private class Mapper[B](f: A => B) extends Pull[B]:
      def next(): Option[B] = up.next().map(f)
```

This `Pull` type defines a method to pull the next value, using options to mark the end of the stream.
The `map` method works as expected: retrieve a value from upstream, if any, and apply the given function to it.
(The Scala syntax `up =>` is used to give a name to the outer instance; I could have written `Pull.this.next().map(f)` instead, as in Java.)
This `Pull` type can be used as follows:

```scala
class From[A](iterator: Iterator[A]) extends Pull[A]:
   def this(values: IterableOnce[A]) = this(values.iterator)
   def next(): Option[A] = Option.when(iterator.hasNext)(iterator.next())

From(1 to 5)
   .filter(isEven)
   .map(x => s"[$x]")
   .flatMap(str => From(str))
   .forEach(print)
```

This produces the output `[2][4]`, printed one character at a time.
This `Pull` type is similar to Scala's iterators and, like them, can be made covariant (that is, `Pull[String]` is a subtype of `Pull[Any]`).

# Pure Push

While `Pull` feels very natural to me, `Push` doesn't.
So, my attempt at writing a pure push stream is designed by mirroring everything in `Pull`, to the extend that it is possible (it doesn't quite work for the definition of `flatMap`, for instance):

```scala
trait Push[A]:
   down =>
   def push(value: A): Unit

   def map[B](f: B => A): Push[B] = Mapper(f)

   private class Mapper[B](f: B => A) extends Push[B]:
      def push(value: B): Unit = down.push(f(value))
```

Streams are bottomless (one can always push more to them), but I suppose the `push` method could return a Boolean to indicate whether values are accepted or rejected.
Note how the `map` method *does not* have its standard signature: The argument function has type `B => A` instead of `A => B`.
It is implemented by processing values through the function before pushing them downstream.
Push streams would be used as follows:

```scala
object Sink extends Push[Any]:
   def push(value: Any): Unit = ()

val pusher: Push[Int] =
   Sink
      .forEach(print)
      .flatMap[String](str => str.toSeq)
      .map[Int](x => s"[$x]")
      .filter(isEven)
for n <- 1 to 5 do pusher.push(n)
```

A pipeline is created by applying to a bottomless sink all the desired transformation *in reverse order*, and values are then pushed *by hand* into the pipeline.
The Scala compiler needs help with types (because its inference tends to work from left to right).
The code is terrible.
It doesn't make much sense.
This cannot possibly be what is meant when referring to "push streams".

# Java Streams Like

After digging into the source code of Java streams, this is how I understand them to work:

```scala
type Stream[T] = Stage[Nothing, T]

trait Stage[-A, B]:
   up =>
   def run(): Unit                 = source.run()
   protected var source: Stream[?] = uninitialized
   protected var down: Stage[B, ?] = uninitialized

   def push(value: A): Unit

   def map[C](f: B => C): Stream[C] =
      val m = Mapper(f)
      down = m
      m

   private class Mapper[C](f: B => C) extends Stage[B, C]:
      source = up.source
      def push(value: B): Unit = down.push(f(value))
```

The main type is `Stage`, which represents a stage in a pipeline, from a type `A` (upstream) to a type `B` (downstream).
A stream is a special kind of stage with no upstream.
Each stage keeps explicit references to its downstream and to the source of the pipeline.
There is no need to keep a reference to the upstream, as values only travel in one direction.
The reference to the source is needed to "run" the pipeline (using what Java calls a "terminal operation").

Types are a bit messy: The exact type of the source and downstream are not known until the stage is connected to another stage (there may be a fancier way to model that with Scala types, but I'm mimicking what's done in Java here).
Having `Stage` be contravariant in its first type makes it possible for `Stage[Any,B]` to be a subtype of `Stream[B]` and simplifies the implementation.
However, I can't make `Stage` covariant in its second type, and therefore `Stream[String]` *is not* a subtype of `Stream[Any]`.

The implementation of `map` is similar to that of the "pure push", except that it explicitly connects the newly constructed stage to its upstream.
Notice, however, that the method now has the desired type (`Stream[B] => (B => C) => Stream[C]`).

Streams are used like this:

```scala
class From[A](values: IterableOnce[A]) extends Stage[Nothing, A]:
   def push(value: Nothing): Unit = throw AssertionError() // never used, unreachable
   override def run(): Unit       = for v <- values.iterator do down.push(v)
   source = this // no source, special run behavior

From(1 to 5)
   .filter(isEven)
   .map(x => s"[$x]")
   .flatMap(str => From(str))
   .forEach(print)
   .run()
```

Class `From` implements a source, which starts pushing values when the final `run` is called.
It is its own source and nothing is ever pushed to it.

# Implementing `filter`, `forEach` and `flatMap`

The implementation of `map` is equally simple in the pull and push variants: pull then apply the function versus apply the function and then push.
The story changes when implementing other methods.
These are the simplest implementations of `filter`, `forEach` and `flatMap` that I could think of in the case of `Pull`:

```scala
   def filter(p: A => Boolean): Pull[A]     = Filterer(p)
   def flatMap[B](f: A => Pull[B]): Pull[B] = FlatMapper(f)

   def forEach(f: A => Any): Unit =
      var opt = next()
      while opt.nonEmpty do
         f(opt.get)
         opt = next()

   private class Filterer(p: A => Boolean) extends Pull[A]:
      def next(): Option[A] =
         @tailrec def dig(): Option[A] =
            val opt = up.next()
            if opt.isEmpty then opt     // end of upstream
            else if p(opt.get) then opt // found next value
            else dig()
         dig()

   private class FlatMapper[B](f: A => Pull[B]) extends Pull[B]:
      private var buffer: Pull[B] = null
      def next(): Option[B] =
         @tailrec def dig(): Option[B] =
            if buffer == null then
               up.next() match
                  case None => None
                  case Some(value) =>
                     buffer = f(value)
                     dig()
            else
               buffer.next() match
                  case None =>
                     buffer = null
                     dig()
                  case some => some
         dig()
```

The difficulty in `filter` is that the method must keep retrieving upstream values until one passes the test or the upstream is exhausted.
The case of `flatMap` is worse: The intermediate stream obtained by applying the function can be empty, in which case more values must be retrieved from upstream, if any; or it can contain several values, in which case they need to be buffered until they can be used in later downstream calls to `next`.
(The code uses tail recursion because it works better than while loops for these scenarios; tail recursive functions are compiled into loops by the Scala compiler.)

Contrast this with the implementation of the same methods in the Java-like streams:

```scala
   def filter(p: B => Boolean): Stream[B] =
      val stream = Filterer(p)
      down = stream
      stream

   def flatMap[C](f: B => Stream[C]): Stream[C] =
      val m = FlatMapper(f)
      down = m
      m

   def forEach(f: B => Any): Stream[Nothing] =
      val s = ForEacher(f)
      down = s
      s

   private class Filterer(p: B => Boolean) extends Stage[B, B]:
      source = up.source
      def push(value: B): Unit = if p(value) then down.push(value)

   private class FlatMapper[C](f: B => Stream[C]) extends Stage[B, C]:
      source = up.source
      def push(value: B): Unit = f(value).forEach(down.push).run()

   private class ForEacher(f: B => Any) extends Stage[B, Nothing]:
      source = up.source
      def push(value: B): Unit = f(value)
```

The implementations are *much* simpler!
In the case of `filter`, push the value down if it passes the test, otherwise do nothing.
In the case of `flatMap`, push the values down one by one, whether there are zero, one, or more.

\pagebreak

# Actual Java Streams

The main differences between the toy implementation above and the real Java streams, as far as I can tell, are:

- I omitted the code that checks that standalone stages are not run and that streams are not reused after a terminal operation. I should have a few flags here and there to disallow improper calls to `run`.

- Java embeds the "run" inside terminal stages. For instance, `findAny` is implemented as a terminal stage that (implicitly) runs the pipeline.

- Java uses `Spliterator` (or even `Supplier<Spliterator>`) as the source. This type is designed for "splitting" and concurrent execution. In addition to "run", there is also a "run in parallel" method.

- Runs are performed more lazily. Java uses an `evaluate` function that uses the source (like my `run`) but also keeps track of the state of the pipeline. This way, it can stop pushing values as soon as the final stage is satisfied (e.g., the `findAny` stage is satisfied as soon as one value reaches it, while the `toList` stage will consume the entire stream).

- For the same reason, they need to be more careful in their implementation of `flatMap`. My naive implementation pushes the entire sub-stream downstream, but you could (and should) stop pushing once the main pipeline has had enough. (There was a bug there in Java 21, but it has been resolved in Java 23.)

- They also need to be careful with the execution stack. In my implementation, calls to `next` are nested and will blow the stack on a large-enough pipeline. They have to be more careful in their `evaluate` function (although I didn't quite follow what they were doing).

As a side note, the Java code does with types the sort of things I tell students never to do.
I find `Stage[B,?]` ugly enough, but they actually use `Stage` as a type (not even `Stage<?,?>`), which Scala refuses but Java still lets you do with a warning (which they disable with an annotation).

# Reactive Streams?

Leaving aside issues of threading and timing, I suspect the main conceptual difference with reactive streams is the lack of back pressure.
I envision a `run(max)` method that calls for upstream values with a bound, instead of my `run()` that pushes everything (or Java's `evaluate` that evaluates enough to terminate).
I didn't try to implement that, as I'm not even sure it fully makes sense without involving threads, which is not something I'm ready to do.
