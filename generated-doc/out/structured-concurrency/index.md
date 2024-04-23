# What is structured concurrency?

Structured concurrency is an approach where the lifetime of a thread is determined by the syntactic structure of the
code.

First introduced by [Martin Sústrik](https://250bpm.com/blog:71/) and later popularized by 
[Nathaniel J. Smith](https://vorpus.org/blog/notes-on-structured-concurrency-or-go-statement-considered-harmful/), 
structured concurrency made its way into Python, Kotlin, Java and now Scala.

The basic concept in structured concurrency are scopes, within which concurrently running threads of execution can be
started. The scope only finishes once all threads started within finish (either successfully, or with an error). Thus,
it isn't possible to "leak" threads outside of a method. Threads become more a method's implementation detail, rather
than an effect.

These characteristics make structured concurrency an ideal candidate to make concurrency safer in direct style 
programming, while keeping blocking-like method calls. Structured concurrency enables local reasoning on the threading 
effects, which is also one of the prime tenets of functional programming!

Ox extends the structured concurrency concepts with various forms of error handling, described in the following sections.
