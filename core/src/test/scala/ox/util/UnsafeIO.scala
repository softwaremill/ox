package ox.util

import ox.IO

trait UnsafeIO:
  given IO = new IO {}
