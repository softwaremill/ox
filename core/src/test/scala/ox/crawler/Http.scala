package ox.crawler

type Host = String
case class Url(host: Host, path: String)

trait Http:
  def get(url: Url): String
