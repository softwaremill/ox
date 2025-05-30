package ox.scheduling

enum Jitter:
  /** Full jitter, i.e. the delay is a random value between 0 and the interval. */
  case Full

  /** Equal jitter, i.e. the delay is half of the interval plus a random value between 0 and the other half. */
  case Equal
end Jitter
