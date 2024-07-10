package ox.scheduling

/** A random factor used for calculating the delay between subsequent retries when a backoff strategy is used for calculating the delay.
  *
  * The purpose of jitter is to avoid clustering of subsequent retries, i.e. to reduce the number of clients calling a service exactly at
  * the same time - which can result in subsequent failures, contrary to what you would expect from retrying. By introducing randomness to
  * the delays, the retries become more evenly distributed over time.
  *
  * See the <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">AWS Architecture Blog article on backoff and
  * jitter</a> for a more in-depth explanation.
  *
  * Depending on the algorithm, the jitter can affect the delay in different ways - see the concrete variants for more details.
  */
enum Jitter:
  /** No jitter, i.e. the delay just uses an exponential backoff with no adjustments. */
  case None

  /** Full jitter, i.e. the delay is a random value between 0 and the calculated backoff delay. */
  case Full

  /** Equal jitter, i.e. the delay is half of the calculated backoff delay plus a random value between 0 and the other half. */
  case Equal

  /** Decorrelated jitter, i.e. the delay is a random value between the initial delay and the last delay multiplied by 3. */
  case Decorrelated
