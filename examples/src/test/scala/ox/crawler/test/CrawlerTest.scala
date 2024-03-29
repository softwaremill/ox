package ox.crawler.test

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import ox.discard
import ox.crawler.Crawler

class CrawlerTest extends AnyFlatSpec with Matchers with CrawlerTestData with ScalaFutures with IntegrationPatience:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(60, Seconds)),
      interval = scaled(Span(150, Millis))
    )

  for (testData <- testDataSets) {
    it should s"crawl a test data set ${testData.name}" in {
      import testData.*

      val t = timed {
        (Crawler.crawl(startingUrl, url => http(url), parseLinks) should be(expectedCounts)).discard
      }

      shouldTakeMillisMin.foreach(m => t should be >= (m))
      shouldTakeMillisMax.foreach(m => t should be <= (m))
    }
  }
