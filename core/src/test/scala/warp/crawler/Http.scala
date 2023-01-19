package warp.crawler

trait Http {
  def get(url: Url): String
}
