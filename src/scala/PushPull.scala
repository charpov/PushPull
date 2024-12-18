import scala.annotation.tailrec
import scala.compiletime.uninitialized
import tinyscalautils.util.isEven

object PurePull:
   trait Pull[+A]:
      up =>
      def next(): Option[A]

      def filter(p: A => Boolean): Pull[A]     = Filterer(p)
      def map[B](f: A => B): Pull[B]           = Mapper(f)
      def flatMap[B](f: A => Pull[B]): Pull[B] = FlatMapper(f)

      def forEach(f: A => Any): Unit =
         var opt = next()
         while opt.nonEmpty do
            f(opt.get)
            opt = next()

      private class Mapper[B](f: A => B) extends Pull[B]:
         def next(): Option[B] = up.next().map(f)

      private class Filterer(p: A => Boolean) extends Pull[A]:
         def next(): Option[A] =
            @tailrec def dig(): Option[A] =
               val opt = up.next()
               if opt.isEmpty then opt     // end of upstream
               else if p(opt.get) then opt // found next value
               else dig()
            dig()

      // noinspection ConvertNullInitializerToUnderscore
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
   end Pull

   def demo(): Unit =
      class From[A](iterator: Iterator[A]) extends Pull[A]:
         def this(values: IterableOnce[A]) = this(values.iterator)
         def next(): Option[A] = Option.when(iterator.hasNext)(iterator.next())

      From(1 to 5)
         .filter(isEven)
         .map(x => s"[$x]")
         .flatMap(str => From(str))
         .forEach(print)
      println()
end PurePull

object PurePush:
   trait Push[A]:
      down =>
      def push(value: A): Unit

      def filter(p: A => Boolean): Push[A]    = Filterer(p)
      def map[B](f: B => A): Push[B]          = Mapper(f)
      def flatMap[B](f: B => Seq[A]): Push[B] = FlatMapper(f)
      def forEach(f: A => Any): Push[A]       = ForEacher(f)

      private class Mapper[B](f: B => A) extends Push[B]:
         def push(value: B): Unit = down.push(f(value))

      private class Filterer(p: A => Boolean) extends Push[A]:
         def push(value: A): Unit = if p(value) then down.push(value)

      private class FlatMapper[B](f: B => Seq[A]) extends Push[B]:
         def push(value: B): Unit = for x <- f(value) do down.push(x)

      private class ForEacher(f: A => Any) extends Push[A]:
         def push(value: A): Unit = f(value)
   end Push

   def demo(): Unit =
      object Sink extends Push[Any]:
         def push(value: Any): Unit = ()

      val pusher: Push[Int] =
         Sink
            .forEach(print)
            .flatMap[String](str => str.toSeq)
            .map[Int](x => s"[$x]")
            .filter(isEven)
      for n <- 1 to 5 do pusher.push(n)
      println()
end PurePush

//noinspection DuplicatedCode
object JavaStream:
   type Stream[T] = Stage[Nothing, T]

   trait Stage[-A, B]:
      up =>
      def run(): Unit                 = source.run()
      protected var source: Stream[?] = uninitialized
      protected var down: Stage[B, ?] = uninitialized

      def push(value: A): Unit

      def filter(p: B => Boolean): Stream[B] =
         val stream = Filterer(p)
         down = stream
         stream

      def map[C](f: B => C): Stream[C] =
         val m = Mapper(f)
         down = m
         m

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

      private class Mapper[C](f: B => C) extends Stage[B, C]:
         source = up.source
         def push(value: B): Unit = down.push(f(value))

      private class FlatMapper[C](f: B => Stream[C]) extends Stage[B, C]:
         source = up.source
         def push(value: B): Unit = f(value).forEach(down.push).run()

      private class ForEacher(f: B => Any) extends Stage[B, Nothing]:
         source = up.source
         def push(value: B): Unit = f(value)
   end Stage

   def demo(): Unit =
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
      println()
end JavaStream

@main def PushPullTest(): Unit =
   PurePull.demo()
   PurePush.demo()
   JavaStream.demo()
