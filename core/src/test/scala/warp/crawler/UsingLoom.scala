package warp.crawler

import org.slf4j.LoggerFactory
import warp.WarpSupervised.Fiber

import java.util
import java.util.concurrent.ArrayBlockingQueue
import scala.annotation.tailrec

object UsingLoom {
//  private val log = LoggerFactory.getLogger(this.getClass)
//
//  def crawl(crawlUrl: Url, http: Http, parseLinks: String => List[Url]): Map[Host, Int] = {
//
//    @tailrec
//    def crawler(crawlerQueue: util.Queue[CrawlerMessage], data: CrawlerData): Map[Host, Int] = {
//      def handleMessage(msg: CrawlerMessage, data: CrawlerData): CrawlerData = msg match {
//        case Start(url) =>
//          crawlUrl(data, url)
//
//        case CrawlResult(url, links) =>
//          val data2 = data.copy(inProgress = data.inProgress - url)
//
//          links.fold(data2) { case (d, link) =>
//            val d2 = d.copy(referenceCount = d.referenceCount.updated(link.host, d.referenceCount.getOrElse(link.host, 0) + 1))
//            crawlUrl(d2, link)
//          }
//      }
//
//      def crawlUrl(data: CrawlerData, url: Url): CrawlerData = {
//        if (!data.visitedLinks.contains(url)) {
//          workerFor(data, url.host).flatMap { case (data2, workerQueue) =>
//            workerQueue.offer(url)
//            data2.copy(
//              visitedLinks = data.visitedLinks + url,
//              inProgress = data.inProgress + url
//            )
//          }
//        } else data
//      }
//
//      def workerFor(data: CrawlerData, host: Host): (CrawlerData, util.Queue[Url]) = {
//        data.workers.get(host) match {
//          case None =>
//            val workerQueue = new ArrayBlockingQueue[Url](32)
//            worker(workerQueue, crawlerQueue)
//            (data.copy(workers = data.workers + (host -> workerQueue)), workerQueue)
//          case Some(queue) => (data, queue)
//        }
//      }
//
//      val msg = crawlerQueue.take
//      val data2 = handleMessage(msg, data)
//      if (data2.inProgress.isEmpty) {
//        data2.referenceCount
//      } else {
//        crawler(crawlerQueue, data2)
//      }
//    }
//
//    def worker(workerQueue: util.Queue[Url], crawlerQueue: util.Queue[CrawlerMessage]): Fiber[Unit] = {
//      def handleUrl(url: Url): Unit = {
//        val r =
//          try parseLinks(http.get(url))
//          catch
//            case e: Exception =>
//              logger.error(s"Cannot get contents of $url", t)
//              List.empty[Url]
//        crawlerQueue.offer(CrawlResult(url, r)).fork.void
//      }
//
//      workerQueue.take
//        .flatMap(handleUrl)
//        .forever
//        .fork
//    }
//
//    val crawl = for {
//      crawlerQueue <- Queue.bounded[CrawlerMessage](32)
//      _ <- crawlerQueue.offer(Start(crawlUrl))
//      r <- crawler(crawlerQueue, CrawlerData(Map(), Set(), Set(), Map()))
//    } yield r
//
//    IO.supervise(crawl)
//  }
//
//  case class CrawlerData(referenceCount: Map[Host, Int], visitedLinks: Set[Url], inProgress: Set[Url], workers: Map[Host, Queue[Url]])
//
//  sealed trait CrawlerMessage
//  case class Start(url: Url) extends CrawlerMessage
//  case class CrawlResult(url: Url, links: List[Url]) extends CrawlerMessage
}
