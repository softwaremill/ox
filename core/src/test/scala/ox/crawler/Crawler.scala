package ox.crawler

import org.slf4j.LoggerFactory
import ox.Ox.{Fiber, forkSupervised, scoped}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import scala.annotation.tailrec

object Crawler:
  private val logger = LoggerFactory.getLogger(this.getClass)

  def crawl(crawlUrl: Url, http: Http, parseLinks: String => List[Url]): Map[Host, Int] = scoped {
    @tailrec
    def crawler(crawlerQueue: BlockingQueue[CrawlerMessage], data: CrawlerData): Map[Host, Int] =
      def handleMessage(msg: CrawlerMessage, data: CrawlerData): CrawlerData = msg match {
        case Start(url) => crawlUrl(data, url)

        case CrawlResult(url, links) =>
          val data2 = data.copy(inProgress = data.inProgress - url)

          links.foldLeft(data2) { case (d, link) =>
            val d2 = d.copy(referenceCount = d.referenceCount.updated(link.host, d.referenceCount.getOrElse(link.host, 0) + 1))
            crawlUrl(d2, link)
          }
      }

      def crawlUrl(data: CrawlerData, url: Url): CrawlerData =
        if !data.visitedLinks.contains(url) then {
          val (data2, workerQueue) = workerFor(data, url.host)
          workerQueue.put(url)
          data2.copy(
            visitedLinks = data.visitedLinks + url,
            inProgress = data.inProgress + url
          )
        } else data

      def workerFor(data: CrawlerData, host: Host): (CrawlerData, BlockingQueue[Url]) =
        data.workers.get(host) match {
          case None =>
            val workerQueue = new ArrayBlockingQueue[Url](32)
            worker(workerQueue, crawlerQueue)
            (data.copy(workers = data.workers + (host -> workerQueue)), workerQueue)
          case Some(queue) => (data, queue)
        }

      val msg = crawlerQueue.take
      val data2 = handleMessage(msg, data)
      if data2.inProgress.isEmpty then data2.referenceCount else crawler(crawlerQueue, data2)

    def worker(workerQueue: BlockingQueue[Url], crawlerQueue: BlockingQueue[CrawlerMessage]): Fiber[Unit] =
      def handleUrl(url: Url): Unit =
        val r =
          try parseLinks(http.get(url))
          catch
            case e: Exception =>
              logger.error(s"Cannot get contents of $url", e)
              List.empty[Url]
        forkSupervised(crawlerQueue.put(CrawlResult(url, r)))

      forkSupervised {
        while true do {
          val url = workerQueue.take
          handleUrl(url)
        }
      }

    val crawlerQueue = new ArrayBlockingQueue[CrawlerMessage](32)
    crawlerQueue.put(Start(crawlUrl))
    crawler(crawlerQueue, CrawlerData(Map(), Set(), Set(), Map()))
  }

  case class CrawlerData(
      referenceCount: Map[Host, Int],
      visitedLinks: Set[Url],
      inProgress: Set[Url],
      workers: Map[Host, BlockingQueue[Url]]
  )

  sealed trait CrawlerMessage
  case class Start(url: Url) extends CrawlerMessage
  case class CrawlResult(url: Url, links: List[Url]) extends CrawlerMessage
